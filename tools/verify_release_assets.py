#!/usr/bin/env python3

import hashlib
import json
import re
import stat
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
NATIVE = ROOT / "app/src/main/jniLibs/arm64-v8a"
ROOT_FLOW = ROOT / "app/src/main/java/com/scr01/scroot/RootFlow.kt"
MAIN_ACTIVITY = ROOT / "app/src/main/java/com/scr01/scroot/MainActivity.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
BUILD_GRADLE = ROOT / "app/build.gradle"
ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
ANDROID = f"{{{ANDROID_NAMESPACE}}}"
MAX_ZIP_ENTRY_BYTES = 128 * 1024 * 1024
MAX_ZIP_TOTAL_BYTES = 256 * 1024 * 1024
MAX_ZIP_ENTRIES = 64
MAX_TOP_LEVEL_BYTES = 64 * 1024 * 1024
EXPECTED_OVERVIEW_PREDECESSORS = {
    "EXPECTED_BRIDGE_040_SHA256": "e3f4c16f9f0ca502a0dc0ba738f553a7a7f02b675c9dcd4ed91cc3b9772cd552",
    "EXPECTED_BRIDGE_041_SHA256": "989017ec4a7bd65b6144931d1da17c08bdb950a291397f7bed9438e207ddbdd4",
    "EXPECTED_BRIDGE_042_SHA256": "a51929e36c79a39aba9974772a064d5a3f3ae8738c2231e13f21e1f5cebe2e0c",
    "EXPECTED_BRIDGE_043_SHA256": "8b7546c4d18e5068429d184fdc91bb662c1e9cbb0c6b41ef7d23b11d7c0d3e27",
    "EXPECTED_BRIDGE_044_SHA256": "3cf8c51f9a9152e01f119afed41006123e0cd659fe087ba874c50209557b3743",
    "EXPECTED_BRIDGE_045_SHA256": "4ee9190fb610fc32978dab0433604338bd46f132a6e653f3196ba2d44f9e9db2",
    "EXPECTED_BRIDGE_046_SHA256": "022c46ac3094848da36022994bc1dfedb991f34a0db84a6595c8dfeb5fe25496",
}
EXPECTED_HOME_PREDECESSORS = {
    "8a4362a3012ea44b1a50e3d29b3cebf9f6e16bd2e7b91e20585538219af0283c",
    "524007eeda1460e4df014e377b7e9bb57e6b316a0545fed9ccfc173e3ac0a024",
    "1b6584a705949f592964dc027b2414e7af59ef156748ac93868ad6af5cf9d880",
    "ce7659450509b47458eaaeb0c46dfe3157c6d11cc18f1f2b144c2d16b0d4928e",
    "32cca4a9b41cdbe38bb136decfec6b46b76d6a628613d6a755f0455631eb0d10",
    "ccbf69decb7ffde2bbe768f52aed90945b30069f6a9c952349cded3ed1d26f45",
    "fbea1f8f8bc3d27060baa1fc9aaada8926933cf0f74ff5f147fbb0eba7747c6b",
    "0c9f76b68adde3d14ac54d653b20405e8c71728c247dd6842ad149bea724636a",
    "ab1803ea431d5b1c04241264cb739df9e94da31fe7a5a096a6226158ea0c67f2",
    "ad9ad032d08e5023eb6b51ba71c740e822d0efe83d168e29fb71fec364c71fa8",
    "031554099fd69d95ef5bb3580721bed26e2a4cef06d3a9487006414a2397e4bf",
    "dcf7d3966caf426893270e669b0e9ecf760e8d956b8d9542c263fc341b5636c9",
    "afd5529ec9b469484d56c35ae262d4ecfabfc8f6537fa60fdf109675dc09ea60",
    "abb5ae74eb28fa535e44128df1b66865d4788795cb3ee925c04df0a179c1e912",
    "2fa44dca4260679ebbf1f8e661ed2444d677b232b32c300a83c6ba5e8145488c",
    "77b3c5617775a3df239a2ffdeb2e90282b292b8020d8a6f53216cae200e16c18",
}

TOP_LEVEL = {
    ASSETS / "manager.apk": "80a9e4b1ba9644f361add3e003e1075bd4f9cb374bbde465c2b57522b5288ba9",
    ASSETS / "meta-overlayfs-scr01.zip": "df4b4a33c9974eb873e62ad01fa7229e9648cc848d032f6192a89b055cb9528c",
    ASSETS / "scr01-home-ui-1.7.28.zip": "0f2e8d8ec02b8ab78fc41f3a32df126d282a2f78242d1dd4f9bf85b33d06cc05",
    ASSETS / "scr01-overview-bridge-0.4.36.zip": "a3bcae90900fd02436108b8644fb5aa9e247816b98fb8fbe4f0c9ee81ce45981",
    NATIVE / "libadbroot.so": "5562adc1e5c6f52fb91f469a1a7d3480050d8697fb5dedbd7fb386d282cd88b8",
    NATIVE / "libbootstrap.so": "e81028efeaf44aba81607aa6116cc1273ad8f4d4eee4ec58ff5e14be061fca90",
    NATIVE / "libexploit.so": "86a8a98029e913b16abf5480bcecf8fe036cb0339de9c7b91dc5c0749898dece",
    NATIVE / "libksucheck.so": "0abb36169ff0864ee659e5b40f7666b01cf36d765e3216d9a4d14695f92817d6",
    NATIVE / "libksud.so": "637676421190aeec504093707ad675a45faaf11bd4b53129d52e150490902cca",
    NATIVE / "libksuglue.so": "b1f6b9afbbfc2f6c388dada781f0761899f494bc6f05e8657fa4325b5a0cbfd9",
    NATIVE / "libmemprep.so": "a743f6c5432ec8072faa1bc47e07d8811135905eb3e458e8f4d892b192cadcee",
    NATIVE / "librootsh.so": "77cfc2e1e8fd710cb18b442f950a76b63e0e0f30214c1131aed49d8ff85edc04",
}

