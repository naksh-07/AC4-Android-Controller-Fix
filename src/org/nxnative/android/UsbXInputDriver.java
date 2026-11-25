package org.nxnative.android;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Direct USB Host XInput reader for wired Xbox 360 controllers (VID 0x045e, PID 0x028e),
 * especially ShanWan clones whose kernel xpad handshake stalls on Android 5.10 kernels
 * (stuck on LED #2 with zero input events until woken via USB Host control transfer).
 */
final class UsbXInputDriver {
    private static final String TAG = "AC4UsbPad";
    private static final String ACTION_USB_PERMISSION =
            "org.nxnative.game.t010044700deb0000.USB_XINPUT_PERMISSION";

    static final int BTN_UP = 0x0001;
    static final int BTN_DOWN = 0x0002;
    static final int BTN_LEFT = 0x0004;
    static final int BTN_RIGHT = 0x0008;
    static final int BTN_START = 0x0010;
    static final int BTN_BACK = 0x0020;
    static final int BTN_LS = 0x0040;
    static final int BTN_RS = 0x0080;
    static final int BTN_LB = 0x0100;
    static final int BTN_RB = 0x0200;
    static final int BTN_GUIDE = 0x0400;
    static final int BTN_A = 0x1000;
    static final int BTN_B = 0x2000;
    static final int BTN_X = 0x4000;
    static final int BTN_Y = 0x8000;

    static final class State {
        final int buttons;
        final int lt;
        final int rt;
        final int lx;
        final int ly;
        final int rx;
        final int ry;

        State(int buttons, int lt, int rt, int lx, int ly, int rx, int ry) {
            this.buttons = buttons;
            this.lt = lt;
            this.rt = rt;
            this.lx = lx;
            this.ly = ly;
            this.rx = rx;
            this.ry = ry;
        }
    }

    interface Listener {
        void onUsbState(State state);
        void onUsbStatus(String message, boolean isUserHint);
        void onUsbDisconnected();
    }

    /** Parses an Xbox 360 20-byte XInput packet (message type 0x00, length >= 14). */
    static State parseReport(byte[] buf, int len) {
        if (buf == null || len < 14 || buf[0] != 0x00 || (buf[1] & 0xFF) < 14) {
            return null;
        }
        int buttons = (buf[2] & 0xFF) | ((buf[3] & 0xFF) << 8);
        int lt = buf[4] & 0xFF;
        int rt = buf[5] & 0xFF;
        int lx = s16(buf[6], buf[7]);
        int ly = s16(buf[8], buf[9]); // Raw XInput Y is already up-positive
        int rx = s16(buf[10], buf[11]);
        int ry = s16(buf[12], buf[13]); // Raw XInput Y is already up-positive
        return new State(buttons, lt, rt, lx, ly, rx, ry);
    }

    private static int s16(byte lo, byte hi) {
        return (short) ((lo & 0xFF) | ((hi & 0xFF) << 8));
    }

    static byte[] rumblePacket(int big, int small) {
        int b = Math.max(0, Math.min(255, big));
        int s = Math.max(0, Math.min(255, small));
        return new byte[] {0x00, 0x08, 0x00, (byte) b, (byte) s, 0x00, 0x00, 0x00};
    }

    private final Context context;
    private final Handler main;
    private final Listener listener;
    private UsbManager usb;
    private boolean registered;
    private boolean resumed;
    private volatile boolean running;
    private UsbDeviceConnection connection;
    private UsbInterface claimedInterface;
    private UsbEndpoint epOut;
    private Thread readerThread;
    private int activeDeviceId = -1;
    private int permissionRequestedId = -1;
    private int lastRumbleKey = 0;
    private boolean kernelBlockedNotified;

    private final ExecutorService rumbleExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "AC4UsbRumble");
                t.setDaemon(true);
                return t;
            });

    private final Runnable probeLoop = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            if (!running) {
                UsbDevice dev = findXInputDevice();
                if (dev != null) {
                    tryOpen(dev);
                }
            }
            main.postDelayed(this, 2000L);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                Log.i(TAG, "USB permission " + (granted ? "granted" : "denied")
                        + (dev == null ? "" : " for " + hex(dev.getVendorId()) + ":" + hex(dev.getProductId())));
                if (granted && dev != null) {
                    startDevice(dev);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                kernelBlockedNotified = false;
                permissionRequestedId = -1;
                probeSoon();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (dev != null && dev.getDeviceId() == activeDeviceId) {
                    stopStream();
                }
            }
        }
    };

    UsbXInputDriver(Context context, Handler main, Listener listener) {
        this.context = context.getApplicationContext();
        this.main = main;
        this.listener = listener;
    }

    void resume() {
        resumed = true;
        if (usb == null) {
            usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        }
        if (!registered) {
            IntentFilter f = new IntentFilter();
            f.addAction(ACTION_USB_PERMISSION);
            f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, f);
            }
            registered = true;
        }
        main.removeCallbacks(probeLoop);
        probeSoon();
        main.postDelayed(probeLoop, 2000L);
    }

    void pause() {
        resumed = false;
        main.removeCallbacks(probeLoop);
        stopStream();
        if (registered) {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            registered = false;
        }
    }

    boolean isActive() {
        return running && connection != null;
    }

    private void probeSoon() {
        main.postDelayed(() -> {
            if (!resumed || running) return;
            UsbDevice dev = findXInputDevice();
            if (dev != null) {
                Log.i(TAG, "USB XInput device visible: " + hex(dev.getVendorId()) + ":" + hex(dev.getProductId())
                        + " mfr=" + dev.getManufacturerName() + " prod=" + dev.getProductName());
                tryOpen(dev);
            }
        }, 400L);
    }

    private UsbDevice findXInputDevice() {
        if (usb == null) return null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == 0x045e && d.getProductId() == 0x028e) {
                return d;
            }
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface intf = d.getInterface(i);
                if (intf.getInterfaceClass() == 255
                        && intf.getInterfaceSubclass() == 93
                        && intf.getInterfaceProtocol() == 1) {
                    return d;
                }
            }
        }
        return null;
    }

    private void tryOpen(UsbDevice dev) {
        if (usb == null || running || !resumed) return;
        if (usb.hasPermission(dev)) {
            startDevice(dev);
        } else if (permissionRequestedId != dev.getDeviceId()) {
            permissionRequestedId = dev.getDeviceId();
            int flags = Build.VERSION.SDK_INT >= 31
                    ? PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName());
            usb.requestPermission(dev, PendingIntent.getBroadcast(context, 0, intent, flags));
            Log.i(TAG, "Requested USB permission for " + hex(dev.getVendorId()) + ":" + hex(dev.getProductId()));
        }
    }

    private synchronized void startDevice(UsbDevice dev) {
        if (running || usb == null) return;
        UsbInterface target = null;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface intf = dev.getInterface(i);
            if (intf.getInterfaceClass() == 255
                    && intf.getInterfaceSubclass() == 93
                    && intf.getInterfaceProtocol() == 1) {
                target = intf;
                break;
            }
        }
        if (target == null && dev.getInterfaceCount() > 0) {
            target = dev.getInterface(0);
        }
        if (target == null) return;

        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < target.getEndpointCount(); i++) {
            UsbEndpoint ep = target.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN && in == null) {
                    in = ep;
                } else if (ep.getDirection() == UsbConstants.USB_DIR_OUT && out == null) {
                    out = ep;
                }
            }
        }
        if (in == null) return;

        UsbDeviceConnection conn = usb.openDevice(dev);
        if (conn == null) {
            Log.w(TAG, "openDevice failed");
            return;
        }
        boolean claimed = false;
        try {
            claimed = conn.claimInterface(target, true);
        } catch (RuntimeException e) {
            Log.w(TAG, "claimInterface threw: " + e);
        }
        if (!claimed) {
            conn.close();
            Log.w(TAG, "claimInterface(force=true) denied for 045e:028e");
            if (!kernelBlockedNotified) {
                kernelBlockedNotified = true;
                main.post(() -> listener.onUsbStatus(
                        "Controller stalled in kernel (045e:028e LED 2) — hold Home 3s for LED 1", true));
            }
            return;
        }

        // Wake packet for ShanWan / clone 045e:028e + Player 1 solid LED (0x01 0x03 0x06).
        byte[] dummy = new byte[20];
        int ctrl = conn.controlTransfer(0xC1, 0x01, 0x0100, 0x0000, dummy, dummy.length, 150);
        Log.i(TAG, "USB XInput claimed iface " + target.getId() + ", wake ctrl=" + ctrl);
        if (out != null) {
            byte[] led1 = new byte[] {0x01, 0x03, 0x06};
            conn.bulkTransfer(out, led1, led1.length, 100);
            conn.bulkTransfer(out, rumblePacket(0, 0), 8, 50);
        }

        connection = conn;
        claimedInterface = target;
        epOut = out;
        activeDeviceId = dev.getDeviceId();
        lastRumbleKey = 0;
        running = true;
        final UsbEndpoint epIn = in;
        main.post(() -> listener.onUsbStatus("Xbox 360 USB controller connected (LED 1)", true));

        readerThread = new Thread(() -> readLoop(conn, epIn), "AC4UsbXInput");
        readerThread.start();
    }

    private void readLoop(UsbDeviceConnection conn, UsbEndpoint epIn) {
        byte[] buf = new byte[Math.max(32, epIn.getMaxPacketSize())];
        byte[] prev = new byte[14];
        boolean havePrev = false;
        while (running) {
            int n = conn.bulkTransfer(epIn, buf, buf.length, 50);
            if (n <= 0) continue;
            final State st = parseReport(buf, n);
            if (st == null) continue;
            if (havePrev && Arrays.equals(Arrays.copyOf(buf, 14), prev)) continue;
            System.arraycopy(buf, 0, prev, 0, 14);
            havePrev = true;
            main.post(() -> {
                if (running) {
                    listener.onUsbState(st);
                }
            });
        }
    }

    synchronized void setRumbleFloat(float intensity) {
        UsbDeviceConnection conn = connection;
        UsbEndpoint out = epOut;
        if (conn == null || out == null || (!running && intensity > 0f)) {
            lastRumbleKey = 0;
            return;
        }
        int big = 0;
        int small = 0;
        if (intensity > 0.01f) {
            float clamped = Math.min(1.0f, intensity);
            big = Math.max(40, Math.min(255, Math.round(clamped * 255f)));
            small = Math.max(30, Math.min(255, Math.round(clamped * 255f)));
        }
        int key = (big << 8) | small;
        if (key == lastRumbleKey) return;
        lastRumbleKey = key;
        byte[] pkt = rumblePacket(big, small);
        rumbleExecutor.execute(() -> {
            try {
                conn.bulkTransfer(out, pkt, pkt.length, 20);
            } catch (RuntimeException ignored) {
            }
        });
    }

    synchronized void stopStream() {
        if (!running && connection == null) return;
        running = false;
        lastRumbleKey = 0;
        Thread t = readerThread;
        readerThread = null;
        UsbDeviceConnection conn = connection;
        UsbInterface intf = claimedInterface;
        UsbEndpoint out = epOut;
        connection = null;
        claimedInterface = null;
        epOut = null;
        activeDeviceId = -1;
        if (conn != null) {
            if (out != null) {
                try {
                    conn.bulkTransfer(out, rumblePacket(0, 0), 8, 20);
                } catch (RuntimeException ignored) {
                }
            }
            try {
                if (intf != null) conn.releaseInterface(intf);
            } catch (RuntimeException ignored) {
            }
            try {
                conn.close();
            } catch (RuntimeException ignored) {
            }
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(120);
            } catch (InterruptedException ignored) {
            }
        }
        main.post(listener::onUsbDisconnected);
    }

    private static String hex(int v) {
        return String.format(Locale.ROOT, "%04x", v & 0xFFFF);
    }
}
