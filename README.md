# AC4 Black Flag Android — Xbox 360 / ShanWan USB Controller Patcher

One-command APK patcher for **Assassin's Creed IV: Black Flag Android** (`org.nxnative.game.t010044700deb0000` / Peanuts Native `dmhost`) to fix wired Xbox 360 & ShanWan clone controllers (`045e:028e` / `2563:0575`) on Android 15 / HyperOS 2.

## Problem Fixed
1. **Stalled Kernel `xpad` Handshake (`045e:028e` LED #2)**: On Android 5.10 kernels (without root), wired Xbox 360 / ShanWan clones stall on LED #2 and send zero input events to `NativeActivity`.
2. **Scrambled `Generic.kl` Mapping (`2563:0575` LED #1)**: Switching manually to HID LED #1 falls back to Android `Generic.kl` and scrambles A/B/X/Y/L1/R1/L2/R2/Select/Start.
3. **Xbox-to-Switch Mapping & Touch Overlay**: Directly maps Xbox 360 inputs into `Gamepad.nativeTouchPad(...)`, hides `BlackFlagTouch`, enables USB motor rumble, and routes Xbox `Guide` + D-pad/stick to the overlay menu.

## Button Mapping (Default Positional / `DM_PAD_ABXY`)
| Xbox 360 Input | Switch Output (`Gamepad.nativeTouchPad`) |
| :--- | :--- |
| `A` / `B` / `X` / `Y` | `B` / `A` / `Y` / `X` (or `A/B/X/Y` when `DM_PAD_ABXY=label`) |
| `LB` / `RB` | `L` / `R` |
| `LT` / `RT` | `ZL` / `ZR` |
| `START` / `BACK` | `PLUS` (+) / `MINUS` (-) |
| `LS` / `RS` | `L3` / `R3` |
| `D-Pad` / `L-Stick` / `R-Stick` | Switch D-Pad / Left Stick / Right Stick (`4000` deadzone, up-positive Y) |
| `Guide` (Home) | Toggle Emulator Overlay Menu (`Gamepad.menuKey(0)`) |

## Quick Usage (For Future APK Updates)
Whenever a new AC4 / `org.nxnative` APK update drops, run one command from this directory:

```powershell
python patch_apk.py "C:\path\to\AC4-new-update.apk"
```

This automatically:
1. Compiles `UsbXInputDriver.java` & `UsbXInputBridge.java` and runs `UsbXInputTest` (6/6 checks).
2. Unpacks only DEX + manifest via `apktool d --no-assets` (skips the 1.48 GB `spv.zip`).
3. Patches `AndroidManifest.xml`, `GameActivity.smali`, `Gamepad.smali`, and `BlackFlagTouch.smali`.
4. Repacks `classes.dex` + `classes2.dex`, runs `zipalign -p 4`, signs with `~/.android/debug.keystore`, and verifies with `apksigner`.

### Prerequisites
- JDK 17 (`javac`, `java`)
- Android SDK `platforms/android-35/android.jar` & `build-tools/35.0.0` (`d8`, `zipalign`, `apksigner`)
- `apktool_2.11.0.jar` in `~/.local/bin/`
- `~/.android/debug.keystore`

### Install & Save Backup Note
Because the patched APK is signed with your local `debug.keystore` instead of the original distributor key, installing over an existing build requires uninstalling first:
1. Open the old app -> **Launcher -> Saves -> Export** to back up your `AC3SAVE` progress.
2. Note: the 11 GB `.romfs` file (`AC4-cache-apkvision.romfs` in `/sdcard/Download/`) is untouched by uninstall, so you only re-pick it once in the new APK and import your save.