ZIP_CONTENTS = {
    ASSETS / "meta-overlayfs-scr01.zip": {
        "customize.sh": "5a63c2e566c1a5b449ba4cb34eae2d60f25530a4fc589c5a4d70f201849229b7",
        "meta-overlayfs-aarch64": "04ef286eb33dd6650be08c368cc16d06e369aeae2c582f8df520d0a7dcc2fa95",
        "metainstall.sh": "d37ff93360bb4e88f76fec4d7587b663e312c324727c351bfc715b4d9cfb8aff",
        "metamount.sh": "96fa80c084d298cd0707531a07b6920264c5635295ae5d95f34a6ffc7f959254",
        "metauninstall.sh": "0421cf45ab6b1fb51c7c2b1c8a93a29976c967ab8b0bbfadec1a45fbb66475ec",
        "module.prop": "a5af76462173be19dbf945e1c901f651c8c1794b23b2e78a7b78a42a72612e1c",
        "post-mount.sh": "cc9e198f97fa4505d5cfc5e04265a8bae9644206576ef73917de95d22d55fce3",
        "uninstall.sh": "cc0e4342a39f8da1dadf5e96f6b0da1884e7bb10de670cbcb74d4bd4ed3e7e49",
    },
    ASSETS / "scr01-home-ui-1.7.28.zip": {
        "bin/scbspatch": "50688c893eab8fcf73277590f1b0486f1ba4a63431f568296ddbd864864d3089",
        "boot-completed.sh": "b6f19382ca21206ed706c5b8fb6a1b7f608e0e7cc7b4f9f1acf0d87f9272be04",
        "customize.sh": "59a66916c4ea6111f9376e1183da8a46be5ca30d7d5044ed79bfcb70715ba5b9",
        "module.prop": "dc377eb86b02f8d94a95b264e81bbd957d2e10f9b9a9ac19fe25b1a259257f1f",
        "patch/MHSHome.bsdiff": "18fb9cd306d221841c322d29470d4d0a9efd1be984e008e679e4d3868f581cbb",
        "skip_mount": "139d0bf2668b9eb865fcb2f6dd4e8a48a2c379cebee8e87120cfcb0e064f8afb",
        "uninstall.sh": "de012db3f34aa294b9bad714f4e022fb1fc7395202a507864ea13b8c4b8784ab",
    },
    ASSETS / "scr01-overview-bridge-0.4.36.zip": {
        "app/SCROverview.apk": "e21c90ee1c70c0a3134f532e179bef1cdaaa47105f63fbce4ca850596607258d",
        "bin/scbspatch": "50688c893eab8fcf73277590f1b0486f1ba4a63431f568296ddbd864864d3089",
        "boot-completed.sh": "a67129119b1bcdc31f3ca6ae71bd366a1db42244aa082e94f98a39d43e2c3f60",
        "customize.sh": "7a18262521d8faa66a696b107636cfb1dd81b75225f932bbc668a151c6ec98f4",
        "module.prop": "be1dd7ed27d90e492330ba266ae7fb272fd938a627b6dedde8e6f3f68a678208",
        "patch/SystemUI.bsdiff": "9d7a2260854ca8f5562e15392a1c3c6939775e8a9c5b2aab6409cb1b6799a5f3",
        "skip_mount": "c8215616bd942c95d0f03856107f1861402a601d487444a5fdb1817732a4da5b",
        "uninstall.sh": "efacf50f885aa55428fdbd0bc758019356d673ad8e843603f25e2ecaf5bfc3d4",
    },
}

MODULES = {
    ASSETS / "meta-overlayfs-scr01.zip": ("meta-overlayfs", "1.3.1-scr01.3"),
    ASSETS / "scr01-home-ui-1.7.28.zip": ("scr01_scroot_menu", "1.7.28"),
    ASSETS / "scr01-overview-bridge-0.4.36.zip": ("scr01_overview_bridge", "0.4.36"),
}

EXPECTED_PERMISSIONS = {
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.WAKE_LOCK",
    "android.permission.USE_FULL_SCREEN_INTENT",
    "com.scr01.scroot.permission.HOME_HEALTH",
}

EXPECTED_DECLARED_PERMISSIONS = {
    "com.scr01.scroot.permission.HOME_HEALTH": "signature",
}

EXPECTED_QUERIES = {
    "com.rifsxd.ksunext",
    "com.sec.android.app.launcher",
}

EXPECTED_COMPONENTS = {
    ("activity", ".MainActivity"): "true",
    ("activity", ".BootTraceActivity"): "false",
    ("receiver", ".AutoRootReceiver"): "false",
    ("service", ".AutoRootService"): "false",
    ("service", ".ManualFlowGuardService"): "false",
}

EXPECTED_DIRECT_BOOT_COMPONENTS = {
    ("activity", ".BootTraceActivity"),
    ("receiver", ".AutoRootReceiver"),
    ("service", ".AutoRootService"),
}

EXPECTED_GRADLE_VALUES = {
    "namespace": "com.scr01.scroot",
    "applicationId": "com.scr01.scroot",
    "compileSdk": "34",
    "minSdk": "30",
    "targetSdk": "30",
    "versionCode": "3",
    "versionName": "1.1.1",
}

def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def require_regular_file(path: Path, maximum_bytes: int = MAX_TOP_LEVEL_BYTES) -> None:
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        raise FileNotFoundError(path) from None
    if not stat.S_ISREG(metadata.st_mode):
        raise ValueError(f"not a regular file: {path}")
    if metadata.st_size <= 0 or metadata.st_size > maximum_bytes:
        raise ValueError(f"invalid file size: {path}")

def verify_exact_regular_files(directory: Path, expected: set[Path]) -> None:
    try:
        metadata = directory.lstat()
    except FileNotFoundError:
        raise FileNotFoundError(directory) from None
    if not stat.S_ISDIR(metadata.st_mode):
        raise ValueError(f"not a directory: {directory}")
    actual = set(directory.iterdir())
    if actual != expected:
        missing = sorted(path.name for path in expected - actual)
        unexpected = sorted(path.name for path in actual - expected)
        raise ValueError(f"directory content mismatch: {directory} missing={missing} unexpected={unexpected}")
    for path in expected:
        require_regular_file(path)

def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()

def parse_properties(data: bytes) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in data.decode("utf-8").splitlines():
        if not raw or raw.startswith("#"):
            continue
        key, separator, value = raw.partition("=")
        if not separator or not key or key in result:
            raise ValueError("invalid or duplicate module property")
        result[key] = value
    return result

def parse_shell_hashes(data: bytes) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in data.decode("utf-8").splitlines():
        match = re.fullmatch(r"([A-Z][A-Z0-9_]*)=([0-9a-f]{64})", raw)
        if match is None:
            continue
        key, value = match.groups()
        if key in result:
            raise ValueError(f"duplicate shell hash constant: {key}")
        result[key] = value
    return result

def verify_internal_hash_pins(
    path: Path,
    archive: zipfile.ZipFile,
    expected_contents: dict[str, str],
) -> None:
    targets: dict[str, str] = {}
    if "bin/scbspatch" in expected_contents:
        targets["EXPECTED_PATCHER_SHA256"] = expected_contents["bin/scbspatch"]
    for delta_name in ("patch/MHSHome.bsdiff", "patch/SystemUI.bsdiff"):
        if delta_name in expected_contents:
            targets["EXPECTED_DELTA_SHA256"] = expected_contents[delta_name]
    if "app/SCROverview.apk" in expected_contents:
        targets["EXPECTED_BRIDGE_SHA256"] = expected_contents["app/SCROverview.apk"]
    if not targets:
        return
    for script_name in ("customize.sh", "boot-completed.sh"):
        constants = parse_shell_hashes(archive.read(script_name))
        for key, expected in targets.items():
            if constants.get(key) != expected:
                raise ValueError(f"internal hash pin mismatch: {path.name}:{script_name}:{key}")

def verify_single_package_path_guard(data: bytes, label: str) -> None:
    source = data.decode("utf-8")
    required = (
        "single_package_path() {",
        '[ "$package_count" -eq 1 ] || return 1',
        '[ "$package_count" -eq 1 ] && [ -n "$package_path" ] || return 1',
    )
    if any(source.count(fragment) != 1 for fragment in required):
        raise ValueError(f"package-path cardinality guard mismatch: {label}")

