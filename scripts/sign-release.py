"""Sign a release with an existing private key outside the source checkout."""
from pathlib import Path
import os
import re
import subprocess

root = Path(__file__).resolve().parent.parent
key_dir = Path(os.environ.get("BUBBLEBD_SIGNING_DIR", str(Path.home() / ".config/bubblebd/signing"))).resolve()
if key_dir.is_relative_to(root):
    raise SystemExit("La clé de publication doit rester hors du projet.")
keystore = key_dir / "release.p12"
password = key_dir / "password"
if not keystore.is_file() or not password.is_file():
    raise SystemExit("Clé absente : configurer BUBBLEBD_SIGNING_DIR (release.p12 et password). Voir docs/RELEASING.md.")
sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
tools = sdk / "build-tools/36.0.0"
version = re.search(r'val bubbleVersion = "([^"]+)"', (root / "app/build.gradle.kts").read_text()).group(1)
unsigned = root / "app/build/outputs/apk/release/app-release-unsigned.apk"
out = root / f"distribution/BubbleBD-{version}-beta.apk"
out.parent.mkdir(exist_ok=True)
subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(unsigned)], check=True)
subprocess.run([str(tools / "apksigner"), "sign", "--ks", str(keystore), "--ks-key-alias", "bubblebd",
                "--ks-pass", f"file:{password}", "--out", str(out), str(unsigned)], check=True)
subprocess.run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(out)], check=True)
print(out)
