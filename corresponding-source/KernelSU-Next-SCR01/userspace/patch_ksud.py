#!/usr/bin/env python3

import hashlib
import sys
import zipfile
from pathlib import Path


EXPECTED_INPUT = "f4359553a597955956b97e86277e08bc426f644de0dba5ff13da562fba29c55d"
EXPECTED_OUTPUT = "637676421190aeec504093707ad675a45faaf11bd4b53129d52e150490902cca"
APK_PATH = "lib/arm64-v8a/libksud.so"
ORIGINAL = b"#/com.rifsxd.ksunext.ui.MainActivity\x00"
REPLACEMENT = b"#/com.rifsxd.ksunext.ui.MainActivit_\x00"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: patch_ksud.py manager.apk-or-libksud.so output-libksud.so")
    source = Path(sys.argv[1])
    output = Path(sys.argv[2])
    if output.exists():
        raise SystemExit("output libksud.so already exists")
    if zipfile.is_zipfile(source):
        with zipfile.ZipFile(source) as archive:
            data = archive.read(APK_PATH)
    else:
        data = source.read_bytes()
    if digest(data) != EXPECTED_INPUT:
        raise SystemExit("unexpected upstream libksud.so hash")
    if data.count(ORIGINAL) != 1:
        raise SystemExit("manager launch component anchor mismatch")
    patched = data.replace(ORIGINAL, REPLACEMENT)
    if digest(patched) != EXPECTED_OUTPUT:
        raise SystemExit("patched libksud.so hash mismatch")
    with output.open("xb") as destination:
        destination.write(patched)
    print(EXPECTED_OUTPUT)


if __name__ == "__main__":
    main()
