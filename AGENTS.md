# Project-Specific Agent Protocol: AC4 Android Controller Patcher

Inherits from global `C:\Users\Suraj\.gemini\config\AGENTS.md`.

- **Canonical Project ID**: `proj-ac4-android-patcher`
- **Root Directory**: `C:\Users\Suraj\Documents\Antigravity\AC4`
- **Target Package**: `org.nxnative.game.t010044700deb0000` (Assassin's Creed IV: Black Flag, Peanuts Native / `libdmhost_010044700DEB0000.so`)

## Architectural Invariants
1. **No Binary Bloat in Git**: Never commit `.apk` binaries (`>400 MB`), `apktool_src/`, `extracted/`, or `build/` intermediates. Only commit Java sources, stubs, unit tests, `patch_apk.py`, docs, and `.agents/memory/`.
2. **Signature & Save Preservation**: Patched APKs are signed with `~/.android/debug.keystore` (v3 scheme). Never uninstall the target package on a connected device without verifying save backup (`LauncherSaves` export / `/data/user/0/.../files/saves/`) or user confirmation.
3. **USB Host XInput Bridge**: `UsbXInputDriver` targets `045e:028e` (USB interface `255/93/1`), sends ShanWan wake (`0xC1/0x01/0x0100`) + LED #1 (`0x01 0x03 0x06`), and `UsbXInputBridge` injects directly into `Gamepad.nativeTouchPad(buttons, lx, ly, rx, ry)` while guarding `Gamepad.recompute()` and `BlackFlagTouch.publish()` against zeroing active USB input.
