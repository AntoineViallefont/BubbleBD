#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
sdk_path="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
apk_path="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$apk_path" ]; then ./scripts/build.sh; fi
"$sdk_path/platform-tools/adb" install -r "$apk_path"
"$sdk_path/platform-tools/adb" shell am start -n fr.bubblebd/.MainActivity
