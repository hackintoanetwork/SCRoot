#!/usr/bin/env python3

import argparse
import hashlib
import os
import re
import stat
import tempfile
from pathlib import Path
from typing import Any


MAX_APK_BYTES = 256 * 1024 * 1024
MAX_PATCH_BYTES = 256 * 1024 * 1024
SHA256_PATTERN = re.compile(r"[0-9a-fA-F]{64}")


def normalize_sha256(value: str) -> str:
    if SHA256_PATTERN.fullmatch(value) is None:
        raise ValueError("SHA-256 values must contain exactly 64 hexadecimal characters")
    return value.lower()


def regular_metadata(path: Path, maximum_bytes: int) -> os.stat_result:
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        raise ValueError(f"missing file: {path}") from None
    if not stat.S_ISREG(metadata.st_mode):
        raise ValueError(f"not a regular file: {path}")
    if metadata.st_size <= 0 or metadata.st_size > maximum_bytes:
        raise ValueError(f"invalid file size: {path}")
    return metadata


def snapshot_file(source: Path, destination: Path, expected_sha256: str) -> None:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    source_fd = os.open(source, flags)
    try:
        source_metadata = os.fstat(source_fd)
        if not stat.S_ISREG(source_metadata.st_mode):
            raise ValueError(f"not a regular file: {source}")
        if source_metadata.st_size <= 0 or source_metadata.st_size > MAX_APK_BYTES:
            raise ValueError(f"invalid file size: {source}")
        destination_fd = os.open(
            destination,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0),
            0o600,
        )
        digest = hashlib.sha256()
        with os.fdopen(source_fd, "rb", closefd=False) as input_stream:
            with os.fdopen(destination_fd, "wb") as output_stream:
                while block := input_stream.read(1024 * 1024):
                    digest.update(block)
                    output_stream.write(block)
                output_stream.flush()
                os.fsync(output_stream.fileno())
        if digest.hexdigest() != expected_sha256:
            raise ValueError(f"input APK hash mismatch: {source}")
    finally:
        os.close(source_fd)


def verify_output_target(output: Path, inputs: tuple[Path, Path]) -> Path:
    parent = output.parent
    try:
        parent_metadata = parent.lstat()
    except FileNotFoundError:
        raise ValueError(f"missing output directory: {parent}") from None
    if not stat.S_ISDIR(parent_metadata.st_mode):
        raise ValueError(f"output parent is not a real directory: {parent}")
    output_resolved = output.resolve(strict=False)
    for input_path in inputs:
        if output_resolved == input_path.resolve(strict=False):
            raise ValueError("output must not replace an input APK")
    try:
        output_metadata = output.lstat()
    except FileNotFoundError:
        return parent
    if not stat.S_ISREG(output_metadata.st_mode):
        raise ValueError(f"output is not a regular file: {output}")
    for input_path in inputs:
        if os.path.samefile(output, input_path):
            raise ValueError("output must not replace an input APK")
    return parent


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def sync_directory(path: Path) -> None:
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_CLOEXEC", 0)
    directory_fd = os.open(path, flags)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)


def generate_delta(
    stock: Path,
    patched: Path,
    output: Path,
    stock_sha256: str,
    patched_sha256: str,
    backend: Any,
) -> tuple[str, int]:
    expected_stock = normalize_sha256(stock_sha256)
    expected_patched = normalize_sha256(patched_sha256)
    regular_metadata(stock, MAX_APK_BYTES)
    regular_metadata(patched, MAX_APK_BYTES)
    parent = verify_output_target(output, (stock, patched))
    with tempfile.TemporaryDirectory(prefix=f".{output.name}.", dir=parent) as temporary:
        staging = Path(temporary)
        stock_snapshot = staging / "stock.apk"
        patched_snapshot = staging / "patched.apk"
        patch = staging / "delta.bsdiff"
        restored = staging / "restored.apk"
        snapshot_file(stock, stock_snapshot, expected_stock)
        snapshot_file(patched, patched_snapshot, expected_patched)
        backend.file_diff(str(stock_snapshot), str(patched_snapshot), str(patch))
        regular_metadata(patch, MAX_PATCH_BYTES)
        backend.file_patch(str(stock_snapshot), str(restored), str(patch))
        regular_metadata(restored, MAX_APK_BYTES)
        if hash_file(restored) != expected_patched:
            raise ValueError("generated delta failed round-trip verification")
        os.chmod(patch, 0o600)
        with patch.open("rb") as patch_stream:
            os.fsync(patch_stream.fileno())
        patch_hash = hash_file(patch)
        patch_size = patch.stat().st_size
        os.replace(patch, output)
        sync_directory(parent)
    return patch_hash, patch_size


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("stock", type=Path)
    parser.add_argument("patched", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--stock-sha256", required=True)
    parser.add_argument("--patched-sha256", required=True)
    args = parser.parse_args()

    try:
        import bsdiff4

        patch_hash, patch_size = generate_delta(
            args.stock,
            args.patched,
            args.output,
            args.stock_sha256,
            args.patched_sha256,
            bsdiff4,
        )
    except (ImportError, OSError, RuntimeError, ValueError) as error:
        raise SystemExit(str(error)) from None
    print(f"{args.output} {patch_hash} {patch_size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
