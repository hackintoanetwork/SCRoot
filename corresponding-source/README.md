# Corresponding source

This directory accompanies the GPL-covered binaries distributed in SCRoot 1.1.0. The files map the released binaries to exact upstream source snapshots, local SCR-01 changes, and the build or post-link steps used for those changes.

## Upstream snapshots

- `upstream/KernelSU-Next-v3.3.0.tar.gz` is the GitHub tag archive for KernelSU-Next v3.3.0 at commit `3b18216f71df189ab3d1b1ce0bdb21be1268e771`.
- `upstream/meta-overlayfs-v1.3.1.tar.gz` is the GitHub tag archive for meta-overlayfs v1.3.1 at commit `7143eb79cc14d68f070081beed60ecf8344da3a8`.

The snapshots retain their upstream licenses and build files. The top-level SCRoot PolyForm license does not replace their GPL terms.

## Released component mapping

| Released file | Source and transformation |
| --- | --- |
| `app/src/main/assets/manager.apk` | KernelSU-Next v3.3.0 Manager plus `KernelSU-Next-SCR01/manager/patch_manager.py`, rebuilt with Apktool 3.0.2 and signed with the SCRoot release certificate. |
| `app/src/main/jniLibs/arm64-v8a/libksud.so` | The v3.3.0 Manager's ARM64 `libksud.so` plus `KernelSU-Next-SCR01/userspace/patch_ksud.py`. |
| `app/src/main/jniLibs/arm64-v8a/libadbroot.so` | Unmodified ARM64 `libadbroot.so` from the v3.3.0 Manager. |
| `app/src/main/jniLibs/arm64-v8a/libksuglue.so` | `KernelSU-Next-SCR01/kernel/ksu_glue.c`, its build inputs, and the checked init-relocation adjustment. |
| `app/src/main/assets/meta-overlayfs-scr01.zip` | Unmodified v1.3.1 ARM64 executable plus the scripts in `meta-overlayfs-SCR01/module`. |

The Manager patch changes the displayed SCR-01 version label and stops a non-system target app after a successful App Profile transaction so its next process receives the new credentials. The separate `ksud` transformation disables the daemon's automatic Manager Activity launch by changing one component-name byte; this prevents a brief foreground Manager window during automatic boot setup.

`KernelSU-Next-SCR01/manager/patch_source.py` applies the same Manager changes to the preferred Kotlin/XML source form in the included v3.3.0 snapshot. `patch_manager.py` records the exact Apktool-level transformation used by the released APK.

## Manager reconstruction

Download `KernelSU_Next_v3.3.0_33214-release.apk` from the official v3.3.0 release, Apktool 3.0.2, and Android SDK Platform 36 revision 2, then run:

```text
./KernelSU-Next-SCR01/manager/build-unsigned.sh official.apk apktool_3.0.2.jar android-36.jar manager-unsigned.apk
```

For a normal source build, extract the included KernelSU-Next archive and run `python3 KernelSU-Next-SCR01/manager/patch_source.py KernelSU-Next-3.3.0` before following the upstream Manager build instructions.

The script rejects any base APK or Apktool JAR with an unexpected SHA-256. Sign the result with your own Android signing key. The SCRoot release private key is not distributed. A fork using a different certificate must update its own Manager signer and artifact hashes in `RootFlow.kt`; those checks protect users from silently substituted privileged components.

To reproduce the standalone `libksud.so` from either the official or SCRoot Manager APK, run:

```text
python3 KernelSU-Next-SCR01/userspace/patch_ksud.py manager.apk libksud.so
```

## Kernel module reconstruction

The kernel module build is pinned to Linux 4.14.186, the SCR-01 configuration, Ubuntu 20.04 amd64, and GCC 9.4.0. Docker is required:

```text
./KernelSU-Next-SCR01/kernel/build-in-docker.sh
```

The build stops unless both the unpatched and final module hashes match the released files. It writes only to `KernelSU-Next-SCR01/out` unless another output directory is supplied.

## Verification

Run the repository verifier from the repository root:

```text
python3 tools/verify_corresponding_source.py
```

`SOURCE_SHA256SUMS` records the upstream snapshots, local sources, transformations, and released GPL binaries. Android signing keys, passwords, device data, and proprietary Samsung firmware are not corresponding source and are not included.
