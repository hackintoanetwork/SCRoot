#!/usr/bin/env python3

import hashlib
import stat
import sys
import tarfile
import zipfile
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "corresponding-source"
ASSETS = ROOT / "app/src/main/assets"
NATIVE = ROOT / "app/src/main/jniLibs/arm64-v8a"
EXPECTED_FILES = {
    "upstream/KernelSU-Next-v3.3.0.tar.gz": "5ab93ee9441c91a2164d7c327cefeac589e1d6eadcc9557aac43c5019a22bda2",
    "upstream/meta-overlayfs-v1.3.1.tar.gz": "ccf90fbd6dd5b5f40204b0fbbfc8db5d2540fd0a6d0939a2dec70f43282c490c",
    "KernelSU-Next-SCR01/kernel/Kbuild": "7881e2c64cd44626665d4876c8cbf1ed5808a4069afd611945b4c7a7437c5aef",
    "KernelSU-Next-SCR01/kernel/adb_root_toggle.c": "56de7d6d82bfe0c830ea25598a8f7f34dea6436d2a1ac22e6e4eeaad214193b5",
    "KernelSU-Next-SCR01/kernel/build-in-docker.sh": "5b3a6e327c4ca6db4ace305e228f19bbfa816984da7d5ec59bcc1d0ab33b4055",
    "KernelSU-Next-SCR01/kernel/device.config": "bb1abef0939942dc644ad4a21ae71fe173c3e4b09e73ce89924ae4d1c1be0191",
    "KernelSU-Next-SCR01/kernel/ksu_glue.c": "2695356243c548e5e2687ae502b24c9459c26fb9705928711f2effff1d75a1ed",
    "KernelSU-Next-SCR01/kernel/patch_init_offset.py": "5893b09c7153187da3077e6f9513c576dd196b58c2128fa99e47cc775bf7a520",
    "KernelSU-Next-SCR01/manager/build-unsigned.sh": "c90578d0ff4a2cd37c5cf77fb747f5c36f1a5c931572f648dd927cfef4c73320",
    "KernelSU-Next-SCR01/manager/patch_manager.py": "548c31f299860244b0eac032f2fc094c50bff095fd17e6ca9fb8cb2636a9071b",
    "KernelSU-Next-SCR01/manager/patch_source.py": "835c777cb09afeadf9d7e323cb165b97d179edc190c890bbe260a8283edcd395",
    "KernelSU-Next-SCR01/userspace/patch_ksud.py": "24063cbadfff64222cee415d0b1ff78cc7a802dc3cd3822e89beeb2314b57a7e",
    "meta-overlayfs-SCR01/module/customize.sh": "5a63c2e566c1a5b449ba4cb34eae2d60f25530a4fc589c5a4d70f201849229b7",
    "meta-overlayfs-SCR01/module/metainstall.sh": "d37ff93360bb4e88f76fec4d7587b663e312c324727c351bfc715b4d9cfb8aff",
    "meta-overlayfs-SCR01/module/metamount.sh": "96fa80c084d298cd0707531a07b6920264c5635295ae5d95f34a6ffc7f959254",
    "meta-overlayfs-SCR01/module/metauninstall.sh": "0421cf45ab6b1fb51c7c2b1c8a93a29976c967ab8b0bbfadec1a45fbb66475ec",
    "meta-overlayfs-SCR01/module/module.prop": "a5af76462173be19dbf945e1c901f651c8c1794b23b2e78a7b78a42a72612e1c",
    "meta-overlayfs-SCR01/module/post-mount.sh": "cc9e198f97fa4505d5cfc5e04265a8bae9644206576ef73917de95d22d55fce3",
    "meta-overlayfs-SCR01/module/uninstall.sh": "cc0e4342a39f8da1dadf5e96f6b0da1884e7bb10de670cbcb74d4bd4ed3e7e49",
}
EXPECTED_RELEASES = {
    "../app/src/main/assets/manager.apk": "80a9e4b1ba9644f361add3e003e1075bd4f9cb374bbde465c2b57522b5288ba9",
    "../app/src/main/assets/meta-overlayfs-scr01.zip": "df4b4a33c9974eb873e62ad01fa7229e9648cc848d032f6192a89b055cb9528c",
    "../app/src/main/jniLibs/arm64-v8a/libadbroot.so": "5562adc1e5c6f52fb91f469a1a7d3480050d8697fb5dedbd7fb386d282cd88b8",
    "../app/src/main/jniLibs/arm64-v8a/libksud.so": "637676421190aeec504093707ad675a45faaf11bd4b53129d52e150490902cca",
    "../app/src/main/jniLibs/arm64-v8a/libksuglue.so": "1cec66df04a0578e315565658198cf1af26f976cdac11ab3755bb5190d7138da",
}
EXPECTED_MANAGER_INNER = {
    "lib/arm64-v8a/libadbroot.so": "5562adc1e5c6f52fb91f469a1a7d3480050d8697fb5dedbd7fb386d282cd88b8",
    "lib/arm64-v8a/libkernelsu.so": "a8d423cf664aa1c1f7a006f5a996f95c72fc23ca5c8d8ddcd307b7ed5da39def",
    "lib/arm64-v8a/libksud.so": "f4359553a597955956b97e86277e08bc426f644de0dba5ff13da562fba29c55d",
}
EXPECTED_META_BINARY = "04ef286eb33dd6650be08c368cc16d06e369aeae2c582f8df520d0a7dcc2fa95"
EXPECTED_KERNEL_OUTPUTS = {
    "ksu_glue.unpatched.ko": "7c433c1fd5d8a081f4eec0f97c24041f6c08833c2f430899663a13da91ae4354",
    "ksu_glue.ko": "1cec66df04a0578e315565658198cf1af26f976cdac11ab3755bb5190d7138da",
}
ORIGINAL_COMPONENT = b"#/com.rifsxd.ksunext.ui.MainActivity\x00"
PATCHED_COMPONENT = b"#/com.rifsxd.ksunext.ui.MainActivit_\x00"


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def require_regular(path: Path) -> None:
    metadata = path.lstat()
    if not stat.S_ISREG(metadata.st_mode):
        raise ValueError(f"not a regular file: {path}")
    if metadata.st_size <= 0:
        raise ValueError(f"empty file: {path}")


