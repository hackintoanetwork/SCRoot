# Third-party notices

SCRoot is a mixed-license distribution. The PolyForm Noncommercial License
applies only to original SCRoot materials identified by the top-level
`LICENSE`. It does not change the licenses below.

## KernelSU-Next

Bundled or adapted KernelSU-Next material includes the manager APK, `ksud`, the
ADB root preload library, protocol support, and the SCR-01 kernel port.

- Upstream: https://github.com/KernelSU-Next/KernelSU-Next
- Source snapshot: `corresponding-source/upstream/KernelSU-Next-v3.3.0.tar.gz`
  (`v3.3.0`, commit `3b18216f71df189ab3d1b1ce0bdb21be1268e771`).
- Upstream license rule: `/kernel` is GPL-2.0-only; all other upstream files
  are GPL-3.0-or-later.
- License copies: `LICENSES/GPL-2.0-only.txt` and
  `LICENSES/GPL-3.0-or-later.txt`.
- Relevant distributed paths include `app/src/main/assets/manager.apk`,
  `app/src/main/jniLibs/arm64-v8a/libksud.so`,
  `app/src/main/jniLibs/arm64-v8a/libadbroot.so`, and
  `app/src/main/jniLibs/arm64-v8a/libksuglue.so`.

The corresponding GPL terms and source-distribution obligations continue to
apply to these components and any modifications of them.

## OverlayFS MetaModule

`app/src/main/assets/meta-overlayfs-scr01.zip` contains a port of the official
meta-overlayfs reference implementation and its executable.

- Upstream: https://github.com/KernelSU-Modules-Repo/meta-overlayfs
- Source snapshot: `corresponding-source/upstream/meta-overlayfs-v1.3.1.tar.gz`
  (`v1.3.1`, commit `7143eb79cc14d68f070081beed60ecf8344da3a8`).
- License: GPL-3.0
- License copy: `LICENSES/GPL-3.0-or-later.txt`

SCR-01-specific integration scripts do not remove or narrow the upstream GPL
rights and obligations for the combined module.

## CVE-2022-38181 exploit base

The SCR-01 Mali exploit is an adapted target-specific implementation based on
the GitHub Security Lab proof of concept by Man Yue Mo.

- Upstream: https://github.com/github/securitylab/tree/main/SecurityExploits/Android/Mali/CVE_2022_38181
- Copyright: Copyright (c) 2019 GitHub, Inc.
- License: MIT
- License copy: `LICENSES/MIT-GitHub-Security-Lab.txt`
- Relevant distributed path: `app/src/main/jniLibs/arm64-v8a/libexploit.so`

SCRoot-specific additions may be covered by the SCRoot license only to the
extent hackintoanetwork owns those additions. The upstream MIT notice remains
in force.

## Gradle wrapper

`gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are Gradle
wrapper files distributed under Apache License 2.0.

- Upstream: https://github.com/gradle/gradle
- License: Apache-2.0
- License copy: `LICENSES/Apache-2.0.txt`

The wrapper scripts retain their upstream copyright and license headers.

## bsdiff4 development dependency

`tools/apk-delta/generate_deltas.py` uses the separately installed `bsdiff4`
Python package to create development-time patches. The package is not bundled
in this repository.

- Upstream: https://github.com/ilanschnell/bsdiff4
- License: BSD-style license
- License copy: `LICENSES/BSD-bsdiff4.txt`

## Samsung firmware material and trademarks

The Home and SystemUI patch archives contain compact binary deltas and do not
contain complete Samsung system APKs. This repository does not grant rights to
Samsung firmware, fonts, names, logos, or trademarks. A user must supply the
exact supported stock firmware on their own device; generated output is
verified and produced locally.

Android is a trademark of Google LLC. Samsung and Galaxy are trademarks of
Samsung Electronics Co., Ltd. Their use here is solely descriptive and does
not imply endorsement.

## Corresponding source

The exact upstream snapshots, SCR-01 modifications, transformation scripts,
build inputs, and source-to-release hash mapping are included under
`corresponding-source`. Run `python3 tools/verify_corresponding_source.py` from
the repository root to verify all five distributed GPL artifacts.
