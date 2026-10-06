#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ] && [ -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]; then
  export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
fi
# Never reset a shared emulator or a physical phone.
export ANDROID_SERIAL="${BUBBLEBD_TEST_SERIAL:-emulator-5580}"
adb_tool="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
avd_name="$($adb_tool -s "$ANDROID_SERIAL" emu avd name | tr -d '\r' | head -n 1)"
[ "$avd_name" = BubbleBD_Test_API_36_1 ] || { printf 'Démarrer la cible dédiée BubbleBD_Test_API_36_1 sur le port 5580.\n'; exit 1; }
# Serialize test installations on the dedicated AVD, including concurrent callers.
test_lock="${TMPDIR:-/tmp}/bubblebd-${ANDROID_SERIAL}.test-lock"
while ! mkdir "$test_lock" 2>/dev/null; do
  if [ -f "$test_lock/pid" ]; then
    test_owner="$(cat "$test_lock/pid")"
    if ! kill -0 "$test_owner" 2>/dev/null; then
      rm -f "$test_lock/pid"
      rmdir "$test_lock" 2>/dev/null || true
      continue
    fi
  fi
  sleep 1
done
printf '%s\n' "$$" > "$test_lock/pid"
trap 'rm -f "$test_lock/pid"; rmdir "$test_lock" 2>/dev/null || true' EXIT HUP INT TERM
printf 'Ces tests réinitialisent la bibliothèque de BubbleBD sur la cible de test. Utiliser un émulateur dédié.\n'
if [ "${1:-}" = --update-release ]; then
  [ "$#" -eq 3 ] || exit 1
  ./gradlew :app:assembleDebugAndroidTest --console=plain
  python3 scripts/test-release-update.py "$adb_tool" "$ANDROID_SERIAL" "$2" "$3"
  exit 0
fi
if [ "${1:-}" = --smoke-release ]; then
  [ "$#" -eq 2 ] && [ -f "$2" ] || exit 1
  # This mode replaces only BubbleBD on the already verified dedicated test AVD.
  "$adb_tool" -s "$ANDROID_SERIAL" uninstall fr.bubblebd >/dev/null 2>&1 || true
  "$adb_tool" -s "$ANDROID_SERIAL" install "$2"
  "$adb_tool" -s "$ANDROID_SERIAL" logcat -c
  "$adb_tool" -s "$ANDROID_SERIAL" shell am start -W -n fr.bubblebd/.MainActivity
  sleep 3
  "$adb_tool" -s "$ANDROID_SERIAL" shell pidof fr.bubblebd
  "$adb_tool" -s "$ANDROID_SERIAL" shell uiautomator dump /sdcard/bubblebd-release-ui.xml >/dev/null
  "$adb_tool" -s "$ANDROID_SERIAL" shell cat /sdcard/bubblebd-release-ui.xml
  exit 0
fi
# Historical UI assertions are French; English scenarios opt in explicitly.
BUBBLEBD_TEST_LANGUAGE="${BUBBLEBD_TEST_LANGUAGE:-fr}"
if [ -n "$BUBBLEBD_TEST_LANGUAGE" ]; then
  case "$BUBBLEBD_TEST_LANGUAGE" in en|fr) ;; *) printf 'Langue de test : en ou fr uniquement.\n'; exit 1 ;; esac
  ./gradlew :app:installDebug --console=plain
  "$adb_tool" -s "$ANDROID_SERIAL" shell cmd locale set-app-locales fr.bubblebd --user 0 --locales "$BUBBLEBD_TEST_LANGUAGE"
fi
./gradlew :app:connectedDebugAndroidTest --console=plain "$@"