def verify_single_quickstep_component_guard(data: bytes, label: str) -> None:
    source = data.decode("utf-8")
    required = (
        "single_quickstep_component() {",
        '[ "$quickstep_count" -eq 1 ] || return 1',
        '[ "$quickstep_count" -eq 1 ] && [ -n "$quickstep_component" ] || return 1',
        "active_component=$(single_quickstep_component) || active_component=",
        "resolved_component=$(single_quickstep_component) || resolved_component=",
    )
    if any(source.count(fragment) != 1 for fragment in required):
        raise ValueError(f"Quickstep cardinality guard mismatch: {label}")
    if "head -n 1" in source:
        raise ValueError(f"Quickstep first-match resolution found: {label}")

def verify_task_broker_apk_pinning(data: bytes, label: str) -> None:
    source = data.decode("utf-8")
    class_name = "com.android.quickstep.RootTaskBridgeServer"
    pinned_invocation = class_name + ' "$MODDIR" "$bridge_apk"'
    if source.count(class_name) != 1 or source.count(pinned_invocation) != 1:
        raise ValueError(f"task broker APK pinning mismatch: {label}")

def verify_fail_closed_uninstall(
    data: bytes,
    expected_patch_hash: str,
    expected_stock_hash: str,
    label: str,
) -> None:
    source = data.decode("utf-8")
    constants = parse_shell_hashes(data)
    if constants.get("EXPECTED_PATCH_SHA256") != expected_patch_hash:
        raise ValueError(f"uninstall patch pin mismatch: {label}")
    if constants.get("EXPECTED_STOCK_SHA256") != expected_stock_hash:
        raise ValueError(f"uninstall stock pin mismatch: {label}")
    required_once = (
        'UNMOUNTED=0',
        'case "$mounted_hash" in',
        '"$EXPECTED_PATCH_SHA256")',
        'UNMOUNTED=1',
        '[ "$restored_hash" = "$EXPECTED_STOCK_SHA256" ] || exit 93',
        '"$EXPECTED_STOCK_SHA256") ;;',
        '") exit 94 ;;',
        '*) exit 95 ;;',
        'RESTART_REQUIRED=$UNMOUNTED',
        '[ "$(getprop sys.boot_completed)" = 1 ] && RESTART_REQUIRED=1',
        'if [ "$RESTART_REQUIRED" = 1 ]; then',
    )
    if any(source.count(fragment) != 1 for fragment in required_once):
        raise ValueError(f"fail-closed uninstall mismatch: {label}")
    target_case_at = source.find('case "$mounted_hash" in')
    transition_at = source.find("\nesac\n", target_case_at)
    restart_gate_at = source.find('if [ "$RESTART_REQUIRED" = 1 ]; then', transition_at)
    if transition_at < 0 or restart_gate_at <= transition_at:
        raise ValueError(f"uninstall transition guard mismatch: {label}")
    if source.count("|| exit 91") != 1 or source.count("exit 92") != 1:
        raise ValueError(f"uninstall runtime/unmount failure handling mismatch: {label}")
    if "HOME_PACKAGE=" in source:
        home_required = (
            "home_pid() {",
            '"/proc/$home_candidate/cmdline"',
            '[ "$home_name" = "$HOME_PACKAGE" ] || continue',
            'old_home_pid=$(home_pid) || old_home_pid=',
            'new_home_pid=$(home_pid) || new_home_pid=',
        )
        if any(source.count(fragment) != 1 for fragment in home_required):
            raise ValueError(f"Home process replacement mismatch: {label}")
        if source.count('[ "$new_home_pid" != "$old_home_pid" ]') < 2:
            raise ValueError(f"Home PID replacement proof mismatch: {label}")
        force_stop_at = source.find('am force-stop "$HOME_PACKAGE"')
        if force_stop_at <= restart_gate_at:
            raise ValueError(f"Home restart bypasses the boot-complete gate: {label}")
    if "SYSTEMUI_PACKAGE=" in source:
        systemui_required = (
            "systemui_pid() {",
            '"/proc/$systemui_candidate/cmdline"',
            '[ "$systemui_name" = "$SYSTEMUI_PACKAGE" ] || continue',
            'old_systemui_pid=$(systemui_pid) || old_systemui_pid=',
            'new_systemui_pid=$(systemui_pid) || new_systemui_pid=',
        )
        if any(source.count(fragment) != 1 for fragment in systemui_required):
            raise ValueError(f"SystemUI process replacement mismatch: {label}")
        if source.count('[ "$new_systemui_pid" != "$old_systemui_pid" ]') < 2:
            raise ValueError(f"SystemUI PID replacement proof mismatch: {label}")
        broker_stop_at = source.find("\nstop_task_bridge\n", transition_at)
        systemui_stop_at = source.find('kill -TERM "$old_systemui_pid"', transition_at)
        if not (transition_at < broker_stop_at < restart_gate_at < systemui_stop_at):
            raise ValueError(f"SystemUI cleanup ordering mismatch: {label}")

def verify_systemui_activation_ownership(data: bytes, label: str) -> None:
    source = data.decode("utf-8")
    required_once = (
        'TASK_BRIDGE_STARTED=1',
        '[ "$bridge_name" = "$TASK_BRIDGE_NAME" ] || return 1',
        'elif [ "$TASK_BRIDGE_STARTED" = 1 ] &&',
    )
    if any(source.count(fragment) != 1 for fragment in required_once):
        raise ValueError(f"SystemUI activation ownership mismatch: {label}")
    if source.count('TASK_BRIDGE_STARTED=0') != 2:
        raise ValueError(f"SystemUI broker cleanup ownership mismatch: {label}")
    activation = source.find("\nACTIVATION_STARTED=1\nnew_pid=$(restart_systemui)")
    mounted = source.find("\nmounted_hash=$(global_file_sha256 \"$TARGET_APK\")")
    if mounted < 0 or activation <= mounted:
        raise ValueError(f"SystemUI activation starts before mount verification: {label}")


def verify_atomic_activation_lock(
    data: bytes,
    label: str,
    inherits_long_lived_broker: bool,
    expected_lock_file: str,
) -> None:
    source = data.decode("utf-8")
    required_once = (
        "FLOCK=/system/bin/flock",
        f"LOCK_FILE={expected_lock_file}",
        '[ ! -e "$LOCK_FILE" ] || [ -f "$LOCK_FILE" ] || return 1',
        ': >> "$LOCK_FILE" || return 1',
        '[ ! -L "$LOCK_FILE" ] || return 1',
        'exec 0<> "$LOCK_FILE" || return 1',
        'while ! "$FLOCK" -n 0; do',
        '"$FLOCK" -u 0 2>/dev/null || true',
    )
    if any(source.count(fragment) != 1 for fragment in required_once):
        raise ValueError(f"atomic activation lock mismatch: {label}")
    if source.count("LOCK_HELD=0") != 2 or source.count("LOCK_HELD=1") != 1:
        raise ValueError(f"activation lock ownership mismatch: {label}")
    if source.count("exec 0< /dev/null") != 2:
        raise ValueError(f"activation lock fd release mismatch: {label}")
    if source.count("if ! acquire_activation_lock; then\n    exit 90\nfi") != 1:
        raise ValueError(f"activation lock failure is not fail-closed: {label}")
    forbidden = (
        'mkdir "$LOCK_DIR"',
        "write_activation_owner()",
        'rm -f "$LOCK_FILE"',
        'LOCK_FILE="$MODDIR/.activation.lock"',
        "migrate_legacy_activation_lock",
        '"$LOCK_FILE/pid"',
    )
    if any(fragment in source for fragment in forbidden):
        raise ValueError(f"legacy racy activation lock remains: {label}")
    broker_redirect = '</dev/null >> "$TASK_BRIDGE_LOG" 2>&1 &'
    if inherits_long_lived_broker:
        if source.count(broker_redirect) != 1:
            raise ValueError(f"task broker inherits activation lock fd: {label}")
    elif broker_redirect in source:
        raise ValueError(f"unexpected task broker redirection: {label}")