def verify_files() -> None:
    for relative, expected in {**EXPECTED_FILES, **EXPECTED_RELEASES}.items():
        path = SOURCE / relative
        require_regular(path)
        actual = sha256_file(path)
        if actual != expected:
            raise ValueError(f"hash mismatch: {relative}")


def verify_source_tree() -> None:
    allowed = {SOURCE / relative for relative in EXPECTED_FILES}
    allowed.update({SOURCE / "README.md", SOURCE / "SOURCE_SHA256SUMS"})
    for entry in SOURCE.rglob("*"):
        relative = entry.relative_to(SOURCE)
        if relative.parts[:2] == ("KernelSU-Next-SCR01", "out"):
            if len(relative.parts) == 2:
                if not stat.S_ISDIR(entry.lstat().st_mode):
                    raise ValueError("kernel output path is not a directory")
                continue
            if len(relative.parts) != 3 or relative.name not in EXPECTED_KERNEL_OUTPUTS:
                raise ValueError(f"unexpected kernel output: {entry}")
            require_regular(entry)
            if sha256_file(entry) != EXPECTED_KERNEL_OUTPUTS[relative.name]:
                raise ValueError(f"kernel output hash mismatch: {entry}")
            continue
        metadata = entry.lstat()
        if stat.S_ISDIR(metadata.st_mode):
            continue
        if entry not in allowed:
            raise ValueError(f"unexpected corresponding-source entry: {entry}")
        if not stat.S_ISREG(metadata.st_mode):
            raise ValueError(f"non-regular corresponding-source entry: {entry}")
    missing = {path for path in allowed if not path.is_file()}
    if missing:
        raise ValueError(f"missing corresponding-source entries: {sorted(missing)}")
    if (ROOT / "SECURITY.md").exists():
        raise ValueError("top-level SECURITY.md is intentionally excluded")


def safe_tar_path(name: str, root: str) -> None:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        raise ValueError(f"unsafe archive path: {name}")
    if path.parts[0] != root:
        raise ValueError(f"unexpected archive root: {name}")


def resolve_archive_path(path: PurePosixPath) -> PurePosixPath:
    parts = []
    for part in path.parts:
        if part in ("", "."):
            continue
        if part == "..":
            if not parts:
                raise ValueError(f"archive link escapes its root: {path}")
            parts.pop()
        else:
            parts.append(part)
    return PurePosixPath(*parts)


