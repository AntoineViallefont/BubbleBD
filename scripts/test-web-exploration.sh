#!/bin/sh
# Separate opt-in exploration; never promote an AI proposal to user approval.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
root=docs/detection/private/exploration-web-20261003
examples=$(python3 - "$root" <<'PY'
from pathlib import Path
import json,re,sys,shutil
root=Path(sys.argv[1]);manifest=json.loads((root/'sources.json').read_text())
assets=Path('app/src/androidTest/assets/private/exploration-web');assets.mkdir(parents=True,exist_ok=True)
ids=[]
for item in manifest['examples']:
    if item['state']=='excluded':continue
    key=item['id'];assert re.fullmatch('[a-z0-9-]+',key)
    shutil.copy2(root/f'{key}-page.png',assets/f'{key}.png');ids.append(key)
print(','.join(ids))
PY
)
rtl_examples=$(python3 - "$root" <<'PYCODE'
from pathlib import Path
import json,sys
root=Path(sys.argv[1]);items=json.loads((root/'sources.json').read_text())['examples']
print(','.join(x['id'] for x in items if x['state']!='excluded' and json.loads((root/x['reference']).read_text())['reading_direction']=='right_to_left'))
PYCODE
)
run_dir="$root/runs/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$run_dir"
adb_tool="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
serial="${BUBBLEBD_TEST_SERIAL:-emulator-5580}"
avd_name="$($adb_tool -s "$serial" emu avd name | tr -d '\r' | head -n 1)"
[ "$avd_name" = BubbleBD_Test_API_36_1 ] || { printf 'Émulateur dédié BubbleBD_Test_API_36_1 requis.\n'; exit 1; }
"$adb_tool" -s "$serial" shell rm -f /sdcard/Download/bubble-web-exploration.json
./scripts/test-device.sh -Pandroid.testInstrumentationRunnerArguments.class='fr.bubblebd.WebExplorationTest#measureRequestedExamples' "-Pandroid.testInstrumentationRunnerArguments.webExamples=$examples" "-Pandroid.testInstrumentationRunnerArguments.webRtlExamples=$rtl_examples" "-Pandroid.testInstrumentationRunnerArguments.webDebugExamples=${BUBBLEBD_WEB_DEBUG:-}" > "$run_dir/android.log" 2>&1
"$adb_tool" -s "$serial" pull /sdcard/Download/bubble-web-exploration.json "$run_dir/measured.json"
python3 - "$root" "$run_dir" <<'PY'
from pathlib import Path
import json,sys
root,run=map(Path,sys.argv[1:]);rows=[];sources={x['id']:x for x in json.loads((root/'sources.json').read_text())['examples']}
for actual in json.loads((run/'measured.json').read_text()):
    assert 'error' not in actual,actual
    ref=json.loads((root/sources[actual['id']]['reference']).read_text())
    rows.append(dict(id=actual['id'],reference_count=len(ref['frames']),measured_count=len(actual['frames']),count_matches=len(ref['frames'])==len(actual['frames']),seconds=actual['seconds'],user_validated=ref.get('user_validated',False)))
(run/'comparison.json').write_text(json.dumps(dict(scope='Comptes seuls, pas une validation des contours/bulles/masques',examples=rows),ensure_ascii=False,indent=2)+'\n')
for row in rows:print(f"{row['id']} : {row['measured_count']}/{row['reference_count']} zones ; {row['seconds']:.2f} s ; validation humaine : {row['user_validated']}")
PY
printf 'Mesures : %s/%s\n' "$PWD" "$run_dir"

python3 "$root/report.py" "$run_dir"

# Preserve successful machine counts without treating AI annotations as approval.
python3 - "$root" "$run_dir" <<'PYCODE'
from pathlib import Path
import hashlib,json,sys
root,run=map(Path,sys.argv[1:])
path=root/'baseline-comptes-stables-20261003.json'
if path.exists():
 baseline=json.loads(path.read_text())
 assert hashlib.sha256((root/baseline['source']).read_bytes()).hexdigest()==baseline['source_sha256'],'Mesure de référence modifiée'
 current={x['id']:x for x in json.loads((run/'measured.json').read_text())}
 failures=[x for x in baseline['examples'] if x['id'] not in current or len(current[x['id']]['frames'])!=x['count']]
 (run/'stability.json').write_text(json.dumps(dict(scope=baseline['scope'],checked=len(baseline['examples']),passed=not failures,failures=failures),ensure_ascii=False,indent=2)+'\n')
 print(f"Stabilité des décomptes : {len(baseline['examples'])-len(failures)}/{len(baseline['examples'])}")
 if failures:sys.exit('Régression des décomptes web : '+', '.join(x['id'] for x in failures))
PYCODE

if [ -n "${BUBBLEBD_WEB_DEBUG:-}" ]; then
  python3 - "$run_dir" <<'PYCODE'
from pathlib import Path
import json,sys,subprocess,os
run=Path(sys.argv[1]);adb=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))/'platform-tools/adb'
for row in json.loads((run/'measured.json').read_text()):
 if 'diagnostic' not in row:continue
 key=row['id'];subprocess.run([str(adb),'-s',os.environ.get('BUBBLEBD_TEST_SERIAL','emulator-5580'),'pull',f'/sdcard/Download/bubble-web-{key}.rgb',str(run/f'{key}.rgb')],check=True)
 (run/f'{key}-predictions.tsv').write_text('\n'.join('\t'.join(str(v).lower() for v in p) for p in row['diagnostic']['predictions'])+'\n')
PYCODE
fi