def verify_overview_upgrade_allowlist(data: bytes, label: str) -> None:
    source = data.decode("utf-8")
    constants = parse_shell_hashes(data)
    for key, expected in EXPECTED_OVERVIEW_PREDECESSORS.items():
        if constants.get(key) != expected or source.count(f'"${key}"') != 1:
            raise ValueError(f"Overview predecessor allowlist mismatch: {label}:{key}")

def verify_home_upgrade_allowlist(data: bytes, label: str) -> None:
    source = data.decode("utf-8")
    for expected in EXPECTED_HOME_PREDECESSORS:
        if source.count(expected) != 1:
            raise ValueError(f"Home predecessor allowlist mismatch: {label}:{expected}")

def verify_home_runtime_watchdog(data: bytes, label: str) -> None:
    source = data.decode("utf-8")
    required_once = (
        "home_process_ready() {",
        "dumpsys activity processes \"$HOME_PACKAGE\"",
        "mHomeProcess: ProcessRecord{.* $expected_pid:$HOME_PACKAGE/",
        "thread=android.app.IApplicationThread",
        "crashing=true",
    )
    if any(source.count(fragment) != 1 for fragment in required_once):
        raise ValueError(f"Home process watchdog mismatch: {label}")
    if source.count('if ! home_process_ready "$home_pid"; then') != 2:
        raise ValueError(f"Home process stability-window mismatch: {label}")

def verify_home_unlock_deferral(data: bytes, label: str) -> None:
    source = data.decode("utf-8")
    required_once = (
        '[ -x "$TIMEOUT" ] || {',
        'user_state=$("$TIMEOUT" -k 1s 8s am get-started-user-state 0 2>/dev/null) ||',
        'if [ "$user_state" != "RUNNING_UNLOCKED" ]; then',
        'log_line "deferred: user 0 is $user_state; Home activation waits for unlock"',
    )
    if any(source.count(fragment) != 1 for fragment in required_once):
        raise ValueError(f"Home unlock deferral mismatch: {label}")
    boot_at = source.find('if [ "$(getprop sys.boot_completed)" != "1" ]; then')
    timeout_at = source.find('[ -x "$TIMEOUT" ] || {', boot_at)
    state_at = source.find('user_state=$("$TIMEOUT"', timeout_at)
    deferred_at = source.find('if [ "$user_state" != "RUNNING_UNLOCKED" ]; then', state_at)
    settle_at = source.find("\nsleep 5\n", deferred_at)
    generate_at = source.find("\nif ! ensure_generated_apk; then", settle_at)
    if not (0 <= boot_at < timeout_at < state_at < deferred_at < settle_at < generate_at):
        raise ValueError(f"Home unlock deferral ordering mismatch: {label}")

def expected_directories(contents: set[str]) -> set[str]:
    result: set[str] = set()
    for name in contents:
        parent = PurePosixPath(name).parent
        while parent != PurePosixPath("."):
            result.add(f"{parent.as_posix()}/")
            parent = parent.parent
    return result

def verify_zip(
    path: Path,
    expected_id: str,
    expected_version: str,
    expected_contents: dict[str, str],
) -> None:
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if not infos or len(infos) > MAX_ZIP_ENTRIES:
            raise ValueError(f"invalid ZIP entry count: {path.name}")
        names = [info.filename for info in infos]
        if len(names) != len(set(names)) or len(names) != len(set(name.casefold() for name in names)):
            raise ValueError(f"duplicate ZIP entry: {path.name}")
        total = 0
        for info in infos:
            name = info.filename
            parsed = PurePosixPath(name)
            mode = (info.external_attr >> 16) & 0xFFFF
            mode_type = stat.S_IFMT(mode)
            canonical = parsed.as_posix() + ("/" if info.is_dir() else "")
            if (
                not name
                or any(ord(character) < 32 for character in name)
                or parsed.is_absolute()
                or ".." in parsed.parts
                or "\\" in name
                or name != canonical
            ):
                raise ValueError(f"unsafe ZIP path: {path.name}:{name}")
            if mode_type not in (0, stat.S_IFREG, stat.S_IFDIR):
                raise ValueError(f"special ZIP entry: {path.name}:{name}")
            if mode_type and info.is_dir() != stat.S_ISDIR(mode):
                raise ValueError(f"ZIP type mismatch: {path.name}:{name}")
            if info.flag_bits & 1:
                raise ValueError(f"encrypted ZIP entry: {path.name}:{name}")
            if info.is_dir() and info.file_size != 0:
                raise ValueError(f"nonempty ZIP directory: {path.name}:{name}")
            if info.file_size > MAX_ZIP_ENTRY_BYTES:
                raise ValueError(f"oversized ZIP entry: {path.name}:{name}")
            total += info.file_size
            if total > MAX_ZIP_TOTAL_BYTES:
                raise ValueError(f"oversized ZIP archive: {path.name}")
            if info.file_size and info.compress_size == 0:
                raise ValueError(f"invalid ZIP compression size: {path.name}:{name}")
            if name.endswith(".sh"):
                check = subprocess.run(
                    ["/bin/sh", "-n"],
                    input=archive.read(info),
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    check=False,
                )
                if check.returncode != 0:
                    raise ValueError(f"invalid shell syntax: {path.name}:{name}")
        expected_files = set(expected_contents)
        expected_names = expected_files | expected_directories(expected_files)
        if set(names) != expected_names:
            raise ValueError(f"unexpected or missing ZIP content: {path.name}")
        corrupt = archive.testzip()
        if corrupt is not None:
            raise ValueError(f"corrupt ZIP entry: {path.name}:{corrupt}")
        properties = parse_properties(archive.read("module.prop"))
        if properties.get("id") != expected_id or properties.get("version") != expected_version:
            raise ValueError(f"module identity mismatch: {path.name}")
        for name, expected in expected_contents.items():
            actual = sha256_bytes(archive.read(name))
            if actual != expected:
                raise ValueError(f"inner hash mismatch: {path.name}:{name}")
        verify_internal_hash_pins(path, archive, expected_contents)
        if expected_id in {"scr01_scroot_menu", "scr01_overview_bridge"}:
            for script_name in ("customize.sh", "boot-completed.sh"):
                verify_single_package_path_guard(
                    archive.read(script_name),
                    f"{path.name}:{script_name}",
                )
        if expected_id == "scr01_overview_bridge":
            verify_overview_upgrade_allowlist(
                archive.read("customize.sh"),
                f"{path.name}:customize.sh",
            )
            verify_task_broker_apk_pinning(
                archive.read("boot-completed.sh"),
                f"{path.name}:boot-completed.sh",
            )
            verify_single_quickstep_component_guard(
                archive.read("boot-completed.sh"),
                f"{path.name}:boot-completed.sh",
            )
            boot_constants = parse_shell_hashes(archive.read("boot-completed.sh"))
            verify_fail_closed_uninstall(
                archive.read("uninstall.sh"),
                boot_constants["EXPECTED_PATCH_SHA256"],
                boot_constants["EXPECTED_STOCK_SHA256"],
                f"{path.name}:uninstall.sh",
            )
            verify_systemui_activation_ownership(
                archive.read("boot-completed.sh"),
                f"{path.name}:boot-completed.sh",
            )
            verify_atomic_activation_lock(
                archive.read("boot-completed.sh"),
                f"{path.name}:boot-completed.sh",
                inherits_long_lived_broker=True,
                expected_lock_file="/data/adb/.scr01_overview_bridge.activation.lock",
            )
            verify_atomic_activation_lock(
                archive.read("uninstall.sh"),
                f"{path.name}:uninstall.sh",
                inherits_long_lived_broker=False,
                expected_lock_file="/data/adb/.scr01_overview_bridge.activation.lock",
            )
        if expected_id == "scr01_scroot_menu":
            for script_name in ("customize.sh", "boot-completed.sh"):
                verify_home_upgrade_allowlist(
                    archive.read(script_name),
                    f"{path.name}:{script_name}",
                )
            verify_home_runtime_watchdog(
                archive.read("boot-completed.sh"),
                f"{path.name}:boot-completed.sh",
            )
            verify_home_unlock_deferral(
                archive.read("boot-completed.sh"),
                f"{path.name}:boot-completed.sh",
            )
            boot_constants = parse_shell_hashes(archive.read("boot-completed.sh"))
            verify_fail_closed_uninstall(
                archive.read("uninstall.sh"),
                boot_constants["EXPECTED_PATCH_SHA256"],
                boot_constants["EXPECTED_STOCK_SHA256"],
                f"{path.name}:uninstall.sh",
            )
            verify_atomic_activation_lock(
                archive.read("boot-completed.sh"),
                f"{path.name}:boot-completed.sh",
                inherits_long_lived_broker=False,
                expected_lock_file="/data/adb/.scr01_scroot_menu.activation.lock",
            )
            verify_atomic_activation_lock(
                archive.read("uninstall.sh"),
                f"{path.name}:uninstall.sh",
                inherits_long_lived_broker=False,
                expected_lock_file="/data/adb/.scr01_scroot_menu.activation.lock",
            )

