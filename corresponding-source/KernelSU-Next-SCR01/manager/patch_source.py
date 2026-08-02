#!/usr/bin/env python3

import re
import sys
from pathlib import Path


EXPECTED_STRING_FILES = 24
VERSION_LINE = '<string name="home_working_version">Version: SCR-01-KSU</string>'
KOTLIN_NEEDLE = """                    } else {
                        profile = it
                        viewModel.updateAppProfile(packageName, it)
                    }
"""
KOTLIN_REPLACEMENT = """                    } else {
                        if (appInfo.uid >= 10000 && packageName != "com.rifsxd.ksunext") {
                            forceStopApp(packageName)
                        }
                        profile = it
                        viewModel.updateAppProfile(packageName, it)
                    }
"""


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_source.py KernelSU-Next-3.3.0-directory")
    root = Path(sys.argv[1]).resolve()
    manager = root / "manager" / "app" / "src" / "main"
    if not manager.is_dir():
        raise SystemExit("KernelSU-Next v3.3.0 Manager source directory is missing")
    pattern = re.compile(
        r'<string name="home_working_version"(?:>.*?</string>|\s*/>)'
    )
    updates = []
    for path in sorted((manager / "res").glob("values*/strings.xml")):
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
    kotlin = manager / "java" / "com" / "rifsxd" / "ksunext" / "ui" / "screen" / "AppProfile.kt"
    kotlin_text = kotlin.read_text(encoding="utf-8")
    if kotlin_text.count(KOTLIN_NEEDLE) != 1:
        raise SystemExit("AppProfile source anchor mismatch")
    for path, updated in updates:
        path.write_text(updated, encoding="utf-8")
    kotlin.write_text(
        kotlin_text.replace(KOTLIN_NEEDLE, KOTLIN_REPLACEMENT), encoding="utf-8"
    )
    print(f"patched {len(updates)} resources and AppProfile.kt")


if __name__ == "__main__":
    main()
