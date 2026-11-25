package org.nxnative.android;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class UsbXInputTest {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }

    public static void main(String[] args) {
        // 1. parseReport decodes XInput 20-byte packet cleanly (including up-positive Y)
        byte[] pkt = new byte[20];
        pkt[0] = 0x00;
        pkt[1] = 0x14;
        ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(2, (short) (UsbXInputDriver.BTN_A | UsbXInputDriver.BTN_RB | UsbXInputDriver.BTN_START))
                .put(4, (byte) 200) // LT
                .put(5, (byte) 100) // RT (< 128)
                .putShort(6, (short) 16000)   // LX
                .putShort(8, (short) 32767)   // LY (up-positive)
                .putShort(10, (short) -32768) // RX (min short)
                .putShort(12, (short) -12000);// RY

        UsbXInputDriver.State st = UsbXInputDriver.parseReport(pkt, pkt.length);
        check(st != null, "parseReport returned null");
        check((st.buttons & UsbXInputDriver.BTN_A) != 0, "BTN_A missing");
        check(st.lt == 200 && st.rt == 100, "LT/RT mismatch");
        check(st.lx == 16000 && st.ly == 32767, "LS mismatch");
        check(st.rx == -32768 && st.ry == -12000, "RS mismatch");

        // 2. Positional Xbox -> Switch mapping (Bottom=B, Right=A, Left=Y, Top=X)
        long posA = UsbXInputBridge.mapToSwitchButtons(UsbXInputDriver.BTN_A, 0, 0, false);
        check(posA == (1L << Gamepad.B_B), "Xbox A should map to Switch B in positional mode");
        long posB = UsbXInputBridge.mapToSwitchButtons(UsbXInputDriver.BTN_B, 0, 0, false);
        check(posB == (1L << Gamepad.B_A), "Xbox B should map to Switch A in positional mode");
        long posX = UsbXInputBridge.mapToSwitchButtons(UsbXInputDriver.BTN_X, 0, 0, false);
        check(posX == (1L << Gamepad.B_Y), "Xbox X should map to Switch Y in positional mode");
        long posY = UsbXInputBridge.mapToSwitchButtons(UsbXInputDriver.BTN_Y, 0, 0, false);
        check(posY == (1L << Gamepad.B_X), "Xbox Y should map to Switch X in positional mode");

        // 3. Label Xbox -> Switch mapping (A->A, B->B, X->X, Y->Y)
        long lblA = UsbXInputBridge.mapToSwitchButtons(UsbXInputDriver.BTN_A, 0, 0, true);
        check(lblA == (1L << Gamepad.B_A), "Xbox A should map to Switch A in label mode");

        // 4. Triggers + Bumpers + Start/Back + Stick Clicks + Dpad
        long combo = UsbXInputBridge.mapToSwitchButtons(
                UsbXInputDriver.BTN_LB | UsbXInputDriver.BTN_RB | UsbXInputDriver.BTN_START
                        | UsbXInputDriver.BTN_BACK | UsbXInputDriver.BTN_LS | UsbXInputDriver.BTN_RS
                        | UsbXInputDriver.BTN_UP | UsbXInputDriver.BTN_LEFT,
                200, 200, false);
        long expectedCombo = (1L << Gamepad.B_L) | (1L << Gamepad.B_R) | (1L << Gamepad.B_PLUS)
                | (1L << Gamepad.B_MINUS) | (1L << Gamepad.B_LS) | (1L << Gamepad.B_RS)
                | (1L << Gamepad.B_UP) | (1L << Gamepad.B_LEFT)
                | (1L << Gamepad.B_ZL) | (1L << Gamepad.B_ZR);
        check(combo == expectedCombo, "combo buttons/triggers mismatch");

        // 5. Deadzone & clamping (-32768 -> -32767, <4000 -> 0)
        int[] dz = UsbXInputBridge.applyDeadzone(2500, -2500);
        check(dz[0] == 0 && dz[1] == 0, "deadzone should zero small drift");
        int[] clamped = UsbXInputBridge.applyDeadzone(-32768, 32767);
        check(clamped[0] == -32767 && clamped[1] == 32767, "clamp mismatch");

        // 6. Rumble packet
        byte[] rZero = UsbXInputDriver.rumblePacket(0, 0);
        check(rZero.length == 8 && rZero[1] == 0x08 && rZero[3] == 0 && rZero[4] == 0, "zero rumble");
        byte[] rFull = UsbXInputDriver.rumblePacket(255, 128);
        check((rFull[3] & 0xFF) == 255 && (rFull[4] & 0xFF) == 128, "full rumble");

        System.out.println("ALL UsbXInputTest CHECKS PASSED (6/6)");
    }
}