def verify_manifest(path: Path) -> None:
    require_regular_file(path, 1024 * 1024)
    root = ElementTree.parse(path).getroot()
    if root.tag != "manifest":
        raise ValueError("invalid Android manifest root")
    if root.get(f"{ANDROID}sharedUserId") is not None:
        raise ValueError("Android shared user IDs are forbidden")
    permission_names = [
        element.get(f"{ANDROID}name")
        for element in root.findall("uses-permission")
    ]
    if any(name is None for name in permission_names):
        raise ValueError("unnamed Android permission")
    permissions = set(permission_names)
    if permissions != EXPECTED_PERMISSIONS:
        raise ValueError(
            f"Android permission surface mismatch: {sorted(permissions)}"
        )
    declared_permissions = {
        element.get(f"{ANDROID}name"): element.get(f"{ANDROID}protectionLevel")
        for element in root.findall("permission")
    }
    if declared_permissions != EXPECTED_DECLARED_PERMISSIONS:
        raise ValueError(
            f"Android declared permission surface mismatch: {declared_permissions}"
        )
    query_names = [
        element.get(f"{ANDROID}name")
        for element in root.findall("queries/package")
    ]
    if any(name is None for name in query_names):
        raise ValueError("unnamed Android package query")
    queries = set(query_names)
    if queries != EXPECTED_QUERIES:
        raise ValueError(f"Android package query surface mismatch: {sorted(queries)}")
    application = root.find("application")
    if application is None:
        raise ValueError("Android application element is missing")
    if application.get(f"{ANDROID}allowBackup") != "false":
        raise ValueError("Android backups must remain disabled")
    if application.get(f"{ANDROID}usesCleartextTraffic") != "false":
        raise ValueError("Android cleartext traffic must remain disabled")
    if application.get(f"{ANDROID}debuggable") == "true":
        raise ValueError("Android application must not be explicitly debuggable")
    components: dict[tuple[str, str], str | None] = {}
    for kind in ("activity", "activity-alias", "receiver", "service", "provider"):
        for element in application.findall(kind):
            name = element.get(f"{ANDROID}name")
            key = (kind, name or "")
            if key in components:
                raise ValueError(f"duplicate Android component: {kind}:{name}")
            components[key] = element.get(f"{ANDROID}exported")
    if components != EXPECTED_COMPONENTS:
        raise ValueError(f"Android component surface mismatch: {components}")
    direct_boot_components = {
        (kind, element.get(f"{ANDROID}name") or "")
        for kind in ("activity", "receiver", "service")
        for element in application.findall(kind)
        if element.get(f"{ANDROID}directBootAware") == "true"
    }
    if direct_boot_components != EXPECTED_DIRECT_BOOT_COMPONENTS:
        raise ValueError(
            f"Android direct-boot component mismatch: {direct_boot_components}"
        )
    main_activity = application.find("activity[@android:name='.MainActivity']", {
        "android": ANDROID_NAMESPACE,
    })
    if main_activity is None:
        raise ValueError("launcher activity is missing")
    if main_activity.get(f"{ANDROID}launchMode") != "singleTask":
        raise ValueError("launcher activity must remain singleTask")
    launcher_actions = {
        element.get(f"{ANDROID}name")
        for element in main_activity.findall("intent-filter/action")
    }
    launcher_categories = {
        element.get(f"{ANDROID}name")
        for element in main_activity.findall("intent-filter/category")
    }
    if launcher_actions != {"android.intent.action.MAIN"} or launcher_categories != {
        "android.intent.category.LAUNCHER"
    }:
        raise ValueError("launcher intent surface mismatch")
    receiver = application.find("receiver[@android:name='.AutoRootReceiver']", {
        "android": ANDROID_NAMESPACE,
    })
    if receiver is None:
        raise ValueError("boot receiver is missing")
    receiver_actions = {
        element.get(f"{ANDROID}name")
        for element in receiver.findall("intent-filter/action")
    }
    if receiver_actions != {
        "android.intent.action.LOCKED_BOOT_COMPLETED",
        "android.intent.action.BOOT_COMPLETED",
    }:
        raise ValueError("boot receiver intent surface mismatch")


