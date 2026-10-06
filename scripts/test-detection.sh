#!/bin/sh
# Whole private corpus and reader checks. A known failure remains a failure.
set -eu
run_suite() {
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
adb_tool="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
serial="${BUBBLEBD_TEST_SERIAL:-emulator-5580}"
avd_name="$($adb_tool -s "$serial" emu avd name | tr -d '\r' | head -n 1)"
[ "$avd_name" = BubbleBD_Test_API_36_1 ] || { printf 'Utiliser l’émulateur dédié BubbleBD_Test_API_36_1 (emulator-5580).\n'; exit 1; }
# Protect the complete run, including build provenance and exported reports.
# The device-only lock cannot prevent another suite from deleting these reports.
suite_lock="${TMPDIR:-/tmp}/bubblebd-${serial}.corpus-lock"
while ! mkdir "$suite_lock" 2>/dev/null; do
  if [ -f "$suite_lock/pid" ]; then
    suite_owner="$(cat "$suite_lock/pid")"
    if ! kill -0 "$suite_owner" 2>/dev/null; then
      rm -f "$suite_lock/pid"
      rmdir "$suite_lock" 2>/dev/null || true
      continue
    fi
  fi
  sleep 1
done
printf '%s\n' "$$" > "$suite_lock/pid"
trap 'rm -f "$suite_lock/pid"; rmdir "$suite_lock" 2>/dev/null || true' EXIT HUP INT TERM
python3 scripts/check-detection-corpus.py
run_dir="docs/detection/private/corpus/runs/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$run_dir"
./scripts/build.sh > "$run_dir/build.log" 2>&1
python3 scripts/capture-detection-build.py "$run_dir/reader-run-provenance.json"
"$adb_tool" -s "$serial" shell rm -rf /sdcard/Download/bubble-reader-corpus
"$adb_tool" -s "$serial" shell rm -f /sdcard/Download/bubble-reported-validation.json
"$adb_tool" -s "$serial" shell rm -f /sdcard/Download/bubble-permanent-corpus.json
"$adb_tool" -s "$serial" shell rm -f /sdcard/Download/bubble-permanent-corpus-rectangular.json
"$adb_tool" -s "$serial" shell rm -rf /sdcard/Download/bubble-native-focus
"$adb_tool" -s "$serial" shell rm -rf /sdcard/Download/bubble-caption-focus
"$adb_tool" -s "$serial" shell rm -rf /sdcard/Download/bubble-orthogonal-focus
"$adb_tool" -s "$serial" shell rm -f /sdcard/Download/bubble-orthogonal-focus.json
"$adb_tool" -s "$serial" shell rm -rf /sdcard/Download/bubble-shared-caption-focus
"$adb_tool" -s "$serial" shell rm -f /sdcard/Download/bubble-shared-caption-focus.json
android_status=0
BUBBLEBD_TEST_SERIAL="$serial" ./scripts/test-device.sh -Pandroid.testInstrumentationRunnerArguments.class='fr.bubblebd.PermanentCorpusTest,fr.bubblebd.ReportedPageValidationTest,fr.bubblebd.ReportedBorderlessPageTest,fr.bubblebd.ReaderCorpusAuditTest,fr.bubblebd.DeletedAlbumsTest,fr.bubblebd.CaptionFocusTest,fr.bubblebd.OrthogonalFocusTest,fr.bubblebd.SharedCaptionFocusTest,fr.bubblebd.MetadataInformationTest,fr.bubblebd.MetadataQueueTest,fr.bubblebd.GestureTest,fr.bubblebd.OctoberChangesTest,fr.bubblebd.ReaderIntegrationTest,fr.bubblebd.OpeningTest,fr.bubblebd.BdThequeSearchTest,fr.bubblebd.BdThequeMetadataTest,fr.bubblebd.InterfaceTest#metadataSearchIsCompactAndDistinguishesWaitingFromRunning,fr.bubblebd.InterfaceTest#longPressSeriesMarksAllVolumes,fr.bubblebd.InterfaceTest#albumSheetHidesResearchStatusInBothThemes,fr.bubblebd.InterfaceTest#compactHomeListAndDeletionConfirmation,fr.bubblebd.InterfaceTest#homeLibrarySettingsAndTheme,fr.bubblebd.InterfaceTest#pageControlsAndManualJumpKeepGuidedReading' > "$run_dir/android.log" 2>&1 || android_status=$?
"$adb_tool" -s "$serial" pull /sdcard/Download/bubble-permanent-corpus.json "$run_dir/measured.json"
"$adb_tool" -s "$serial" pull /sdcard/Download/bubble-permanent-corpus-rectangular.json "$run_dir/measured-rectangular.json"
artifact_status=0
"$adb_tool" -s "$serial" pull /sdcard/Download/bubble-reader-corpus "$run_dir/reader-corpus" || artifact_status=$?
"$adb_tool" -s "$serial" pull /sdcard/Download/bubble-reported-validation.json "$run_dir/reported-validation.json" || artifact_status=$?
"$adb_tool" -s "$serial" pull /sdcard/Download/bubble-native-focus "$run_dir/native-focus" || artifact_status=$?
"$adb_tool" -s "$serial" pull /sdcard/Download/bubble-caption-focus "$run_dir/caption-focus" || artifact_status=$?
"$adb_tool" -s "$serial" pull /sdcard/Download/bubble-caption-focus.json "$run_dir/caption-focus.json" || artifact_status=$?
"$adb_tool" -s "$serial" pull /sdcard/Download/bubble-orthogonal-focus "$run_dir/orthogonal-focus" || artifact_status=$?
"$adb_tool" -s "$serial" pull /sdcard/Download/bubble-orthogonal-focus.json "$run_dir/orthogonal-focus.json" || artifact_status=$?
"$adb_tool" -s "$serial" pull /sdcard/Download/bubble-shared-caption-focus "$run_dir/shared-caption-focus" || artifact_status=$?
"$adb_tool" -s "$serial" pull /sdcard/Download/bubble-shared-caption-focus.json "$run_dir/shared-caption-focus.json" || artifact_status=$?
report_status=0
python3 scripts/check-reader-corpus.py "$run_dir/reader-corpus" || report_status=$?
python3 scripts/check-detection-corpus.py --results "$run_dir/measured.json" --out "$run_dir" || report_status=$?
python3 scripts/check-detection-corpus.py --results "$run_dir/measured-rectangular.json" --out "$run_dir/rectangular" || report_status=$?
printf 'Rapport et aperçus : %s/%s\n' "$PWD" "$run_dir"
[ "$android_status" -eq 0 ] && [ "$report_status" -eq 0 ] && [ "$artifact_status" -eq 0 ]

}
run_suite "$@"