def verify_tar(path: Path, root: str, required: set[str]) -> None:
    with tarfile.open(path, "r:gz") as archive:
        names = set()
        for member in archive.getmembers():
            safe_tar_path(member.name, root)
            names.add(member.name)
            if member.isdev() or member.isfifo():
                raise ValueError(f"special archive member: {member.name}")
            if member.issym() or member.islnk():
                target = PurePosixPath(member.linkname)
                if target.is_absolute():
                    raise ValueError(f"unsafe archive link: {member.name}")
                if member.issym():
                    target = PurePosixPath(member.name).parent / target
                resolved = resolve_archive_path(target)
                if not resolved.parts or resolved.parts[0] != root:
                    raise ValueError(f"unsafe archive link: {member.name}")
        missing = required - names
        if missing:
            raise ValueError(f"missing archive members: {sorted(missing)}")


def verify_archives() -> None:
    verify_tar(
        SOURCE / "upstream/KernelSU-Next-v3.3.0.tar.gz",
        "KernelSU-Next-3.3.0",
        {
            "KernelSU-Next-3.3.0/LICENSE",
            "KernelSU-Next-3.3.0/manager/build.gradle.kts",
            "KernelSU-Next-3.3.0/userspace/ksud/Cargo.lock",
            "KernelSU-Next-3.3.0/userspace/ksud/src/main.rs",
        },
    )
    verify_tar(
        SOURCE / "upstream/meta-overlayfs-v1.3.1.tar.gz",
        "meta-overlayfs-1.3.1",
        {
            "meta-overlayfs-1.3.1/Cargo.toml",
            "meta-overlayfs-1.3.1/build.sh",
            "meta-overlayfs-1.3.1/src/main.rs",
        },
    )


def verify_manager() -> None:
    manager = ASSETS / "manager.apk"
    with zipfile.ZipFile(manager) as archive:
        for name, expected in EXPECTED_MANAGER_INNER.items():
            actual = sha256_bytes(archive.read(name))
            if actual != expected:
                raise ValueError(f"Manager inner hash mismatch: {name}")
        upstream_ksud = archive.read("lib/arm64-v8a/libksud.so")
        upstream_adbroot = archive.read("lib/arm64-v8a/libadbroot.so")
    if upstream_ksud.count(ORIGINAL_COMPONENT) != 1:
        raise ValueError("ksud Manager component anchor mismatch")
    patched = upstream_ksud.replace(ORIGINAL_COMPONENT, PATCHED_COMPONENT)
    released = (NATIVE / "libksud.so").read_bytes()
    if patched != released:
        raise ValueError("released libksud.so is not the declared one-byte transformation")
    if (NATIVE / "libadbroot.so").read_bytes() != upstream_adbroot:
        raise ValueError("released libadbroot.so differs from the upstream Manager library")


def verify_meta_module() -> None:
    archive_path = ASSETS / "meta-overlayfs-scr01.zip"
    source_dir = SOURCE / "meta-overlayfs-SCR01/module"
    with zipfile.ZipFile(archive_path) as archive:
        if sha256_bytes(archive.read("meta-overlayfs-aarch64")) != EXPECTED_META_BINARY:
            raise ValueError("meta-overlayfs executable hash mismatch")
        for source in sorted(source_dir.iterdir()):
            require_regular(source)
            if archive.read(source.name) != source.read_bytes():
                raise ValueError(f"meta-overlayfs script mismatch: {source.name}")


def parse_manifest() -> dict[str, str]:
    result = {}
    for line in (SOURCE / "SOURCE_SHA256SUMS").read_text(encoding="utf-8").splitlines():
        digest, separator, relative = line.partition("  ")
        if not separator or len(digest) != 64 or relative in result:
            raise ValueError("invalid SOURCE_SHA256SUMS entry")
        result[relative] = digest
    return result


def verify_manifest() -> None:
    expected = {**EXPECTED_FILES, **EXPECTED_RELEASES}
    actual = parse_manifest()
    if actual != expected:
        raise ValueError("SOURCE_SHA256SUMS content mismatch")


def main() -> int:
    try:
        verify_source_tree()
        verify_files()
        verify_archives()
        verify_manager()
        verify_meta_module()
        verify_manifest()
    except (FileNotFoundError, OSError, ValueError, KeyError, tarfile.TarError, zipfile.BadZipFile) as error:
        print(f"corresponding-source verification failed: {error}", file=sys.stderr)
        return 1
    print("verified corresponding source for 5 released GPL artifacts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