def verify_gradle_application(path: Path) -> None:
    require_regular_file(path, 1024 * 1024)
    source = path.read_text("utf-8")
    for key, expected in EXPECTED_GRADLE_VALUES.items():
        if key in {"namespace", "applicationId", "versionName"}:
            pattern = rf"(?m)^\s*{re.escape(key)}\s+['\"]([^'\"]+)['\"]\s*$"
        else:
            pattern = rf"(?m)^\s*{re.escape(key)}\s+(\d+)\s*$"
        values = re.findall(pattern, source)
        if values != [expected]:
            raise ValueError(f"Gradle {key} mismatch: {values}")
    if re.search(r"(?m)^\s*debuggable\s+true\s*$", source):
        raise ValueError("release configuration must not be debuggable")
    release = re.search(r"(?s)buildTypes\s*\{.*?release\s*\{(.*?)\n\s*\}\s*\n\s*\}", source)
    if release is None or not re.search(
        r"(?m)^\s*minifyEnabled\s+false\s*$",
        release.group(1),
    ):
        raise ValueError("release minification policy mismatch")


def verify_root_flow_security_invariants(source: str) -> None:
    split_guard = "if (!hasNoSplitApks(appInfo.splitSourceDirs))"
    if source.count(split_guard) != 3:
        raise ValueError("RootFlow exact-package split APK guards mismatch")
    bridge_guard = '[ \\"\\$count\\" -eq 1 ] || return 1;'
    if source.count('append("bridge_hash() { paths=') != 1 or source.count(bridge_guard) != 1:
        raise ValueError("RootFlow Overview package-path cardinality guard mismatch")
    health_fragments = (
        '"content://com.sec.android.app.launcher.scroot.health"',
        'Uri.parse(OVERVIEW_HEALTH_URI)',
        'private const val OVERVIEW_HEALTH_BUILD_ID = "scroverview-0.4.7"',
        "val overviewReady = health != null && overviewHealthSignalsReady(",
        'buildId = health.getString(OVERVIEW_HEALTH_BUILD_KEY)',
        'quickstepBound = health.getBoolean("quickstep_bound", false)',
        'brokerReady = health.getBoolean("broker_ready", false)',
        'private const val HOME_HEALTH_ACTION = "com.scr01.scroot.action.HOME_HEALTH"',
        'private const val HOME_HEALTH_BUILD_ID = "scr01-home-1.7.27"',
        "homeReady = isHomeUiLive(appContext)",
        "internal fun homeHealthSignalsReady(",
        'buildId = extras?.getString(HOME_HEALTH_BUILD_KEY)',
        ".setPackage(HOME_PACKAGE)",
        "completed.await(HOME_HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)",
        "if (!verifyProvisionedSystemUiLive(ctx)) {",
        "if (!recoverProvisionedSystemUiLive(health, log) ||",
        "if (!ready && provisionFailureMayUseLiveRecovery(result.rc, result.timedOut)) {",
        'log("  [recovery] retrying the safely rolled-back UI component")',
        r"name=\$({ tr ",
        'append("echo \'UI_LIVE_RECOVERY_READY\'\\n")',
        "if (!isSystemUiIntegratedForCurrentBoot(ctx)) {",
        "Files.readAllBytes(target.toPath()).contentEquals(expectedBytes)",
        "if (!committed) Files.deleteIfExists(target.toPath())",
    )
    if any(source.count(fragment) != 1 for fragment in health_fragments):
        raise ValueError("RootFlow live system UI health invariant mismatch")
    if source.count("val appContext = ctx.applicationContext") != 1 or source.count(
        "val appContext = AutoRootPreferences.deviceProtectedContext(ctx)"
    ) != 1 or source.count(
        "appContext.contentResolver.call("
    ) != 1 or source.count(
        "val receiptFile = File(appContext.filesDir, UI_RECEIPT_FILE)"
    ) != 1:
        raise ValueError("RootFlow health context lifetime invariant mismatch")
    if source.count("val cleared = clearSystemUiReceipt(ctx)") != 2:
        raise ValueError("RootFlow live system UI receipt cleanup mismatch")
    receipt_write = source.find("if (!writeSystemUiReceipt(ctx)) {")
    live_verify = source.find("if (!verifyProvisionedSystemUiLive(ctx)) {")
    commit_verify = source.find("if (!isSystemUiIntegratedForCurrentBoot(ctx)) {")
    success_log = source.find(
        'log("  [OK] Apps screen, Root menu and native Recents are active")'
    )
    if (
        live_verify < 0
        or receipt_write <= live_verify
        or commit_verify <= receipt_write
        or success_log <= commit_verify
    ):
        raise ValueError("RootFlow live system UI verification ordering mismatch")
    lock_observer = next(
        (
            line
            for line in source.splitlines()
            if 'append("activation_lock_alive() {' in line
        ),
        None,
    )
    if lock_observer is None:
        raise ValueError("RootFlow module activation lock observer is missing")
    if 'rm -f' in lock_observer or 'rmdir' in lock_observer:
        raise ValueError("RootFlow module activation lock observer must be read-only")
    shared_lock_definitions = (
        'append("OVERVIEW_LOCK=/data/adb/.scr01_overview_bridge.activation.lock\\n")',
        'append("HOME_LOCK=/data/adb/.scr01_scroot_menu.activation.lock\\n")',
    )
    if any(source.count(fragment) != 1 for fragment in shared_lock_definitions):
        raise ValueError("RootFlow shared module lock definitions mismatch")
    activation_scan = next(
        (
            line
            for line in source.splitlines()
            if 'append("activation_running() {' in line
        ),
        None,
    )
    activation_fragments = (
        'for lock in \\"\\$OVERVIEW_LOCK\\" \\"\\$HOME_LOCK\\"; do',
        "for base in /data/adb/modules_update /data/adb/modules; do",
        'for id in \\"\\$OVERVIEW_ID\\" \\"\\$HOME_ID\\"; do',
        'lock=\\"\\$dir/.activation.lock\\";',
        'activation_lock_alive \\"\\$dir\\" && return 0;',
        'activation_flock_held \\"\\$lock\\" && return 0;',
        'elif [ -e \\"\\$lock\\" ] || [ -L \\"\\$lock\\" ]; then return 0;',
    )
    if activation_scan is None or any(
        fragment not in activation_scan for fragment in activation_fragments
    ):
        raise ValueError("RootFlow must inspect active and staged module locks")
    if activation_scan.find("for lock in") >= activation_scan.find("for base in"):
        raise ValueError("RootFlow must inspect shared locks before legacy module locks")
    flock_observer = next(
        (
            line
            for line in source.splitlines()
            if 'append("activation_flock_held() {' in line
        ),
        None,
    )
    flock_fragments = (
        '[ -f \\"\\$lock\\" ] && [ ! -L \\"\\$lock\\" ] || return 1;',
        '( exec 0<> \\"\\$lock\\" || exit 0;',
        'if \\"\\$FLOCK\\" -n 0;',
        '\\"\\$FLOCK\\" -u 0;',
    )
    if flock_observer is None or any(
        fragment not in flock_observer for fragment in flock_fragments
    ):
        raise ValueError("RootFlow advisory lock observer invariant mismatch")
    promotion = next(
        (
            line
            for line in source.splitlines()
            if 'append("promote_active_uninstall() {' in line
        ),
        None,
    )
    promotion_fragments = (
        'staged=\\"/data/adb/modules_update/\\$id/uninstall.sh\\";',
        'active=\\"/data/adb/modules/\\$id\\";',
        '[ ! -L \\"\\$staged\\" ] || return 1;',
        '[ ! -L \\"\\$active\\" ] || return 1;',
        '[ \\"\\$(file_hash \\"\\$temporary\\")\\" != \\"\\$expected\\" ]',
        'mv -f \\"\\$temporary\\" \\"\\$active/uninstall.sh\\"',
        '[ \\"\\$(file_hash \\"\\$active/uninstall.sh\\")\\" != \\"\\$expected\\" ]',
    )
    if promotion is None or any(
        fragment not in promotion for fragment in promotion_fragments
    ):
        raise ValueError("RootFlow active uninstall promotion invariant mismatch")
    verified_promotion = next(
        (
            line
            for line in source.splitlines()
            if 'append("promote_verified_active_uninstalls() {' in line
        ),
        None,
    )
    verified_promotion_fragments = (
        '[ -f \\"/data/adb/modules_update/\\$OVERVIEW_ID/module.prop\\" ]',
        'module_exact \\"\\$OVERVIEW_ID\\" \\"\\$OVERVIEW_VERSION\\" && overview_integrity;',
        '[ -f \\"/data/adb/modules_update/\\$HOME_ID/module.prop\\" ]',
        'module_exact \\"\\$HOME_ID\\" \\"\\$HOME_VERSION\\" && home_integrity;',
        '[ \\"\\$promoted\\" = 0 ] || \\"\\$TIMEOUT\\" -k 2s 20s \\"\\$SYNC\\";',
    )
    if verified_promotion is None or any(
        fragment not in verified_promotion
        for fragment in verified_promotion_fragments
    ):
        raise ValueError("RootFlow verified uninstall promotion invariant mismatch")
    if source.count('append("promote_verified_active_uninstalls || exit ') != 2:
        raise ValueError("RootFlow active uninstall promotion call mismatch")
    module_directory = next(
        (
            line
            for line in source.splitlines()
            if 'append("module_dir() {' in line
            and '[ ! -L \\"\\$dir/module.prop\\" ]' in line
        ),
        None,
    )
    module_directory_fragments = (
        '[ -d \\"\\$dir\\" ]',
        '[ ! -L \\"\\$dir\\" ]',
        '[ -f \\"\\$dir/module.prop\\" ]',
        '[ ! -L \\"\\$dir/module.prop\\" ]',
    )
    if module_directory is None or any(
        fragment not in module_directory for fragment in module_directory_fragments
    ):
        raise ValueError("RootFlow module directory no-follow invariant mismatch")
    module_state_lines = [
        line
        for line in source.splitlines()
        if 'append("module_not_blocked() {' in line
    ]
    module_state_fragments = (
        "for base in /data/adb/modules_update /data/adb/modules; do",
        "for marker in disable remove; do",
        '[ ! -e \\"\\$dir/\\$marker\\" ]',
        '[ ! -L \\"\\$dir/\\$marker\\" ] || return 1;',
    )
    if len(module_state_lines) != 2 or any(
        fragment not in line
        for line in module_state_lines
        for fragment in module_state_fragments
    ):
        raise ValueError("RootFlow module disable/remove state guard mismatch")
    verify_boot = next(
        (
            line
            for line in source.splitlines()
            if 'append("verify_boot() {' in line
        ),
        None,
    )
    if (
        verify_boot is None
        or 'module_not_blocked \\"\\$id\\" || return 1;' not in verify_boot
    ):
        raise ValueError("RootFlow live recovery module state guard mismatch")
    module_exact = next(
        (
            line
            for line in source.splitlines()
            if 'append("module_exact() {' in line
        ),
        None,
    )
    install_exact = next(
        (
            line
            for line in source.splitlines()
            if 'append("install_exact() {' in line
        ),
        None,
    )
    if (
        module_exact is None
        or 'module_not_blocked \\"\\$1\\" || return 1;' not in module_exact
        or install_exact is None
        or install_exact.count('module_not_blocked \\"\\$id\\"') != 2
        or install_exact.find('module_not_blocked \\"\\$id\\"')
        > install_exact.find('\\"\\$KSUD\\" module install')
    ):
        raise ValueError("RootFlow module state transition guard mismatch")
    preactivation_guards = (
        'append("module_exact \\"\\$OVERVIEW_ID\\" \\"\\$OVERVIEW_VERSION\\" && overview_integrity || exit 55\\n")',
        'append("module_exact \\"\\$HOME_ID\\" \\"\\$HOME_VERSION\\" && home_integrity || exit 56\\n")',
    )
    if any(source.count(fragment) != 1 for fragment in preactivation_guards):
        raise ValueError("RootFlow pre-activation module state guard mismatch")


