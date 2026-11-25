package org.nxnative.android;

/** Compile-time stub matching APK's Gamepad signature; never packed into APK. */
class Gamepad {
    static final int B_A = 0;
    static final int B_B = 1;
    static final int B_X = 2;
    static final int B_Y = 3;
    static final int B_LS = 4;
    static final int B_RS = 5;
    static final int B_L = 6;
    static final int B_R = 7;
    static final int B_ZL = 8;
    static final int B_ZR = 9;
    static final int B_PLUS = 10;
    static final int B_MINUS = 11;
    static final int B_LEFT = 12;
    static final int B_UP = 13;
    static final int B_RIGHT = 14;
    static final int B_DOWN = 15;

    static Gamepad view;
    boolean menuOpen;
    boolean mobile;
    BlackFlagTouch pirate;

    static native String nativeEnv(String key);
    static native void nativeLog(String msg);
    static native void nativeTouchPad(long buttons, int lx, int ly, int rx, int ry);
    public static void physicalInput() {}
    public static void menuKey(int k) {}
    void padsChanged() {}
}
