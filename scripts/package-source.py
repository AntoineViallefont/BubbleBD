"""Create a source bundle using an explicit allowlist; never include private data."""
from pathlib import Path
import re,zipfile
root=Path(__file__).resolve().parent.parent
version=re.search(r'val bubbleVersion = "([^"]+)"',(root/'app/build.gradle.kts').read_text()).group(1)
files=set()
files.add(root/'scripts/sign-release.py')
for name in ['LICENSE','NOTICE','docs/libarchive-LICENSE.txt','settings.gradle.kts','build.gradle.kts','gradle.properties','gradlew','app/build.gradle.kts','scripts/build.sh','scripts/test-device.sh','scripts/test-detection.sh','scripts/check-detection-corpus.py','scripts/package-source.py','scripts/verify-existing-release.py','scripts/run-metadata-local.py','scripts/prepare-metadata-worker.py']:
 files.add(root/name)
for name in ['server.py','mistral_provider.py','test_server.py','test_mistral.py','catalog_backup.py','test_catalog_backup.py','Dockerfile','README.md','LICENSE','.dockerignore']:
 files.add(root/'services/metadata'/name)
for name in ['worker.mjs','protocol.json','schema.sql','migrate-durable.sql','wrangler.jsonc','test-worker.mjs','README.md']:
 files.add(root/'services/metadata/worker'/name)
for directory in ['gradle/wrapper','app/src']:
 for file in (root/directory).rglob('*'):
  if file.is_file() and 'private' not in file.relative_to(root).parts:files.add(file)
out=root/f'distribution/BubbleBD-{version}-source.zip'
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as archive:
 for file in sorted(files):
  assert not any(p in file.parts for p in ['private','.git','.gradle'])
  archive.write(file,file.relative_to(root))
 archive.writestr('README.md',"""# BubbleBD — source

Native Android comic reader; local library, progress and embedded AI inference.
License: AGPL-3.0-or-later for application code; see LICENSE and NOTICE.
No user comics, private accounts, authentication tokens or signing keys included.

Build prerequisites: JDK 17+, Android SDK 36, NDK 28.2.13676358,
CMake 3.22.1. Android Studio can install these SDK packages.
Create local.properties with sdk.dir pointing to the local Android SDK.
Optional: onedrive.clientId=<your registered public Microsoft app identifier>.
Without it, OneDrive authentication is unavailable.
Distributed configuration (public URL, no secret):
metadata.endpoint=https://bubblebd-metadata.antoine-viallefont.workers.dev
Add that line to local.properties to enable the same background Mistral lookup.
An empty metadata.endpoint disables the shared service for offline development.
Mistral or Gemini API keys belong only on the server, never in Android configuration.
The service source and offline tests are under services/metadata/.

Build and JVM checks:
./scripts/build.sh

Android checks (dedicated emulator only):
./scripts/test-device.sh

Some private regression images are intentionally absent.
Original INT8 weights are included and unmodified. Model provenance and licensing:
app/src/main/assets/models/NOTICE.txt.
Gradle and CMake download their declared dependencies during the build.
""")
with zipfile.ZipFile(out) as archive:
 names=archive.namelist()
 assert not any('/private/' in n or n.endswith(('local.properties','.jks','.keystore','.apk')) for n in names)
 assert 'app/src/main/java/fr/bubblebd/HybridPanels.kt' in names
 assert 'app/src/main/assets/models/panels-int8.tflite' in names
print(out)
