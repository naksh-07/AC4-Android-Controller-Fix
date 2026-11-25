#!/usr/bin/env python3
"""
One-command Xbox 360 / ShanWan (045e:028e) USB Host XInput patcher for
AC4 Black Flag Android (Peanuts Native / org.nxnative.android).

Usage:
    python patch_apk.py <input.apk> [--out <output.apk>] [--install]
"""

import argparse
import os
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def find_sdk_paths():
    local_app = Path(os.environ.get("LOCALAPPDATA", ""))
    sdk_root = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or (local_app / "Android" / "Sdk"))

    build_tools_dirs = sorted((sdk_root / "build-tools").glob("*"), reverse=True)
    if not build_tools_dirs:
        raise FileNotFoundError(f"No Android build-tools found under {sdk_root / 'build-tools'}")
    bt = build_tools_dirs[0]

    platforms = sorted((sdk_root / "platforms").glob("android-*"), key=lambda p: int(re.sub(r"\D", "", p.name) or 0), reverse=True)
    if not platforms:
        raise FileNotFoundError(f"No Android platforms found under {sdk_root / 'platforms'}")
    android_jar = platforms[0] / "android.jar"

    apktool_jars = sorted((Path.home() / ".local" / "bin").glob("apktool*.jar"))
    if not apktool_jars:
        raise FileNotFoundError("apktool*.jar not found in ~/.local/bin")

    java_home = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"))
    javac = java_home / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    java = java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not javac.is_file():
        javac = Path(shutil.which("javac") or "javac")
    if not java.is_file():
        java = Path(shutil.which("java") or "java")

    return {
        "bt": bt,
        "d8": bt / ("d8.bat" if os.name == "nt" else "d8"),
        "zipalign": bt / ("zipalign.exe" if os.name == "nt" else "zipalign"),
        "apksigner": bt / ("apksigner.bat" if os.name == "nt" else "apksigner"),
        "android_jar": android_jar,
        "apktool_jar": apktool_jars[-1],
        "javac": javac,
        "java": java,
        "keystore": Path.home() / ".android" / "debug.keystore",
    }


def compile_driver_dex(tools: dict, work_dir: Path) -> Path:
    stubs_out = work_dir / "classes_stubs"
    app_out = work_dir / "classes_app"
    test_out = work_dir / "classes_test"
    dex_out = work_dir / "dex"
    for d in (stubs_out, app_out, test_out, dex_out):
        d.mkdir(parents=True, exist_ok=True)

    android_jar = str(tools["android_jar"])
    javac = str(tools["javac"])
    java = str(tools["java"])
    sep = ";" if os.name == "nt" else ":"

    subprocess.run(
        [
            javac, "--release", "11", "-d", str(stubs_out),
            str(ROOT / "stubs" / "org" / "nxnative" / "android" / "BlackFlagTouch.java"),
            str(ROOT / "stubs" / "org" / "nxnative" / "android" / "Gamepad.java"),
        ],
        check=True,
    )
    subprocess.run(
        [
            javac, "--release", "11", "-cp", f"{android_jar}{sep}{stubs_out}", "-d", str(app_out),
            str(ROOT / "src" / "org" / "nxnative" / "android" / "UsbXInputDriver.java"),
            str(ROOT / "src" / "org" / "nxnative" / "android" / "UsbXInputBridge.java"),
        ],
        check=True,
    )
    subprocess.run(
        [
            javac, "--release", "11", "-cp", f"{android_jar}{sep}{stubs_out}{sep}{app_out}", "-d", str(test_out),
            str(ROOT / "test" / "org" / "nxnative" / "android" / "UsbXInputTest.java"),
        ],
        check=True,
    )
    subprocess.run(
        [java, "-cp", f"{test_out}{sep}{app_out}{sep}{stubs_out}{sep}{android_jar}", "org.nxnative.android.UsbXInputTest"],
        check=True,
    )

    class_files = [str(p) for p in app_out.rglob("*.class")]
    d8_cmd = [str(tools["d8"]), "--min-api", "29", "--lib", android_jar, "--output", str(dex_out), *class_files]
    if os.name == "nt":
        subprocess.run(["cmd.exe", "/c", *d8_cmd], check=True)
    else:
        subprocess.run(d8_cmd, check=True)
    return dex_out / "classes.dex"


def patch_manifest(manifest_path: Path):
    text = manifest_path.read_text(encoding="utf-8")
    if "android.hardware.usb.host" in text:
        return
    needle = '<uses-feature android:glEsVersion="0x00030000"/>'
    replacement = needle + '\n    <uses-feature android:name="android.hardware.usb.host" android:required="false"/>'
    if needle in text:
        text = text.replace(needle, replacement, 1)
    else:
        text = text.replace(
            "<application",
            '    <uses-feature android:name="android.hardware.usb.host" android:required="false"/>\n    <application',
            1,
        )
    manifest_path.write_text(text, encoding="utf-8")


