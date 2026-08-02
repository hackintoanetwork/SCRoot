#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: build-unsigned.sh official-v3.3.0.apk apktool-3.0.2.jar android-36.jar output.apk" >&2
  exit 2
fi

script_dir=$(cd "$(dirname "$0")" && pwd)
official_apk=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
apktool_jar=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
android_jar=$(cd "$(dirname "$3")" && pwd)/$(basename "$3")
output_apk=$(cd "$(dirname "$4")" && pwd)/$(basename "$4")
if [[ -e "$output_apk" ]]; then
  echo "output APK already exists" >&2
  exit 2
fi
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT
java_bin=${JAVA_BIN:-java}

if ! command -v "$java_bin" >/dev/null 2>&1; then
  echo "Java 17 or newer is required; set JAVA_BIN to its executable path" >&2
  exit 2
fi

python3 - "$official_apk" "$apktool_jar" "$android_jar" <<'PY'
import hashlib
import pathlib
import sys

expected = {
    sys.argv[1]: "fd0b12385c98fe9d5f4f1257b5f184e55c74c1376637507df0718305f5d7a924",
    sys.argv[2]: "eee4669a704a14e0623407e6701b0b91887e61e1e4049cb7a82833e14ae8b5fd",
    sys.argv[3]: "d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a",
}
for path, wanted in expected.items():
    actual = hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()
    if actual != wanted:
        raise SystemExit(f"hash mismatch: {path}")
PY

"$java_bin" -jar "$apktool_jar" if -p "$work_dir/framework" "$android_jar"
"$java_bin" -jar "$apktool_jar" d -f -p "$work_dir/framework" "$official_apk" -o "$work_dir/decoded"
python3 "$script_dir/patch_manager.py" "$work_dir/decoded"
"$java_bin" -jar "$apktool_jar" b -p "$work_dir/framework" "$work_dir/decoded" -o "$output_apk"
python3 - "$output_apk" <<'PY'
import hashlib
import sys
import zipfile

expected = {
    "AndroidManifest.xml": "627bc1fb8337e97db4594eebedda26678c7ff5043c3a75edadab76c4bf7202de",
    "classes.dex": "eaa4409041f193415582380f4417096d11f441d5b8ee9ad6d9debc4921e7fe9d",
    "resources.arsc": "434d404160a7728aaf91945fbec131203678ad8fdab475c8a60affa6ad97e6b7",
}
with zipfile.ZipFile(sys.argv[1]) as archive:
    for name, wanted in expected.items():
        actual = hashlib.sha256(archive.read(name)).hexdigest()
        if actual != wanted:
            raise SystemExit(f"rebuilt Manager payload mismatch: {name}")
PY
echo "$output_apk"
