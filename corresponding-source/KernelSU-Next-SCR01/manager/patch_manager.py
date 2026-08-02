#!/usr/bin/env python3

import re
import sys
from pathlib import Path


EXPECTED_STRING_FILES = 24
VERSION_LINE = '<string name="home_working_version">Version: SCR-01-KSU</string>'
SMALI_NEEDLE = """    :cond_7
    invoke-interface {v8, v0}, Lu0/u0;->setValue(Ljava/lang/Object;)V
"""
SMALI_REPLACEMENT = """    :cond_7
    iget-object v1, p0, Lj8/l;->j:Lo8/k;

    invoke-virtual {v1}, Lo8/k;->b()I

    move-result v1

    const/16 v2, 0x2710

    if-lt v1, v2, :cond_7_profile_state

    iget-object v1, p0, Lj8/l;->p:Ljava/lang/String;

    const-string v2, "com.rifsxd.ksunext"

    invoke-virtual {v2, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v2

    if-nez v2, :cond_7_profile_state

    invoke-static {v1}, Ll4/k;->j(Ljava/lang/String;)V

    :cond_7_profile_state
    invoke-interface {v8, v0}, Lu0/u0;->setValue(Ljava/lang/Object;)V
"""


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_manager.py decoded-apk-directory")
    root = Path(sys.argv[1]).resolve()
    if not root.is_dir():
        raise SystemExit("decoded APK directory does not exist")
    pattern = re.compile(
        r'<string name="home_working_version"(?:>.*?</string>|\s*/>)'
    )
    updates = []
    for path in sorted((root / "res").glob("values*/strings.xml")):
        text = path.read_text(encoding="utf-8")
        updated, count = pattern.subn(VERSION_LINE, text)
        if count > 1:
            raise SystemExit(f"multiple version strings in {path}")
        if count == 1:
            updates.append((path, updated))
    if len(updates) != EXPECTED_STRING_FILES:
        raise SystemExit(
            f"expected {EXPECTED_STRING_FILES} version resources, found {len(updates)}"
        )
    smali = root / "smali" / "j8" / "l.smali"
    smali_text = smali.read_text(encoding="utf-8")
    if smali_text.count(SMALI_NEEDLE) != 1:
        raise SystemExit("manager profile state anchor mismatch")
    metadata = root / "apktool.yml"
    metadata_text = metadata.read_text(encoding="utf-8")
    metadata_updated, count = re.subn(
        r"(?m)^apkFileName: .+$", "apkFileName: base.apk", metadata_text
    )
    if count != 1:
        raise SystemExit("apktool metadata anchor mismatch")
    for path, updated in updates:
        path.write_text(updated, encoding="utf-8")
    smali.write_text(
        smali_text.replace(SMALI_NEEDLE, SMALI_REPLACEMENT), encoding="utf-8"
    )
    metadata.write_text(metadata_updated, encoding="utf-8")
    print(f"patched {len(updates)} resources and one profile transaction path")


if __name__ == "__main__":
    main()