def patch_game_activity(smali_path: Path):
    text = smali_path.read_text(encoding="utf-8")
    if "UsbXInputBridge;->resume" in text:
        return

    if ".method protected onResume()V" in text or ".method public onResume()V" in text:
        raise RuntimeError("GameActivity already defines onResume(); please inspect manually.")
    if ".method protected onPause()V" in text or ".method public onPause()V" in text:
        raise RuntimeError("GameActivity already defines onPause(); please inspect manually.")

    hooks = """

.method protected onPause()V
    .locals 0

    invoke-static {}, Lorg/nxnative/android/UsbXInputBridge;->pause()V

    invoke-super {p0}, Landroid/app/NativeActivity;->onPause()V

    return-void
.end method

.method protected onResume()V
    .locals 0

    invoke-super {p0}, Landroid/app/NativeActivity;->onResume()V

    invoke-static {p0}, Lorg/nxnative/android/UsbXInputBridge;->resume(Landroid/app/Activity;)V

    return-void
.end method
"""
    smali_path.write_text(text.rstrip() + hooks + "\n", encoding="utf-8")


def patch_gamepad(smali_path: Path):
    text = smali_path.read_text(encoding="utf-8")
    if "UsbXInputBridge" in text:
        return

    # 1. anyPad() -> return true when UsbXInputBridge.isActive()
    anypad_pat = re.compile(
        r"(\.method static anyPad\(\)Z\s+\.locals \d+\s+(?:\.line \d+\s+)?)invoke-static \{\}, Landroid/view/InputDevice;->getDeviceIds\(\)\[I"
    )
    text, n1 = anypad_pat.subn(
        r"""\1invoke-static {}, Lorg/nxnative/android/UsbXInputBridge;->isActive()Z

    move-result v0

    if-eqz v0, :cond_usb_inactive

    const/4 v0, 0x1

    return v0

    :cond_usb_inactive
    invoke-static {}, Landroid/view/InputDevice;->getDeviceIds()[I""",
        text,
        count=1,
    )
    if n1 != 1:
        raise RuntimeError("Failed to anchor Gamepad.anyPad()")

    # 2. padsChanged() -> warnIfShanWanHid()
    pads_pat = re.compile(
        r"(invoke-static \{\}, Lorg/nxnative/android/Gamepad;->anyPad\(\)Z\s+move-result v0)"
    )
    text, n2 = pads_pat.subn(
        r"""\1

    invoke-static {}, Lorg/nxnative/android/UsbXInputBridge;->warnIfShanWanHid()V""",
        text,
        count=1,
    )
    if n2 != 1:
        raise RuntimeError("Failed to anchor Gamepad.padsChanged()")

    # 3. rumble(F)V -> forward to UsbXInputBridge.setRumble(F) when active
    rumble_pat = re.compile(
        r"(\.method public static rumble\(F\)V\s+\.locals \d+\s+(?:\.line \d+\s+)?)"
    )
    text, n3 = rumble_pat.subn(
        r"""\1invoke-static {}, Lorg/nxnative/android/UsbXInputBridge;->isActive()Z

    move-result v0

    if-eqz v0, :cond_usb_no_rumble

    invoke-static {p0}, Lorg/nxnative/android/UsbXInputBridge;->setRumble(F)V

    return-void

    :cond_usb_no_rumble
    """,
        text,
        count=1,
    )
    if n3 != 1:
        raise RuntimeError("Failed to anchor Gamepad.rumble(F)")

    # 4. recompute() -> guard nativeTouchPad(JIIII)V so touch reset doesn't zero USB controller
    tp_pat = re.compile(
        r"(invoke-static/range \{v23 \.\. v28\}, Lorg/nxnative/android/Gamepad;->nativeTouchPad\(JIIII\)V)"
    )
    text, n4 = tp_pat.subn(
        r"""invoke-static {}, Lorg/nxnative/android/UsbXInputBridge;->isActive()Z

    move-result v1

    if-nez v1, :cond_skip_usb_touchpad

    \1

    :cond_skip_usb_touchpad""",
        text,
        count=1,
    )
    if n4 != 1:
        raise RuntimeError("Failed to anchor Gamepad.recompute() nativeTouchPad call")

    smali_path.write_text(text, encoding="utf-8")


def patch_black_flag_touch(smali_path: Path):
    if not smali_path.is_file():
        return
    text = smali_path.read_text(encoding="utf-8")
    if "UsbXInputBridge" in text:
        return

    tp_pat = re.compile(
        r"(invoke-static/range \{v5 \.\. v10\}, Lorg/nxnative/android/Gamepad;->nativeTouchPad\(JIIII\)V)"
    )
    text, n = tp_pat.subn(
        r"""invoke-static {}, Lorg/nxnative/android/UsbXInputBridge;->isActive()Z

    move-result v0

    if-nez v0, :cond_skip_usb_touchpad

    \1

    :cond_skip_usb_touchpad""",
        text,
        count=1,
    )
    if n != 1:
        raise RuntimeError("Failed to anchor BlackFlagTouch.publish() nativeTouchPad call")

    smali_path.write_text(text, encoding="utf-8")


