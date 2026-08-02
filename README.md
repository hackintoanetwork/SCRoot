<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="SCRoot logo" width="180">
</p>

<h1 align="center">SCRoot</h1>

<p align="center">
  <strong>One-click rooting and KernelSU-Next tool for the Samsung SCR-01 mobile 5G router.</strong><br>
  <a href="README.md">English</a> ·
  <a href="README_KO.md">한국어</a>
</p>

<p align="center">
  <img src="docs/images/scroot-not-installed.png" alt="SCRoot before setup" width="19%">
  <img src="docs/images/scroot-rooted.png" alt="SCRoot rooted status" width="19%">
  <img src="docs/images/scroot-exploit-trace.png" alt="SCRoot exploit trace" width="19%">
  <img src="docs/images/scroot-automatic-setup.png" alt="SCRoot automatic setup trace" width="19%">
  <img src="docs/images/kernelsu-next-status.png" alt="KernelSU-Next working status" width="19%">
</p>

SCRoot is a one-click rooting app built specifically for the `SCR01KDU1AVK2` firmware on the Samsung SCR-01 mobile 5G router. It validates the device, runs the bundled CVE-2022-38181 Mali exploit, loads the KernelSU-Next port for the `SCR-01`, and configures the manager and system UI integration.

After sideloading the APK once, it can be used without a computer.

> [!WARNING]
> Use SCRoot only on a device you own. A kernel exploit may reboot or crash the device and may cause data loss.
>
> If the device fingerprint, kernel, firmware, payload, or manager signature does not match the supported target, SCRoot safely stops the operation.

## Compatibility

| Item | Supported target |
| --- | --- |
| Device | Samsung SCR-01 / SM-H412J |
| Product | Galaxy 5G Mobile Wi-Fi |
| Firmware | `SCR01KDU1AVK2` exact supported profile |
| Android | 11 |
| Kernel | `4.14.186-24165939` |
| SoC | MediaTek MT6853 |
| GPU | Mali-G57, Valhall r25p0 |
| Vulnerability | CVE-2022-38181 |
| Root type | Temporary root that is lost after each reboot |

## Features

- One-click root setup with the exploit process shown on screen
- KernelSU-Next v3.3.0 port for the SCR-01 kernel
- Automatic KernelSU manager, `ksud`, and OverlayFS MetaModule configuration
- Optional automatic rooting after reboot
- Root entry in the stock SCR-01 side menu
- One UI-style Apps and Recents integration
- Light and dark themes for the app interface

The bundled Home and SystemUI integration modules do not contain Samsung's `MHSHome.apk` or `SystemUI.apk`. They verify the exact stock firmware APK hashes on the router, apply compact binary deltas locally, and perform guarded late bind mounts only after the generated hashes also match. Unsupported or incomplete results are rejected without replacing the running UI.

## Installation

Download `SCRoot-SCR01-1.1.0.apk` from the repository's [Releases](../../releases/latest), connect the router with USB debugging enabled, and run:

```bash
adb install "SCRoot-SCR01-1.1.0.apk"
adb shell am start -n com.scr01.scroot/.MainActivity
```

## Usage

1. Open SCRoot and select **Start root setup**.
2. Read the first-run warning and select **Continue**.
3. Keep the app in the foreground and do not touch the device while the exploit trace is running.
4. Wait until the status changes to **Rooted**.
5. Select **Open KernelSU** to manage root access and modules.

> [!WARNING]
> Do not run the exploit again in the same boot after a failure. Reboot the router before trying again.

## Automatic rooting after reboot

Enable **Run at boot** in SCRoot. Starting with the next boot, SCRoot displays a live trace, waits for the minimum safe uptime, and performs one guarded rooting attempt. The countdown shows how long you must wait without touching the device.

A successful automatic run restores:

- Root access and full capabilities
- KernelSU runtime and userspace
- OverlayFS MetaModule
- SCRoot and KSU Next entries in the stock menu
- Apps and Recents integration

Kernel patches remain active only temporarily. If **Run at boot** is disabled, root is lost after reboot and SCRoot must be run manually again.

## Expected KernelSU status

After a successful setup, KernelSU-Next should display:

```text
Status: Working
Version: SCR-01-KSU
Mode: LKM (LEGACY)
Metamodule: Installed
```

The manager UI does not open automatically. It opens only when manually selected from SCRoot, the Apps screen, or the stock Root menu.

## How it works

The exploit extends a Mali JIT use-after-free into a stale backing-page alias, reuses that page as a GPU page table, and constructs physical kernel writes through controlled PTEs. The runtime payload disables Samsung DEFEX, adjusts the target SELinux/AVC paths, installs a temporary credential hook, and then loads the target-specific KernelSU module.

The implementation is pinned to the exact kernel layout and Mali ABI of `SCR01KDU1AVK2`. It is not a generic Mali exploit or a root tool for every Samsung device.

For more information, visit https://hackintoanetwork.com/blog.

## Source and licenses

Original SCRoot material is available under the PolyForm Noncommercial 1.0.0 license. Bundled third-party components retain their own licenses. Exact upstream snapshots, SCR-01 modifications, build inputs, and release hash mappings for the GPL-covered KernelSU-Next and meta-overlayfs components are included in [`corresponding-source`](corresponding-source). See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for the complete component mapping.
