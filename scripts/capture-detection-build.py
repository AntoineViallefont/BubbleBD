"""Freeze the build and reference identity before the Android run."""
from pathlib import Path
import hashlib, json, re, sys
from datetime import datetime, timezone
root = Path(__file__).resolve().parent.parent
version = re.search(r'val bubbleVersion = "([^"]+)"', (root / 'app/build.gradle.kts').read_text()).group(1)
digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
record = dict(version=version, started_utc=datetime.now(timezone.utc).isoformat(),
              engine_sha256={str(p.relative_to(root)): digest(p) for p in (root / 'app/src/main/java/fr/bubblebd').glob('*.kt')},
              apk_sha256=digest(root / f'distribution/BubbleBD-{version}.apk'),
              reader_reference_manifest_sha256=digest(root / 'app/src/androidTest/assets/private/reader-audit/manifest.json'),
              reader_reference_sha256={str(p.relative_to(root)): digest(p) for p in (root / 'app/src/androidTest/assets/private/reader-audit').glob('*.json')},
              validation_sha256={str(p.relative_to(root)): digest(p) for p in [
                  *(root / 'app/src/androidTest/java/fr/bubblebd').glob('*.kt'),
                  root / 'scripts/check-reader-corpus.py', root / 'scripts/check-detection-corpus.py']},
              status='unqualified until checks and coverage pass')
Path(sys.argv[1]).write_text(json.dumps(record, indent=2) + '\n')
