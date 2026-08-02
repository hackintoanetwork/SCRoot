import hashlib
import stat
import tempfile
import unittest
import zipfile
from pathlib import Path

import verify_release_assets as verifier


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def add_entry(
    archive: zipfile.ZipFile,
    name: str,
    data: bytes,
    mode: int = stat.S_IFREG | 0o644,
) -> None:
    info = zipfile.ZipInfo(name)
    info.create_system = 3
    info.external_attr = mode << 16
    archive.writestr(info, data)


class ReleaseAssetVerifierTest(unittest.TestCase):
    def test_main_activity_never_leaves_fresh_setup_in_checking_state(self) -> None:
        source = verifier.MAIN_ACTIVITY.read_text("utf-8")
        verifier.verify_main_activity_safety_invariants(source)
        unsafe = source.replace(
            'setButtonEnabled(true, "Start root setup", primary)',
            'setButtonEnabled(false, "Checking status", primary)',
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_main_activity_safety_invariants(unsafe)

    def test_regular_file_check_does_not_follow_symlinks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            regular = root / "regular"
            regular.write_bytes(b"data")
            verifier.require_regular_file(regular)
            symlink = root / "symlink"
            symlink.symlink_to(regular)
            with self.assertRaises(ValueError):
                verifier.require_regular_file(symlink)
            with self.assertRaises(ValueError):
                verifier.require_regular_file(root)

    def test_directory_allowlist_rejects_unexpected_entries(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            expected = root / "expected"
            expected.write_bytes(b"data")
            verifier.verify_exact_regular_files(root, {expected})
            (root / "unexpected").write_bytes(b"data")
            with self.assertRaises(ValueError):
                verifier.verify_exact_regular_files(root, {expected})

    def test_module_properties_reject_duplicates(self) -> None:
        with self.assertRaises(ValueError):
            verifier.parse_properties(b"id=one\nid=two\n")
        self.assertEqual(
            {"id": "one", "version": "1"},
            verifier.parse_properties(b"id=one\nversion=1\n"),
        )

    def test_internal_shell_hash_pins_must_match_payloads(self) -> None:
        expected = "a" * 64
        matching = verifier.parse_shell_hashes(
            f"EXPECTED_PATCHER_SHA256={expected}\n".encode()
        )
        self.assertEqual(expected, matching["EXPECTED_PATCHER_SHA256"])
        with self.assertRaises(ValueError):
            verifier.parse_shell_hashes(
                (
                    f"EXPECTED_PATCHER_SHA256={expected}\n"
                    f"EXPECTED_PATCHER_SHA256={expected}\n"
                ).encode()
            )

    def test_single_package_path_guard_rejects_first_match_logic(self) -> None:
        valid = b'''single_package_path() {
[ "$package_count" -eq 1 ] || return 1
[ "$package_count" -eq 1 ] && [ -n "$package_path" ] || return 1
}
'''
        verifier.verify_single_package_path_guard(valid, "valid")
        unsafe = valid.replace(b'-eq 1 ] || return 1', b'-ge 1 ] || return 1', 1)
        with self.assertRaises(ValueError):
            verifier.verify_single_package_path_guard(unsafe, "unsafe")

    def test_task_broker_requires_the_verified_apk_argument(self) -> None:
        valid = (
            b'com.android.quickstep.RootTaskBridgeServer "$MODDIR" "$bridge_apk"\n'
        )
        verifier.verify_task_broker_apk_pinning(valid, "valid")
        unsafe = b'com.android.quickstep.RootTaskBridgeServer "$MODDIR"\n'
        with self.assertRaises(ValueError):
            verifier.verify_task_broker_apk_pinning(unsafe, "unsafe")

    def test_quickstep_resolution_requires_exactly_one_component(self) -> None:
        valid = b'''single_quickstep_component() {
[ "$quickstep_count" -eq 1 ] || return 1
[ "$quickstep_count" -eq 1 ] && [ -n "$quickstep_component" ] || return 1
}
active_component=$(single_quickstep_component) || active_component=
resolved_component=$(single_quickstep_component) || resolved_component=
'''
        verifier.verify_single_quickstep_component_guard(valid, "valid")
        unsafe = valid.replace(
            b'[ "$quickstep_count" -eq 1 ] || return 1',
            b'[ "$quickstep_count" -ge 1 ] || return 1',
        )
        with self.assertRaises(ValueError):
            verifier.verify_single_quickstep_component_guard(unsafe, "unsafe")

    def test_home_uninstall_restarts_after_supported_target_verification(self) -> None:
        patch_hash = "a" * 64
        stock_hash = "c" * 64
        valid = f'''EXPECTED_STOCK_SHA256={stock_hash}
EXPECTED_PATCH_SHA256={patch_hash}
HOME_PACKAGE=com.example.home
UNMOUNTED=0
[ -x "$TIMEOUT" ] && [ -x "$NSENTER" ] || exit 91
home_pid() {{
    home_name=$(tr '\\000' '\\n' < "/proc/$home_candidate/cmdline")
    [ "$home_name" = "$HOME_PACKAGE" ] || continue
}}
case "$mounted_hash" in
    "$EXPECTED_PATCH_SHA256")
        umount "$TARGET_APK" || exit 92
        UNMOUNTED=1
        [ "$restored_hash" = "$EXPECTED_STOCK_SHA256" ] || exit 93
        ;;
    "$EXPECTED_STOCK_SHA256") ;;
    "") exit 94 ;;
    *) exit 95 ;;
esac
RESTART_REQUIRED=$UNMOUNTED
[ "$(getprop sys.boot_completed)" = 1 ] && RESTART_REQUIRED=1
if [ "$RESTART_REQUIRED" = 1 ]; then
old_home_pid=$(home_pid) || old_home_pid=
am force-stop "$HOME_PACKAGE"
new_home_pid=$(home_pid) || new_home_pid=
[ "$new_home_pid" != "$old_home_pid" ]
[ "$new_home_pid" != "$old_home_pid" ]
fi
'''.encode()
        verifier.verify_fail_closed_uninstall(
            valid, patch_hash, stock_hash, "valid-home"
        )
        unsafe = valid.replace(
            b'case "$mounted_hash" in',
            b'am force-stop "$HOME_PACKAGE"\ncase "$mounted_hash" in',
            1,
        ).replace(
            b'if [ "$RESTART_REQUIRED" = 1 ]; then\nold_home_pid=$(home_pid) || old_home_pid=\nam force-stop "$HOME_PACKAGE"\n',
            b'if [ "$RESTART_REQUIRED" = 1 ]; then\nold_home_pid=$(home_pid) || old_home_pid=\n',
            1,
        )
        with self.assertRaises(ValueError):
            verifier.verify_fail_closed_uninstall(
                unsafe, patch_hash, stock_hash, "unsafe-home"
            )

    def test_systemui_uninstall_stops_broker_before_gated_process_restart(self) -> None:
        patch_hash = "b" * 64
        stock_hash = "d" * 64
        valid = f'''EXPECTED_STOCK_SHA256={stock_hash}
EXPECTED_PATCH_SHA256={patch_hash}
SYSTEMUI_PACKAGE=com.android.systemui
UNMOUNTED=0
stop_task_bridge() {{ :; }}
systemui_pid() {{
    systemui_name=$(tr '\\000' '\\n' < "/proc/$systemui_candidate/cmdline")
    [ "$systemui_name" = "$SYSTEMUI_PACKAGE" ] || continue
}}
[ -x "$TIMEOUT" ] && [ -x "$NSENTER" ] || exit 91
case "$mounted_hash" in
    "$EXPECTED_PATCH_SHA256")
        umount "$TARGET_APK" || exit 92
        UNMOUNTED=1
        [ "$restored_hash" = "$EXPECTED_STOCK_SHA256" ] || exit 93
        ;;
    "$EXPECTED_STOCK_SHA256") ;;
    "") exit 94 ;;
    *) exit 95 ;;
esac
stop_task_bridge
RESTART_REQUIRED=$UNMOUNTED
[ "$(getprop sys.boot_completed)" = 1 ] && RESTART_REQUIRED=1
if [ "$RESTART_REQUIRED" = 1 ]; then
old_systemui_pid=$(systemui_pid) || old_systemui_pid=
kill -TERM "$old_systemui_pid"
new_systemui_pid=$(systemui_pid) || new_systemui_pid=
[ "$new_systemui_pid" != "$old_systemui_pid" ]
[ "$new_systemui_pid" != "$old_systemui_pid" ]
fi
'''.encode()
        verifier.verify_fail_closed_uninstall(
            valid, patch_hash, stock_hash, "valid-systemui"
        )
        unsafe = valid.replace(
            b'esac\nstop_task_bridge\nRESTART_REQUIRED',
            b'esac\nRESTART_REQUIRED',
            1,
        )
        with self.assertRaises(ValueError):
            verifier.verify_fail_closed_uninstall(
                unsafe,
                patch_hash,
                stock_hash,
                "unsafe-systemui",
            )

    def test_activation_lock_requires_flock_and_releases_its_fd(self) -> None:
        valid = b'''FLOCK=/system/bin/flock
LOCK_FILE=/data/adb/.scr01_scroot_menu.activation.lock
LOCK_HELD=0
[ ! -L "$LOCK_FILE" ] || return 1
[ ! -e "$LOCK_FILE" ] || [ -f "$LOCK_FILE" ] || return 1
: >> "$LOCK_FILE" || return 1
exec 0<> "$LOCK_FILE" || return 1
while ! "$FLOCK" -n 0; do
exec 0< /dev/null
done
LOCK_HELD=1
"$FLOCK" -u 0 2>/dev/null || true
exec 0< /dev/null
LOCK_HELD=0
if ! acquire_activation_lock; then
    exit 90
fi
'''
        verifier.verify_atomic_activation_lock(
            valid,
            "valid",
            False,
            "/data/adb/.scr01_scroot_menu.activation.lock",
        )
        unsafe = valid.replace(b'while ! "$FLOCK" -n 0; do', b'while true; do')
        with self.assertRaises(ValueError):
            verifier.verify_atomic_activation_lock(
                unsafe,
                "unsafe",
                False,
                "/data/adb/.scr01_scroot_menu.activation.lock",
            )
        unsafe_success = valid.replace(b"    exit 90", b"    exit 0")
        with self.assertRaises(ValueError):
            verifier.verify_atomic_activation_lock(
                unsafe_success,
                "unsafe-success",
                False,
                "/data/adb/.scr01_scroot_menu.activation.lock",
            )
        unsafe_special = valid.replace(
            b'[ ! -e "$LOCK_FILE" ] || [ -f "$LOCK_FILE" ] || return 1\n',
            b'',
            1,
        )
        with self.assertRaises(ValueError):
            verifier.verify_atomic_activation_lock(
                unsafe_special,
                "unsafe-special-file",
                False,
                "/data/adb/.scr01_scroot_menu.activation.lock",
            )

    def test_task_broker_does_not_inherit_the_activation_lock(self) -> None:
        base = b'''FLOCK=/system/bin/flock
LOCK_FILE=/data/adb/.scr01_overview_bridge.activation.lock
LOCK_HELD=0
[ ! -L "$LOCK_FILE" ] || return 1
[ ! -e "$LOCK_FILE" ] || [ -f "$LOCK_FILE" ] || return 1
: >> "$LOCK_FILE" || return 1
exec 0<> "$LOCK_FILE" || return 1
while ! "$FLOCK" -n 0; do
exec 0< /dev/null
done
LOCK_HELD=1
"$FLOCK" -u 0 2>/dev/null || true
exec 0< /dev/null
LOCK_HELD=0
if ! acquire_activation_lock; then
    exit 90
fi
'''
        valid = base + b'</dev/null >> "$TASK_BRIDGE_LOG" 2>&1 &\n'
        verifier.verify_atomic_activation_lock(
            valid,
            "valid-broker",
            True,
            "/data/adb/.scr01_overview_bridge.activation.lock",
        )
        with self.assertRaises(ValueError):
            verifier.verify_atomic_activation_lock(
                base,
                "unsafe-broker",
                True,
                "/data/adb/.scr01_overview_bridge.activation.lock",
            )

    def test_overview_upgrade_allowlist_requires_every_signed_predecessor(self) -> None:
        valid = "\n".join(
            f'{key}={value}\ncase "$installed_hash" in "${key}") ;; esac'
            for key, value in verifier.EXPECTED_OVERVIEW_PREDECESSORS.items()
        ).encode()
        verifier.verify_overview_upgrade_allowlist(valid, "valid")
        missing = valid.replace(b'"$EXPECTED_BRIDGE_041_SHA256"', b'"missing"')
        with self.assertRaises(ValueError):
            verifier.verify_overview_upgrade_allowlist(missing, "missing")

    def test_home_upgrade_allowlist_requires_every_known_predecessor(self) -> None:
        valid = "\n".join(sorted(verifier.EXPECTED_HOME_PREDECESSORS)).encode()
        verifier.verify_home_upgrade_allowlist(valid, "valid")
        missing_hash = next(iter(verifier.EXPECTED_HOME_PREDECESSORS))
        missing = valid.decode().replace(missing_hash, "", 1).encode()
        with self.assertRaises(ValueError):
            verifier.verify_home_upgrade_allowlist(missing, "missing")

    def test_home_watchdog_requires_process_identity_and_two_stability_windows(self) -> None:
        valid = b'''home_process_ready() {
dumpsys activity processes "$HOME_PACKAGE"
mHomeProcess: ProcessRecord{.* $expected_pid:$HOME_PACKAGE/
thread=android.app.IApplicationThread
crashing=true
}
if ! home_process_ready "$home_pid"; then
fi
if ! home_process_ready "$home_pid"; then
fi
'''
        verifier.verify_home_runtime_watchdog(valid, "valid")
        unsafe = valid.replace(b'crashing=true', b'crashing=false')
        with self.assertRaises(ValueError):
            verifier.verify_home_runtime_watchdog(unsafe, "unsafe")

    def test_valid_minimal_archive(self) -> None:
        module = b"id=test\nversion=1\n"
        script = b"#!/bin/sh\nexit 0\n"
        expected = {"module.prop": digest(module), "service.sh": digest(script)}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "module.zip"
            with zipfile.ZipFile(path, "w") as archive:
                add_entry(archive, "module.prop", module)
                add_entry(archive, "service.sh", script, stat.S_IFREG | 0o755)
            verifier.verify_zip(path, "test", "1", expected)

    def test_archive_rejects_case_collisions(self) -> None:
        module = b"id=test\nversion=1\n"
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "module.zip"
            with zipfile.ZipFile(path, "w") as archive:
                add_entry(archive, "module.prop", module)
                add_entry(archive, "MODULE.PROP", module)
            with self.assertRaises(ValueError):
                verifier.verify_zip(path, "test", "1", {"module.prop": digest(module)})

    def test_archive_rejects_traversal(self) -> None:
        module = b"id=test\nversion=1\n"
        payload = b"payload"
        expected = {"module.prop": digest(module), "../payload": digest(payload)}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "module.zip"
            with zipfile.ZipFile(path, "w") as archive:
                add_entry(archive, "module.prop", module)
                add_entry(archive, "../payload", payload)
            with self.assertRaises(ValueError):
                verifier.verify_zip(path, "test", "1", expected)

    def test_archive_rejects_symlinks(self) -> None:
        module = b"id=test\nversion=1\n"
        target = b"module.prop"
        expected = {"module.prop": digest(module), "link": digest(target)}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "module.zip"
            with zipfile.ZipFile(path, "w") as archive:
                add_entry(archive, "module.prop", module)
                add_entry(archive, "link", target, stat.S_IFLNK | 0o777)
            with self.assertRaises(ValueError):
                verifier.verify_zip(path, "test", "1", expected)

    def test_archive_rejects_unexpected_directories(self) -> None:
        module = b"id=test\nversion=1\n"
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "module.zip"
            with zipfile.ZipFile(path, "w") as archive:
                add_entry(archive, "module.prop", module)
                add_entry(archive, "unused/", b"", stat.S_IFDIR | 0o755)
            with self.assertRaises(ValueError):
                verifier.verify_zip(path, "test", "1", {"module.prop": digest(module)})

    def test_archive_rejects_internal_hash_pin_mismatch(self) -> None:
        module = b"id=test\nversion=1\n"
        patcher = b"patcher"
        delta = b"delta"
        patcher_hash = digest(patcher)
        delta_hash = digest(delta)
        valid_script = (
            f"EXPECTED_PATCHER_SHA256={patcher_hash}\n"
            f"EXPECTED_DELTA_SHA256={delta_hash}\n"
        ).encode()
        invalid_script = valid_script.replace(patcher_hash.encode(), b"0" * 64)
        expected = {
            "module.prop": digest(module),
            "bin/scbspatch": patcher_hash,
            "patch/MHSHome.bsdiff": delta_hash,
            "customize.sh": digest(invalid_script),
            "boot-completed.sh": digest(valid_script),
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "module.zip"
            with zipfile.ZipFile(path, "w") as archive:
                add_entry(archive, "module.prop", module)
                add_entry(archive, "bin/", b"", stat.S_IFDIR | 0o755)
                add_entry(archive, "bin/scbspatch", patcher, stat.S_IFREG | 0o755)
                add_entry(archive, "patch/", b"", stat.S_IFDIR | 0o755)
                add_entry(archive, "patch/MHSHome.bsdiff", delta)
                add_entry(archive, "customize.sh", invalid_script, stat.S_IFREG | 0o755)
                add_entry(archive, "boot-completed.sh", valid_script, stat.S_IFREG | 0o755)
            with self.assertRaises(ValueError):
                verifier.verify_zip(path, "test", "1", expected)

    def test_current_android_manifest_has_the_expected_attack_surface(self) -> None:
        verifier.verify_manifest(verifier.MANIFEST)

    def test_android_manifest_rejects_an_exported_privileged_service(self) -> None:
        source = verifier.MANIFEST.read_text("utf-8")
        unsafe = source.replace(
            'android:name=".AutoRootService"\n            android:enabled="true"\n            android:exported="false"',
            'android:name=".AutoRootService"\n            android:enabled="true"\n            android:exported="true"',
        )
        self.assertNotEqual(source, unsafe)
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / "AndroidManifest.xml"
            manifest.write_text(unsafe, "utf-8")
            with self.assertRaises(ValueError):
                verifier.verify_manifest(manifest)

    def test_android_manifest_rejects_stackable_launcher_activity(self) -> None:
        source = verifier.MANIFEST.read_text("utf-8")
        unsafe = source.replace(
            'android:exported="true"\n            android:launchMode="singleTask"',
            'android:exported="true"',
            1,
        )
        self.assertNotEqual(source, unsafe)
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / "AndroidManifest.xml"
            manifest.write_text(unsafe, "utf-8")
            with self.assertRaises(ValueError):
                verifier.verify_manifest(manifest)

    def test_current_gradle_application_identity_is_release_safe(self) -> None:
        verifier.verify_gradle_application(verifier.BUILD_GRADLE)

    def test_gradle_application_rejects_an_identity_change(self) -> None:
        source = verifier.BUILD_GRADLE.read_text("utf-8")
        unsafe = source.replace(
            'applicationId "com.scr01.scroot"',
            'applicationId "com.scr01.lookalike"',
        )
        self.assertNotEqual(source, unsafe)
        with tempfile.TemporaryDirectory() as temporary:
            build_file = Path(temporary) / "build.gradle"
            build_file.write_text(unsafe, "utf-8")
            with self.assertRaises(ValueError):
                verifier.verify_gradle_application(build_file)

    def test_root_flow_requires_all_exact_package_split_guards(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            "if (!hasNoSplitApks(appInfo.splitSourceDirs))",
            "if (false)",
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_rejects_ambiguous_overview_package_paths(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            '[ \\"\\$count\\" -eq 1 ] || return 1;',
            '[ \\"\\$count\\" -ge 1 ] || return 1;',
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_requires_live_overview_health(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            'brokerReady = health.getBoolean("broker_ready", false)',
            "brokerReady = true",
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_pins_exact_live_ui_build_identities(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        stale_overview = source.replace(
            'private const val OVERVIEW_HEALTH_BUILD_ID = "scroverview-0.4.7"',
            'private const val OVERVIEW_HEALTH_BUILD_ID = "scroverview-0.4.6"',
            1,
        )
        stale_home = source.replace(
            'private const val HOME_HEALTH_BUILD_ID = "scr01-home-1.7.27"',
            'private const val HOME_HEALTH_BUILD_ID = "scr01-home-1.7.26"',
            1,
        )
        self.assertNotEqual(source, stale_overview)
        self.assertNotEqual(source, stale_home)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(stale_overview)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(stale_home)

    def test_root_flow_verifies_live_health_before_reporting_ui_success(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            "if (!verifyProvisionedSystemUiLive(ctx)) {",
            "if (false) {",
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_health_ipc_never_retains_an_activity_context(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            "appContext.contentResolver.call(",
            "ctx.contentResolver.call(",
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_repairs_incomplete_live_health_before_receipt_commit(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            "if (!recoverProvisionedSystemUiLive(health, log) ||",
            "if (false ||",
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_retries_only_safe_ui_activation_failures(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            "if (!ready && provisionFailureMayUseLiveRecovery(result.rc, result.timedOut)) {",
            "if (!ready) {",
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_silences_broker_pid_exit_races(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(r"name=\$({ tr ", r"name=\$(tr ", 1)
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_verifies_committed_receipt_bytes(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            "Files.readAllBytes(target.toPath()).contentEquals(expectedBytes)",
            "true",
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_module_lock_observer_is_read_only(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            'read -r pid recorded_start < \\"\\$lock/pid\\" 2>/dev/null || return 1;',
            'read -r pid recorded_start < \\"\\$lock/pid\\" 2>/dev/null || { rm -f \\"\\$lock/pid\\"; return 1; };',
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_scans_active_and_staged_module_locks(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            "for base in /data/adb/modules_update /data/adb/modules; do for id in",
            "for base in /data/adb/modules_update; do for id in",
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_scans_shared_locks_before_legacy_module_locks(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            'for lock in \\"\\$OVERVIEW_LOCK\\" \\"\\$HOME_LOCK\\"; do',
            'for lock in; do',
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_detects_kernel_advisory_module_locks(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            'if \\"\\$FLOCK\\" -n 0;',
            'if true;',
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_pins_active_uninstall_promotion(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            '[ ! -L \\"\\$active\\" ] || return 1;',
            ':;',
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_promotes_only_the_exact_staged_module_version(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            'module_exact \\"\\$OVERVIEW_ID\\" \\"\\$OVERVIEW_VERSION\\" && overview_integrity;',
            'overview_integrity;',
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_rejects_disabled_or_removed_active_and_staged_modules(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            "for marker in disable remove; do",
            "for marker in; do",
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_rechecks_module_state_after_install(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        install_line = next(
            line for line in source.splitlines() if 'append("install_exact() {' in line
        )
        unsafe_line = install_line.replace(
            'module_not_blocked \\"\\$id\\" && module_exact',
            "module_exact",
            1,
        )
        self.assertNotEqual(install_line, unsafe_line)
        unsafe = source.replace(install_line, unsafe_line, 1)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_rechecks_module_state_before_live_recovery(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            'verify_boot() { id=\\$1; version=\\$2; expected=\\$3; module_not_blocked \\"\\$id\\" || return 1;',
            'verify_boot() { id=\\$1; version=\\$2; expected=\\$3;',
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_rechecks_module_state_before_activation_scripts(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_security_invariants(source)
        unsafe = source.replace(
            'append("module_exact \\"\\$OVERVIEW_ID\\" \\"\\$OVERVIEW_VERSION\\" && overview_integrity || exit 55\\n")',
            'append(":\\n")',
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_security_invariants(unsafe)

    def test_root_flow_generated_shell_has_valid_syntax(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_root_flow_generated_shell_syntax(source)
        unsafe = source.replace(
            'append("ui_active() { overview_active && home_active; }\\n")',
            'append("ui_active() { if then; }\\n")',
            1,
        )
        self.assertNotEqual(source, unsafe)
        with self.assertRaises(ValueError):
            verifier.verify_root_flow_generated_shell_syntax(unsafe)

    def test_root_flow_pins_exact_metamodule_and_manager_paths(self) -> None:
        source = verifier.ROOT_FLOW.read_text("utf-8")
        verifier.verify_meta_module_invariants(source)
        unsafe_meta = source.replace(
            "grep -qx \\\"id=\\$META_ID\\\" \\\"\\$dir/module.prop\\\"",
            "grep -q \\\"\\$META_ID\\\" \\\"\\$dir/module.prop\\\"",
            1,
        )
        unsafe_manager = source.replace(
            "case \\\"\\$INSTALLED_COUNT\\\" in 1)",
            "case \\\"\\$INSTALLED_COUNT\\\" in *)",
            1,
        )
        self.assertNotEqual(source, unsafe_meta)
        self.assertNotEqual(source, unsafe_manager)
        with self.assertRaises(ValueError):
            verifier.verify_meta_module_invariants(unsafe_meta)
        with self.assertRaises(ValueError):
            verifier.verify_meta_module_invariants(unsafe_manager)


if __name__ == "__main__":
    unittest.main()
