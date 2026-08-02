from __future__ import annotations

import bz2
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


SOURCE = Path(__file__).parent / "apk-delta/scbspatch.c"


def encode_offset(value: int) -> bytes:
    magnitude = abs(value)
    if magnitude >= 1 << 63:
        raise ValueError("offset out of range")
    encoded = bytearray(magnitude.to_bytes(8, "little"))
    if value < 0:
        encoded[7] |= 0x80
    return bytes(encoded)


def create_patch(
    controls: list[tuple[int, int, int]],
    diff: bytes,
    extra: bytes,
    new_size: int,
) -> bytes:
    control_data = b"".join(encode_offset(value) for control in controls for value in control)
    compressed_control = bz2.compress(control_data)
    compressed_diff = bz2.compress(diff)
    compressed_extra = bz2.compress(extra)
    return (
        b"BSDIFF40"
        + encode_offset(len(compressed_control))
        + encode_offset(len(compressed_diff))
        + encode_offset(new_size)
        + compressed_control
        + compressed_diff
        + compressed_extra
    )


class ScbspatchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        cls.binary = cls.root / "scbspatch"
        compiler = shutil.which("cc")
        bzip2 = shutil.which("bzip2")
        if compiler is None or bzip2 is None:
            raise unittest.SkipTest("C compiler and bzip2 are required")
        subprocess.run(
            [
                compiler,
                "-std=c11",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-D_FILE_OFFSET_BITS=64",
                f'-DBZIP2_PATH="{bzip2}"',
                str(SOURCE),
                "-o",
                str(cls.binary),
            ],
            check=True,
            timeout=30,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    def invoke(self, old: bytes, patch: bytes, existing_output: bytes | None = None):
        case = Path(tempfile.mkdtemp(dir=self.root))
        old_path = case / "old.apk"
        patch_path = case / "delta.bsdiff"
        output_path = case / "new.apk"
        old_path.write_bytes(old)
        patch_path.write_bytes(patch)
        if existing_output is not None:
            output_path.write_bytes(existing_output)
        result = subprocess.run(
            [str(self.binary), str(old_path), str(output_path), str(patch_path)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            timeout=5,
        )
        return case, output_path, result

    def test_applies_valid_patch_and_cleans_temporary_streams(self) -> None:
        old = b"abc"
        new = b"axc"
        diff = bytes((new[index] - old[index]) & 0xFF for index in range(len(new)))
        patch = create_patch([(3, 0, 0)], diff, b"", len(new))
        case, output, result = self.invoke(old, patch)
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertEqual(new, output.read_bytes())
        self.assertEqual([], list(case.glob("*.bz2")))

    def test_seek_only_control_tuple_remains_supported(self) -> None:
        patch = create_patch([(0, 0, 1), (1, 0, 0)], b"\x00", b"", 1)
        _, output, result = self.invoke(b"AB", patch)
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertEqual(b"B", output.read_bytes())

    def test_no_progress_control_tuple_is_rejected(self) -> None:
        patch = create_patch([(0, 0, 0), (1, 0, 0)], b"\x00", b"", 1)
        _, output, result = self.invoke(b"A", patch)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output.exists())

    def test_trailing_decompressed_data_is_rejected(self) -> None:
        patch = create_patch([(1, 0, 0)], b"\x00", b"unused", 1)
        _, output, result = self.invoke(b"A", patch)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output.exists())

    def test_existing_output_is_preserved(self) -> None:
        patch = create_patch([(1, 0, 0)], b"\x00", b"", 1)
        _, output, result = self.invoke(b"A", patch, b"existing")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b"existing", output.read_bytes())

    def test_special_input_file_is_rejected(self) -> None:
        case = Path(tempfile.mkdtemp(dir=self.root))
        patch_path = case / "delta.bsdiff"
        output_path = case / "new.apk"
        patch_path.write_bytes(create_patch([(1, 0, 0)], b"\x00", b"", 1))
        result = subprocess.run(
            [str(self.binary), "/dev/null", str(output_path), str(patch_path)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            timeout=5,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output_path.exists())


if __name__ == "__main__":
    unittest.main()
