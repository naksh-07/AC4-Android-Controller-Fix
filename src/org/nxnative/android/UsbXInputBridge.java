package org.nxnative.android;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.widget.Toast;

/**
 * Bridges UsbXInputDriver directly into libdmhost's Gamepad.nativeTouchPad / menuKey / rumble
 * so wired Xbox 360 / ShanWan controllers work out of the box with proper Nintendo Switch mapping.
 */
public final class UsbXInputBridge {
    private static final int STICK_DEADZONE = 4000;
    private static final int MENU_STICK_THRESH = 16384;
    private static final int TRIGGER_THRESH = 128;

    private static UsbXInputDriver driver;
    private static Context appCtx;
    private static Handler mainHandler;
    private static int lastMenuMask;
    private static int lastXButtons;
    private static Boolean cachedAbxyByLabel;

    private UsbXInputBridge() {}

    public static synchronized void resume(Activity activity) {
        if (activity == null) return;
        appCtx = activity.getApplicationContext();
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        cachedAbxyByLabel = null;
        if (driver == null) {
            driver = new UsbXInputDriver(appCtx, mainHandler, listener);
        }
        driver.resume();
    }

    public static synchronized void pause() {
        lastMenuMask = 0;
        lastXButtons = 0;
        if (driver != null) {
            driver.pause();
        }
    }

    public static boolean isActive() {
        UsbXInputDriver d = driver;
        return d != null && d.isActive();
    }

    public static void setRumble(float intensity) {
        UsbXInputDriver d = driver;
        if (d != null) {
            d.setRumbleFloat(intensity);
        }
    }

