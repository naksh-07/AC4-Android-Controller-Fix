# Patterns: Peanuts Native (`org.nxnative.android`) APK Patching

- **Fast APK Rebuild**: Use `apktool d --no-assets` so 1.48 GB `assets/game/spv.zip` is skipped during decompile/compile; merge rebuilt `classes.dex` + `AndroidManifest.xml` + compiled `classes2.dex` into original ZIP entries, then `zipalign -p 4` and `apksigner sign`.
- **`nativeTouchPad` Conventions**: `buttons` uses bit indices `B_A=0..B_DOWN=15` (`Gamepad.java`), sticks are `[-32767, 32767]` with UP-positive Y (matching raw XInput without negation). `publish()` in `libdmhost` applies a `4000` radial deadzone only to physical `AInputEvent` sticks, so `UsbXInputBridge` applies `4000` deadzone in Java.
