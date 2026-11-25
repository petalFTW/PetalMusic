#!/usr/bin/env python3
"""Replace the stock AAR's arm64 runtime with the locally built QNN runtime.

Preserves CPU runtimes for the other ABIs. QNN and Hexagon v81 runtime files are
packaged together to avoid mixing SDK versions with the phone's vendor libraries.
"""
import argparse
from pathlib import Path
import zipfile


def package(stock: Path, build: Path, sdk: Path, output: Path) -> None:
    native = "jni/arm64-v8a/"
    replacements = {
        native + name: build / name
        for name in ("libonnxruntime.so", "libonnxruntime4j_jni.so")
    }
    for name in ("libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnHtpV81Stub.so", "libQnnSystem.so"):
        replacements[native + name] = sdk / "lib/aarch64-android" / name
    for name in ("libQnnHtpV81Skel.so", "libQnnHtpV81.so", "libQnnHexagonSkel_dspApp.so"):
        replacements[native + name] = sdk / "lib/hexagon-v81/unsigned" / name
    replacements["META-INF/licenses/Qualcomm-LICENSE.pdf"] = sdk / "LICENSE.pdf"
    replacements["META-INF/licenses/Qualcomm-QNN-NOTICE.txt"] = sdk / "QNN_NOTICE.txt"
    for path in replacements.values():
        if not path.is_file():
            raise FileNotFoundError(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(stock) as source, zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as dest:
        for entry in source.infolist():
            if entry.filename not in replacements:
                dest.writestr(entry, source.read(entry.filename))
        for name, path in replacements.items():
            dest.write(path, name)
            print(f"{name}: {path.stat().st_size:,} bytes")
    print(f"Wrote {output} ({output.stat().st_size:,} bytes)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock_aar", type=Path)
    parser.add_argument("native_build", type=Path)
    parser.add_argument("sdk", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    package(args.stock_aar, args.native_build, args.sdk, args.output)
