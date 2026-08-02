import hashlib
import importlib.util
import shutil
import stat
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parent / "apk-delta/generate_deltas.py"
SPEC = importlib.util.spec_from_file_location("generate_deltas", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("could not load delta generator")
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class CopyBackend:
    @staticmethod
    def file_diff(stock: str, patched: str, patch: str) -> None:
        shutil.copyfile(patched, patch)

    @staticmethod
    def file_patch(stock: str, restored: str, patch: str) -> None:
        shutil.copyfile(patch, restored)


class CorruptBackend(CopyBackend):
    @staticmethod
    def file_patch(stock: str, restored: str, patch: str) -> None:
        shutil.copyfile(stock, restored)


class DeltaGeneratorTest(unittest.TestCase):
    def test_generation_is_verified_and_atomically_replaces_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stock = root / "stock.apk"
            patched = root / "patched.apk"
            output = root / "output.bsdiff"
            stock.write_bytes(b"stock")
            patched.write_bytes(b"patched")
            output.write_bytes(b"old")
            patch_hash, patch_size = generator.generate_delta(
                stock,
                patched,
                output,
                digest(b"stock"),
                digest(b"patched"),
                CopyBackend,
            )
            self.assertEqual(b"patched", output.read_bytes())
            self.assertEqual(digest(b"patched"), patch_hash)
            self.assertEqual(len(b"patched"), patch_size)
            self.assertEqual(0o600, stat.S_IMODE(output.stat().st_mode))

    def test_output_must_not_alias_an_input(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stock = root / "stock.apk"
            patched = root / "patched.apk"
            stock.write_bytes(b"stock")
            patched.write_bytes(b"patched")
            with self.assertRaises(ValueError):
                generator.generate_delta(
                    stock,
                    patched,
                    stock,
                    digest(b"stock"),
                    digest(b"patched"),
                    CopyBackend,
                )
            self.assertEqual(b"stock", stock.read_bytes())

    def test_input_symlinks_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            real_stock = root / "real-stock.apk"
            stock = root / "stock.apk"
            patched = root / "patched.apk"
            output = root / "output.bsdiff"
            real_stock.write_bytes(b"stock")
            stock.symlink_to(real_stock)
            patched.write_bytes(b"patched")
            with self.assertRaises(ValueError):
                generator.generate_delta(
                    stock,
                    patched,
                    output,
                    digest(b"stock"),
                    digest(b"patched"),
                    CopyBackend,
                )

    def test_output_symlinks_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stock = root / "stock.apk"
            patched = root / "patched.apk"
            victim = root / "victim"
            output = root / "output.bsdiff"
            stock.write_bytes(b"stock")
            patched.write_bytes(b"patched")
            victim.write_bytes(b"victim")
            output.symlink_to(victim)
            with self.assertRaises(ValueError):
                generator.generate_delta(
                    stock,
                    patched,
                    output,
                    digest(b"stock"),
                    digest(b"patched"),
                    CopyBackend,
                )
            self.assertEqual(b"victim", victim.read_bytes())

    def test_failed_round_trip_preserves_previous_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stock = root / "stock.apk"
            patched = root / "patched.apk"
            output = root / "output.bsdiff"
            stock.write_bytes(b"stock")
            patched.write_bytes(b"patched")
            output.write_bytes(b"previous")
            with self.assertRaises(ValueError):
                generator.generate_delta(
                    stock,
                    patched,
                    output,
                    digest(b"stock"),
                    digest(b"patched"),
                    CorruptBackend,
                )
            self.assertEqual(b"previous", output.read_bytes())

    def test_hash_arguments_are_strict(self) -> None:
        with self.assertRaises(ValueError):
            generator.normalize_sha256("not-a-hash")
        self.assertEqual("a" * 64, generator.normalize_sha256("A" * 64))


if __name__ == "__main__":
    unittest.main()
