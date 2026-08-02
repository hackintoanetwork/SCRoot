#!/bin/sh
set -eu

if [ "$#" -ne 0 ]; then
    printf '%s\n' "Usage: ./build.sh" >&2
    exit 2
fi

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$project_dir"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    java_executable="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1 && java -version >/dev/null 2>&1; then
    java_executable=$(command -v java)
elif [ -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java ]; then
    JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    export JAVA_HOME
    java_executable="$JAVA_HOME/bin/java"
elif [ -x /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java ]; then
    JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    export JAVA_HOME
    java_executable="$JAVA_HOME/bin/java"
else
    printf '%s\n' "Java 17 or newer is required." >&2
    exit 1
fi

java_major=$("$java_executable" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | sed -n '1p')
if [ -z "$java_major" ] || [ "$java_major" -lt 17 ]; then
    printf '%s\n' "Java 17 or newer is required." >&2
    exit 1
fi

sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk_root" ] && [ -f local.properties ]; then
    sdk_root=$(sed -n 's/^sdk\.dir=//p' local.properties | sed -n '1p')
fi
if [ -z "$sdk_root" ] && [ -n "${HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]; then
    sdk_root="$HOME/Library/Android/sdk"
fi
if [ -z "$sdk_root" ] && [ -n "${HOME:-}" ] && [ -d "$HOME/Android/Sdk" ]; then
    sdk_root="$HOME/Android/Sdk"
fi
if [ -z "$sdk_root" ] || [ ! -f "$sdk_root/platforms/android-34/android.jar" ]; then
    printf '%s\n' "Android SDK Platform 34 is required." >&2
    printf '%s\n' "Set ANDROID_HOME or ANDROID_SDK_ROOT, or create local.properties with sdk.dir." >&2
    exit 1
fi

ANDROID_HOME=$sdk_root
ANDROID_SDK_ROOT=$sdk_root
export ANDROID_HOME ANDROID_SDK_ROOT

if ! command -v python3 >/dev/null 2>&1; then
    printf '%s\n' "Python 3 is required for payload verification." >&2
    exit 1
fi

PYTHONDONTWRITEBYTECODE=1 python3 tools/verify_release_assets.py
PYTHONDONTWRITEBYTECODE=1 python3 tools/verify_corresponding_source.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools -p 'test_*.py' -q
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug

version_name=$(sed -n 's/^[[:space:]]*versionName[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle | sed -n '1p')
if [ -z "$version_name" ]; then
    printf '%s\n' "Could not determine the app version." >&2
    exit 1
fi

built_apk="$project_dir/app/build/outputs/apk/debug/app-debug.apk"
output_dir="$project_dir/dist"
output_apk="$output_dir/SCRoot-SCR01-$version_name-debug.apk"
if [ ! -f "$built_apk" ]; then
    printf '%s\n' "Gradle completed without producing the expected APK." >&2
    exit 1
fi

mkdir -p "$output_dir"
cp -f "$built_apk" "$output_apk"
chmod 0644 "$output_apk"

if command -v sha256sum >/dev/null 2>&1; then
    apk_sha256=$(sha256sum "$output_apk" | awk '{print $1}')
else
    apk_sha256=$(shasum -a 256 "$output_apk" | awk '{print $1}')
fi

printf '\nBuilt: %s\n' "$output_apk"
printf 'SHA-256: %s\n' "$apk_sha256"
printf 'Install: adb install -r "%s"\n' "$output_apk"
