import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VERIFY = ROOT / "tools/verify_corresponding_source.py"
PATCH_KSUD = ROOT / "corresponding-source/KernelSU-Next-SCR01/userspace/patch_ksud.py"
PATCH_MANAGER = ROOT / "corresponding-source/KernelSU-Next-SCR01/manager/patch_manager.py"
PATCH_SOURCE = ROOT / "corresponding-source/KernelSU-Next-SCR01/manager/patch_source.py"
MANAGER = ROOT / "app/src/main/assets/manager.apk"
RELEASED_KSUD = ROOT / "app/src/main/jniLibs/arm64-v8a/libksud.so"
VERSION_ORIGINAL = '<resources><string name="home_working_version">Version: %1$s (%2$s)</string></resources>\n'
VERSION_PATCHED = '<resources><string name="home_working_version">Version: SCR-01-KSU</string></resources>\n'
KOTLIN_ORIGINAL = """                    } else {
                        profile = it
                        viewModel.updateAppProfile(packageName, it)
                    }
"""
KOTLIN_PATCHED = """                    } else {
                        if (appInfo.uid >= 10000 && packageName != "com.rifsxd.ksunext") {
                            forceStopApp(packageName)
                        }
                        profile = it
                        viewModel.updateAppProfile(packageName, it)
                    }
"""


class CorrespondingSourceTest(unittest.TestCase):
    def test_repository_mapping(self):
        result = subprocess.run(
            [sys.executable, str(VERIFY)],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("verified corresponding source", result.stdout)

    def test_ksud_transformation_is_exact(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "libksud.so"
            subprocess.run(
                [sys.executable, str(PATCH_KSUD), str(MANAGER), str(output)],
                cwd=ROOT,
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertEqual(output.read_bytes(), RELEASED_KSUD.read_bytes())

    def test_ksud_refuses_to_overwrite_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "libksud.so"
            output.write_bytes(b"keep")
            result = subprocess.run(
                [sys.executable, str(PATCH_KSUD), str(MANAGER), str(output)],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(output.read_bytes(), b"keep")

    def test_manager_validation_precedes_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            decoded = Path(directory)
            original = '<resources><string name="home_working_version">Version: %1$s (%2$s)</string></resources>\n'
            resources = []
            for index in range(24):
                path = decoded / "res" / f"values-{index}" / "strings.xml"
                path.parent.mkdir(parents=True)
                path.write_text(original, encoding="utf-8")
                resources.append(path)
            smali = decoded / "smali" / "j8" / "l.smali"
            smali.parent.mkdir(parents=True)
            smali.write_text("invalid anchor\n", encoding="utf-8")
            (decoded / "apktool.yml").write_text(
                "version: 3.0.2\napkFileName: base.apk\n", encoding="utf-8"
            )
            result = subprocess.run(
                [sys.executable, str(PATCH_MANAGER), str(decoded)],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(result.returncode, 0)
            for path in resources:
                self.assertEqual(path.read_text(encoding="utf-8"), original)

    def make_source_fixture(self, root, kotlin_text=KOTLIN_ORIGINAL):
        manager = root / "manager" / "app" / "src" / "main"
        resources = []
        for index in range(24):
            path = manager / "res" / f"values-{index}" / "strings.xml"
            path.parent.mkdir(parents=True)
            path.write_text(VERSION_ORIGINAL, encoding="utf-8")
            resources.append(path)
        kotlin = manager / "java" / "com" / "rifsxd" / "ksunext" / "ui" / "screen" / "AppProfile.kt"
        kotlin.parent.mkdir(parents=True)
        kotlin.write_text(kotlin_text, encoding="utf-8")
        return resources, kotlin

    def test_preferred_source_patch_is_exact_and_single_use(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "KernelSU-Next-3.3.0"
            resources, kotlin = self.make_source_fixture(root)
            result = subprocess.run(
                [sys.executable, str(PATCH_SOURCE), str(root)],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            for path in resources:
                self.assertEqual(path.read_text(encoding="utf-8"), VERSION_PATCHED)
            self.assertEqual(kotlin.read_text(encoding="utf-8"), KOTLIN_PATCHED)
            before_retry = [path.read_bytes() for path in resources] + [kotlin.read_bytes()]
            retry = subprocess.run(
                [sys.executable, str(PATCH_SOURCE), str(root)],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(retry.returncode, 0)
            after_retry = [path.read_bytes() for path in resources] + [kotlin.read_bytes()]
            self.assertEqual(after_retry, before_retry)

    def test_preferred_source_validation_precedes_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "KernelSU-Next-3.3.0"
            resources, _ = self.make_source_fixture(root, "invalid anchor\n")
            result = subprocess.run(
                [sys.executable, str(PATCH_SOURCE), str(root)],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(result.returncode, 0)
            for path in resources:
                self.assertEqual(path.read_text(encoding="utf-8"), VERSION_ORIGINAL)


if __name__ == "__main__":
    unittest.main()
