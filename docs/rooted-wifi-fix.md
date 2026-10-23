# Rooted Wi-Fi STA failure on SCR01 AVK2

Related: https://github.com/hackintoanetwork/SCRoot/issues/8

## Cause

Both `hook_execve` and `hook_execveat` refresh every UID-0 process when
`enable_manager_fd` is enabled. This includes init's newly forked service
children. `ksu_escalate_current()` replaces their credentials and selects
`u:r:shell:s0`, discarding the SELinux exec transition prepared by init.

The vendor supplicant starts as root before dropping to UID 1010. After
rooting, it therefore remains in `u:r:shell:s0` rather than
`u:r:hal_wifi_supplicant_default:s0`. Its HIDL registration fails with:

```text
Service android.hardware.wifi.supplicant@1.3::ISupplicant/default must be in VINTF manifest in order to register/get.
Failed to register as service supplicant interface with HIDL status: -2147483648
```

The VINTF declaration is already present. Wi-Fi HAL and wlan0 initialization
succeed, but WifiNative times out waiting for the supplicant and disables STA.

## Change

Skip root credential refresh and driver-FD injection for tasks still in the
init domain in both exec hooks. Init can then perform the normal executable
domain transition. Existing manager and su helper handling remains active.

The bundled module, its runtime integrity pin, the relocation patch pins,
and corresponding-source manifests are updated together.

## Validation

On an SCR01 running SCR01KDU1AVK2, the original module reproduced the failure.
Temporarily disabling `enable_manager_fd` only while restarting supplicant
restored its correct domain and allowed STA to connect; the parameter was
then restored to `Y`.

The original source was rebuilt in Ubuntu 20.04 with GCC 9.4.0, Linux
4.14.186, the checked-in device configuration, and the documented paths.
Its unpatched SHA-256 matched the release pin exactly:
`0a37c39e94f816b7c769efa583e0134d4e3331a38d6c93a926b9373229e70ebd`.
The modified source was built in the same environment and reproduced the
new module pins on a second build.

Both payload verifiers and all 76 Python tests pass. Android JVM unit tests
and lint also pass.

The rebuilt module was then installed and loaded by automatic rooting after
a reboot with Wi-Fi disabled. With `enable_manager_fd=Y`, two separate
disable/enable cycles started fresh supplicant processes in
`u:r:hal_wifi_supplicant_default:s0`; both connected to an existing AP and
Android reported the network as `VALIDATED`. Root access remained available
and the automatic setup completed successfully. No VINTF or vendor Wi-Fi
binary changes were needed.