def verify_main_activity_safety_invariants(source: str) -> None:
    if source.count("if (RootFlow.currentExploitWindowExpired()) {") != 2:
        raise ValueError("MainActivity fresh-boot window guards mismatch")
    required = (
        'setButtonEnabled(true, "Start root setup", primary)',
        'setButtonEnabled(true, "Enable auto root", danger)',
        'setButtonEnabled(false, "REBOOT THE DEVICE", danger)',
        '"240 seconds passed. Reboot and try again."',
        'textView("Auto root after reboot", 13f, textSecondary)',
        '"On · Auto root after reboot"',
        '"Restart the device. Auto root will start after reboot."',
        "private var autoRootDangerState = false",
        "private fun setAutoRootDangerAppearance(enabled: Boolean)",
        "intArrayOf(if (enabled) danger else success, palette.switchThumbOff)",
        "if (enabled) palette.dangerAction else palette.switchTrackOn",
        "if (autoRootDangerState) danger else statusColor",
        'val rebootInstruction = !enabled && label == "REBOOT THE DEVICE"',
        "button.alpha = if (enabled || rebootInstruction) 1f else 0.58f",
        "button.textSize = if (rebootInstruction) 18f else 15f",
        "AutoRootPreferences.setEnabled(this, true)",
        "enableAutoRootOnClick -> enableAutoRootAfterReboot()",
        '"[BLOCKED] The 240-second fresh-boot window has elapsed."',
    )
    if any(source.count(fragment) != 1 for fragment in required):
        raise ValueError("MainActivity fresh-boot window presentation mismatch")


