#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ] && [ -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]; then
  export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
fi
if [ "${1:-}" = --release ]; then
  [ "$#" -eq 1 ] || exit 1
  ./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease --console=plain
  python3 scripts/sign-release.py
  python3 scripts/package-source.py
  exit 0
elif [ "${1:-}" = --compile-only ]; then
  [ "$#" -eq 1 ] || exit 1
  ./gradlew :app:assembleDebug --console=plain
else
  [ "$#" -eq 0 ] || exit 1
  ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
fi
mkdir -p distribution
cp app/build/outputs/apk/debug/app-debug.apk distribution/BubbleBD-0.3.33.apk
printf '\nAPK : %s/distribution/BubbleBD-0.3.33.apk\n' "$PWD"

python3 scripts/package-source.py
