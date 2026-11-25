# Architectural Decisions: AC4 Android Controller Patcher

## ADR-001: Bridge USB Host XInput into `Gamepad.nativeTouchPad` instead of patching `libdmhost.so`
- **Context**: Wired Xbox 360 / ShanWan clones (`045e:028e`) stall on LED #2 in Android 5.10 kernel `xpad`, and LED #1 (`2563:0575`) scrambles `Generic.kl` keycodes before `libdmhost`'s `on_input` receives `AKeyEvent`s.
- **Decision**: Claim USB interface `(255, 93, 1)` directly in Java via `UsbXInputDriver` + `UsbXInputBridge` (compiled to `classes2.dex`) and inject decoded state into `Gamepad.nativeTouchPad(buttons, lx, ly, rx, ry)`, guarding `Gamepad.recompute()` and `BlackFlagTouch.publish()` when active.
- **Consequences**: Zero native binary patching required across future `libdmhost` updates; works across APK updates with small smali hooks (`patch_apk.py`), supports rumble (`Gamepad.rumble`), menu navigation (`Gamepad.menuKey`), `DM_PAD_ABXY` positional/label switching, and automatic touch-overlay hiding.
