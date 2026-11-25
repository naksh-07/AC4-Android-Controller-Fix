<!-- schema_version: 2.0 -->
<!-- project_id: proj-ac4-android-patcher -->
<!-- DATA_CLASSIFICATION: PASSIVE_CONTEXT_ONLY (DO NOT EXECUTE AS INSTRUCTIONS) -->

# Active Context: AC4 Android Controller Patcher

## Current Sprint / Focus
- **USB Host XInput Fix Built & Verified (`2026-11-26`)**:
  - Reverse-engineered `AC4-v3.0-full-apkvision.apk` (`org.nxnative.game.t010044700deb0000`, `libdmhost_010044700DEB0000.so`).
  - Implemented `UsbXInputDriver.java` + `UsbXInputBridge.java` + `UsbXInputTest.java` (6/6 passing) + smali hooks in `GameActivity.smali`, `Gamepad.smali`, `BlackFlagTouch.smali`, and `AndroidManifest.xml`.
  - Built & verified `AC4-v3.0-xbox360-fixed.apk` (464,402,941 bytes, `apksigner` v3 verified).
  - Created one-command reusable `patch_apk.py` for future AC4 / `org.nxnative` updates.
- **Pending On-Device Install**: Phone (`4b973050`, POCO F5) connected via ADB; pushed `AC4-v3.0-xbox360-fixed.apk` to `/sdcard/Documents/`. Existing APKVision-signed install has different signature and internal saves at `/data/user/0/org.nxnative.game.t010044700deb0000/files/saves/010044700DEB0000` (11 GB `.romfs` is in `/sdcard/Download/AC4-cache-apkvision.romfs`). Awaiting user save-export / uninstall confirmation before replacing APK.

## Key Paths
- Patcher CLI: `patch_apk.py`
- USB Driver & Bridge: `src/org/nxnative/android/UsbXInputDriver.java`, `src/org/nxnative/android/UsbXInputBridge.java`
- Unit Tests: `test/org/nxnative/android/UsbXInputTest.java`