def patch_apk(input_apk: Path, output_apk: Path):
    tools = find_sdk_paths()
    if not tools["keystore"].is_file():
        raise FileNotFoundError(f"Debug keystore not found at {tools['keystore']}")

    with tempfile.TemporaryDirectory(prefix="ac4_patch_") as tmp:
        tmp_dir = Path(tmp)
        apktool_dir = tmp_dir / "apktool_src"
        stub_apk = tmp_dir / "patched_stub.apk"
        unaligned_apk = tmp_dir / "unaligned.apk"
        aligned_apk = tmp_dir / "aligned.apk"

        print("[1/5] Compiling UsbXInputDriver + UsbXInputBridge & running unit tests...")
        driver_dex_path = compile_driver_dex(tools, tmp_dir)
        driver_dex_bytes = driver_dex_path.read_bytes()

        print(f"[2/5] Decoding DEX & manifest from {input_apk.name} (skipping large assets)...")
        subprocess.run(
            [str(tools["java"]), "-jar", str(tools["apktool_jar"]), "d", "-f", "--no-assets", "-o", str(apktool_dir), str(input_apk)],
            check=True,
        )

        print("[3/5] Applying smali & manifest hooks...")
        patch_manifest(apktool_dir / "AndroidManifest.xml")
        nx_smali = apktool_dir / "smali" / "org" / "nxnative" / "android"
        patch_game_activity(nx_smali / "GameActivity.smali")
        patch_gamepad(nx_smali / "Gamepad.smali")
        patch_black_flag_touch(nx_smali / "BlackFlagTouch.smali")

        print("[4/5] Rebuilding patched DEX & merging APK...")
        subprocess.run(
            [str(tools["java"]), "-jar", str(tools["apktool_jar"]), "b", str(apktool_dir), "-o", str(stub_apk)],
            check=True,
        )
        with zipfile.ZipFile(stub_apk, "r") as stub_z:
            new_manifest = stub_z.read("AndroidManifest.xml")
            new_classes1 = stub_z.read("classes.dex")

        with zipfile.ZipFile(input_apk, "r") as src_z:
            existing_names = {i.filename for i in src_z.infolist()}
            dex_idx = 2
            while f"classes{dex_idx}.dex" in existing_names:
                dex_idx += 1
            extra_dex_name = f"classes{dex_idx}.dex"

            with zipfile.ZipFile(unaligned_apk, "w", allowZip64=True) as dst_z:
                for info in src_z.infolist():
                    name = info.filename
                    if name.startswith("META-INF/"):
                        continue
                    if name == "AndroidManifest.xml":
                        dst_z.writestr("AndroidManifest.xml", new_manifest, compress_type=zipfile.ZIP_DEFLATED)
                        continue
                    if name == "classes.dex":
                        dst_z.writestr("classes.dex", new_classes1, compress_type=zipfile.ZIP_DEFLATED)
                        dst_z.writestr(extra_dex_name, driver_dex_bytes, compress_type=zipfile.ZIP_DEFLATED)
                        continue
                    data = src_z.read(name)
                    new_info = zipfile.ZipInfo(name, date_time=info.date_time)
                    new_info.compress_type = info.compress_type
                    new_info.external_attr = info.external_attr
                    if info.compress_type == zipfile.ZIP_DEFLATED and len(data) > 50_000_000:
                        dst_z.writestr(new_info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=1)
                    else:
                        dst_z.writestr(new_info, data, compress_type=info.compress_type)

        print(f"[5/5] Running zipalign & apksigner ({extra_dex_name})...")
        subprocess.run([str(tools["zipalign"]), "-f", "-p", "4", str(unaligned_apk), str(aligned_apk)], check=True)
        if output_apk.exists():
            output_apk.unlink()
        sign_cmd = [
            str(tools["apksigner"]), "sign",
            "--ks", str(tools["keystore"]),
            "--ks-pass", "pass:android",
            "--key-pass", "pass:android",
            "--ks-key-alias", "androiddebugkey",
            "--out", str(output_apk),
            str(aligned_apk),
        ]
        verify_cmd = [str(tools["apksigner"]), "verify", "--verbose", str(output_apk)]
        if os.name == "nt":
            subprocess.run(["cmd.exe", "/c", *sign_cmd], check=True)
            subprocess.run(["cmd.exe", "/c", *verify_cmd], check=True)
        else:
            subprocess.run(sign_cmd, check=True)
            subprocess.run(verify_cmd, check=True)

    print(f"SUCCESS: {output_apk} ({output_apk.stat().st_size:,} bytes)")


def main():
    parser = argparse.ArgumentParser(description="Patch an AC4 / Peanuts Native APK for Xbox 360 / ShanWan USB controllers.")
    parser.add_argument("input_apk", type=Path, help="Path to original APK")
    parser.add_argument("--out", type=Path, default=None, help="Output signed APK path")
    parser.add_argument("--install", action="store_true", help="Push/install via adb after building")
    args = parser.parse_args()

    inp = args.input_apk.resolve()
    if not inp.is_file():
        raise FileNotFoundError(f"Input APK not found: {inp}")
    out = args.out.resolve() if args.out else inp.with_name(inp.stem + "-xbox360-fixed.apk")
    patch_apk(inp, out)

    if args.install:
        subprocess.run(["adb", "install", "-r", str(out)], check=True)


if __name__ == "__main__":
    main()