def verify_root_flow_generated_shell_syntax(source: str) -> None:
    function_blocks = (
        (
            "live recovery",
            "    private fun recoverProvisionedSystemUiLive(",
            "    private fun provisionSystemUi(",
            10,
        ),
        (
            "provisioning",
            "    private fun provisionSystemUi(",
            "    private fun moduleManagerAppId()",
            60,
        ),
    )
    for label, function_start, function_end, minimum_fragments in function_blocks:
        if source.count(function_start) != 1 or source.count(function_end) != 1:
            raise ValueError(f"RootFlow {label} function boundary mismatch")
        function = source.split(function_start, 1)[1].split(function_end, 1)[0]
        start_marker = "val script = buildString {"
        end_marker = "        }\n        val result = captureRootScript"
        if function.count(start_marker) != 1 or function.count(end_marker) != 1:
            raise ValueError(f"RootFlow {label} shell block boundary mismatch")
        block = function.split(start_marker, 1)[1].split(end_marker, 1)[0]
        fragments: list[str] = []
        for raw_line in block.splitlines():
            line = raw_line.strip()
            if not line.startswith('append("') or not line.endswith('")'):
                continue
            encoded = line[len('append("'):-2].replace(r"\$", "$")
            try:
                fragments.append(json.loads(f'"{encoded}"'))
            except json.JSONDecodeError as error:
                raise ValueError(f"RootFlow {label} shell string decode failed") from error
        if len(fragments) < minimum_fragments:
            raise ValueError(f"RootFlow {label} shell is unexpectedly incomplete")
        check = subprocess.run(
            ["/bin/sh", "-n"],
            input="".join(fragments).encode(),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if check.returncode != 0:
            raise ValueError(
                f"RootFlow {label} shell syntax failed: "
                + check.stderr.decode("utf-8", "replace").strip()
            )


def verify_meta_module_invariants(source: str) -> None:
    start = "    internal fun metaModuleVerificationShell(): String = buildString {"
    end = "\n    fun run("
    if source.count(start) != 1 or source.count(end) != 1:
        raise ValueError("RootFlow metamodule verifier boundary mismatch")
    block = source.split(start, 1)[1].split(end, 1)[0]
    fragments: list[str] = []
    for raw_line in block.splitlines():
        line = raw_line.strip()
        if not line.startswith('append("') or not line.endswith('")'):
            continue
        encoded = line[len('append("'):-2].replace(r"\$", "$")
        try:
            fragments.append(json.loads(f'"{encoded}"'))
        except json.JSONDecodeError as error:
            raise ValueError("RootFlow metamodule shell string decode failed") from error
    shell = "".join(fragments)
    required = (
        "META_ID='$EXPECTED_META_ID'",
        "META_VERSION='$EXPECTED_META_VERSION'",
        "for base in /data/adb/modules_update /data/adb/modules; do",
        "for marker in disable remove; do",
        '[ ! -L "$dir/module.prop" ]',
        'grep -qx "id=$META_ID" "$dir/module.prop"',
        'grep -qx "version=$META_VERSION" "$dir/module.prop"',
        "grep -qx 'metamodule=1' \"$dir/module.prop\"",
        '[ -x "$dir/meta-overlayfs" ]',
        'meta_file_hash "$dir/meta-overlayfs"',
        'meta_file_hash "$dir/metainstall.sh"',
        'meta_file_hash "$dir/metamount.sh"',
        'meta_file_hash "$dir/post-mount.sh"',
        'meta_file_hash "$dir/metauninstall.sh"',
        'meta_file_hash "$dir/uninstall.sh"',
    )
    if len(fragments) != 13 or any(fragment not in shell for fragment in required):
        raise ValueError("RootFlow exact metamodule verifier mismatch")
    check = subprocess.run(
        ["/bin/sh", "-n"],
        input=shell.encode(),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if check.returncode != 0:
        raise ValueError(
            "RootFlow metamodule shell syntax failed: "
            + check.stderr.decode("utf-8", "replace").strip()
        )
    source_required = (
        "metaModuleVerificationShell() +",
        "append(metaModuleVerificationShell())",
        "if ! meta_exact; then meta_not_blocked || exit 32;",
        "if meta_exact; then META_INSTALL_RC=0; else meta_not_blocked || exit 32;",
        "INSTALLED_PATHS=\\$(pm path \\$PKG 2>/dev/null)",
        "INSTALLED_COUNT=\\$(printf '%s\\\\n'",
        'case \\"\\$INSTALLED_COUNT\\" in 1)',
    )
    if any(source.count(fragment) != 1 for fragment in source_required):
        raise ValueError("RootFlow metamodule or Manager cardinality integration mismatch")
    if "module list | grep '$EXPECTED_META_VERSION'" in source or "head -n 1" in source:
        raise ValueError("RootFlow ambiguous module or Manager first-match resolution found")


def main() -> int:
    expected_assets = {path for path in TOP_LEVEL if path.parent == ASSETS}
    expected_native = {path for path in TOP_LEVEL if path.parent == NATIVE}
    verify_exact_regular_files(ASSETS, expected_assets)
    verify_exact_regular_files(NATIVE, expected_native)
    for path, expected in TOP_LEVEL.items():
        actual = sha256_file(path)
        if actual != expected:
            raise ValueError(f"top-level hash mismatch: {path.relative_to(ROOT)}")
    for path, contents in ZIP_CONTENTS.items():
        expected_id, expected_version = MODULES[path]
        verify_zip(path, expected_id, expected_version, contents)
    overview_path = next(
        path for path, identity in MODULES.items()
        if identity[0] == "scr01_overview_bridge"
    )
    home_path = next(
        path for path, identity in MODULES.items()
        if identity[0] == "scr01_scroot_menu"
    )
    expected_bridge = ZIP_CONTENTS[overview_path]["app/SCROverview.apk"]
    with zipfile.ZipFile(home_path) as archive:
        for script_name in ("customize.sh", "boot-completed.sh"):
            constants = parse_shell_hashes(archive.read(script_name))
            if constants.get("EXPECTED_BRIDGE_SHA256") != expected_bridge:
                raise ValueError(f"Home bridge pin mismatch: {script_name}")
    verify_manifest(MANIFEST)
    verify_gradle_application(BUILD_GRADLE)
    require_regular_file(ROOT_FLOW, 2 * 1024 * 1024)
    source = ROOT_FLOW.read_text("utf-8")
    verify_root_flow_security_invariants(source)
    verify_root_flow_generated_shell_syntax(source)
    verify_meta_module_invariants(source)
    require_regular_file(MAIN_ACTIVITY, 2 * 1024 * 1024)
    verify_main_activity_safety_invariants(MAIN_ACTIVITY.read_text("utf-8"))
    for path, expected in TOP_LEVEL.items():
        if path.parent == ASSETS and path.suffix != ".apk":
            if path.name not in source or expected not in source:
                raise ValueError(f"RootFlow pin mismatch: {path.name}")
    for path, contents in ZIP_CONTENTS.items():
        if path.name == "meta-overlayfs-scr01.zip" or path.name.startswith(
            ("scr01-home-ui-", "scr01-overview-bridge-")
        ):
            for name, expected in contents.items():
                if name == "customize.sh":
                    continue
                if expected not in source:
                    raise ValueError(f"RootFlow inner pin mismatch: {path.name}:{name}")
    expected_module_assets = {
        path.name
        for path in ZIP_CONTENTS
        if path.name.startswith(("scr01-home-ui-", "scr01-overview-bridge-"))
    }
    referenced_module_assets = set(
        re.findall(r"scr01-(?:home-ui|overview-bridge)-[A-Za-z0-9._-]+\.zip", source)
    )
    if referenced_module_assets != expected_module_assets:
        raise ValueError(
            "RootFlow module asset references mismatch: "
            f"expected={sorted(expected_module_assets)} actual={sorted(referenced_module_assets)}"
        )
    print(f"verified {len(TOP_LEVEL)} payloads and {len(ZIP_CONTENTS)} module archives")
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (
        ElementTree.ParseError,
        FileNotFoundError,
        KeyError,
        OSError,
        UnicodeError,
        ValueError,
        zipfile.BadZipFile,
    ) as error:
        print(f"release asset verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