    /** Called from Gamepad.padsChanged() to hint if user plugged ShanWan in HID LED #1 mode. */
    public static void warnIfShanWanHid() {
        Context c = appCtx;
        if (c == null || isActive()) return;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev != null && dev.getVendorId() == 0x2563 && dev.getProductId() == 0x0575) {
                Gamepad.nativeLog("gamepad: ShanWan 2563:0575 LED 1 HID detected; hold Home 3s for Xbox 045e:028e mode");
                if (mainHandler != null) {
                    mainHandler.post(() -> Toast.makeText(
                            c,
                            "Hold Home 3s to switch controller to Xbox XInput mode (LED 1 fix active)",
                            Toast.LENGTH_LONG).show());
                }
                return;
            }
        }
    }

    /** Maps Xbox 360 XInput buttons + triggers to Switch Npad bitmask expected by Gamepad.nativeTouchPad. */
    static long mapToSwitchButtons(int xb, int lt, int rt, boolean abxyByLabel) {
        long nx = 0L;
        if (abxyByLabel) {
            if ((xb & UsbXInputDriver.BTN_A) != 0) nx |= 1L << Gamepad.B_A;
            if ((xb & UsbXInputDriver.BTN_B) != 0) nx |= 1L << Gamepad.B_B;
            if ((xb & UsbXInputDriver.BTN_X) != 0) nx |= 1L << Gamepad.B_X;
            if ((xb & UsbXInputDriver.BTN_Y) != 0) nx |= 1L << Gamepad.B_Y;
        } else {
            // Positional Xbox -> Switch mapping (Bottom=B, Right=A, Left=Y, Top=X)
            if ((xb & UsbXInputDriver.BTN_A) != 0) nx |= 1L << Gamepad.B_B;
            if ((xb & UsbXInputDriver.BTN_B) != 0) nx |= 1L << Gamepad.B_A;
            if ((xb & UsbXInputDriver.BTN_X) != 0) nx |= 1L << Gamepad.B_Y;
            if ((xb & UsbXInputDriver.BTN_Y) != 0) nx |= 1L << Gamepad.B_X;
        }
        if ((xb & UsbXInputDriver.BTN_LS) != 0) nx |= 1L << Gamepad.B_LS;
        if ((xb & UsbXInputDriver.BTN_RS) != 0) nx |= 1L << Gamepad.B_RS;
        if ((xb & UsbXInputDriver.BTN_LB) != 0) nx |= 1L << Gamepad.B_L;
        if ((xb & UsbXInputDriver.BTN_RB) != 0) nx |= 1L << Gamepad.B_R;
        if (lt >= TRIGGER_THRESH) nx |= 1L << Gamepad.B_ZL;
        if (rt >= TRIGGER_THRESH) nx |= 1L << Gamepad.B_ZR;
        if ((xb & UsbXInputDriver.BTN_START) != 0) nx |= 1L << Gamepad.B_PLUS;
        if ((xb & UsbXInputDriver.BTN_BACK) != 0) nx |= 1L << Gamepad.B_MINUS;
        if ((xb & UsbXInputDriver.BTN_LEFT) != 0) nx |= 1L << Gamepad.B_LEFT;
        if ((xb & UsbXInputDriver.BTN_UP) != 0) nx |= 1L << Gamepad.B_UP;
        if ((xb & UsbXInputDriver.BTN_RIGHT) != 0) nx |= 1L << Gamepad.B_RIGHT;
        if ((xb & UsbXInputDriver.BTN_DOWN) != 0) nx |= 1L << Gamepad.B_DOWN;
        return nx;
    }

    static int[] applyDeadzone(int rawX, int rawY) {
        long sq = (long) rawX * rawX + (long) rawY * rawY;
        if (sq < (long) STICK_DEADZONE * STICK_DEADZONE) {
            return new int[] {0, 0};
        }
        int cx = Math.max(-32767, Math.min(32767, rawX));
        int cy = Math.max(-32767, Math.min(32767, rawY));
        return new int[] {cx, cy};
    }

    private static boolean isAbxyByLabel() {
        Boolean cached = cachedAbxyByLabel;
        if (cached != null) return cached;
        boolean byLabel = false;
        try {
            byLabel = "label".equals(Gamepad.nativeEnv("DM_PAD_ABXY"));
        } catch (Throwable ignored) {
        }
        cachedAbxyByLabel = byLabel;
        return byLabel;
    }

    private static final UsbXInputDriver.Listener listener = new UsbXInputDriver.Listener() {
        @Override
        public void onUsbState(UsbXInputDriver.State st) {
            int guideEdge = (st.buttons & ~lastXButtons) & UsbXInputDriver.BTN_GUIDE;
            lastXButtons = st.buttons;
            if (guideEdge != 0) {
                cachedAbxyByLabel = null;
                Gamepad.menuKey(0);
            }

            Gamepad v = Gamepad.view;
            if (v != null && v.menuOpen) {
                Gamepad.nativeTouchPad(0L, 0, 0, 0, 0);
                int mask = 0;
                if ((st.buttons & UsbXInputDriver.BTN_UP) != 0 || st.ly >= MENU_STICK_THRESH) mask |= 1 << 1;
                if ((st.buttons & UsbXInputDriver.BTN_DOWN) != 0 || st.ly <= -MENU_STICK_THRESH) mask |= 1 << 2;
                if ((st.buttons & UsbXInputDriver.BTN_LEFT) != 0 || st.lx <= -MENU_STICK_THRESH) mask |= 1 << 3;
                if ((st.buttons & UsbXInputDriver.BTN_RIGHT) != 0 || st.lx >= MENU_STICK_THRESH) mask |= 1 << 4;
                if ((st.buttons & UsbXInputDriver.BTN_A) != 0) mask |= 1 << 5;
                if ((st.buttons & UsbXInputDriver.BTN_B) != 0) mask |= 1 << 6;
                int rising = mask & ~lastMenuMask;
                lastMenuMask = mask;
                for (int k = 1; k <= 6; k++) {
                    if ((rising & (1 << k)) != 0) {
                        Gamepad.menuKey(k);
                    }
                }
                return;
            }
            lastMenuMask = 0;

            int[] ls = applyDeadzone(st.lx, st.ly);
            int[] rs = applyDeadzone(st.rx, st.ry);
            long nxButtons = mapToSwitchButtons(st.buttons, st.lt, st.rt, isAbxyByLabel());
            Gamepad.nativeTouchPad(nxButtons, ls[0], ls[1], rs[0], rs[1]);

            if ((nxButtons != 0L || ls[0] != 0 || ls[1] != 0 || rs[0] != 0 || rs[1] != 0)
                    && v != null && v.mobile && v.pirate != null && !v.pirate.hidden) {
                Gamepad.physicalInput();
            }
        }

        @Override
        public void onUsbStatus(String message, boolean isUserHint) {
            try {
                Gamepad.nativeLog("gamepad: " + message);
            } catch (Throwable ignored) {
            }
            if (isUserHint && appCtx != null) {
                Toast.makeText(appCtx, message, Toast.LENGTH_SHORT).show();
            }
            if (!isUserHint || isActive()) {
                Gamepad v = Gamepad.view;
                if (v != null) {
                    v.padsChanged();
                }
                Gamepad.physicalInput();
            }
        }

        @Override
        public void onUsbDisconnected() {
            lastMenuMask = 0;
            lastXButtons = 0;
            try {
                Gamepad.nativeTouchPad(0L, 0, 0, 0, 0);
            } catch (Throwable ignored) {
            }
            Gamepad v = Gamepad.view;
            if (v != null) {
                v.padsChanged();
            }
        }
    };
}
