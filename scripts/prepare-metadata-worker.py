"""Prepare the public AGPL service archive, without credentials or private data."""
from pathlib import Path
import zipfile
import base64
import json

root = Path(__file__).resolve().parent.parent
base = root / "services/metadata"
worker = base / "worker"
output = worker / "public/source.zip"
output.parent.mkdir(exist_ok=True)
files = [base / name for name in ("server.py", "mistral_provider.py", "test_server.py", "test_mistral.py", "catalog_backup.py", "test_catalog_backup.py", "Dockerfile", "LICENSE", "README.md")]
files += [worker / name for name in ("worker.mjs", "protocol.json", "schema.sql", "migrate-durable.sql", "wrangler.jsonc", "test-worker.mjs", "README.md")]
files += [root / "scripts/prepare-metadata-worker.py"]
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in files:
        archive.write(path, path.relative_to(root))
with zipfile.ZipFile(output) as archive:
    assert not any("private" in name or name.endswith((".pdf", ".jpg", ".apk", "local.properties")) for name in archive.namelist())
print(output)
# The dashboard editor accepts one bundled module. Embed the same public source
# archive so /source remains available without uploading an asset binding.
entry = worker / "dashboard-entry.mjs"
entry.write_text("import worker from './worker.mjs';\nconst source = " + json.dumps(base64.b64encode(output.read_bytes()).decode()) + ";\nexport default {fetch(request,env){return worker.fetch(request,{...env,SOURCE_B64:source});}};\n")
configuration = json.loads((worker / "wrangler.jsonc").read_text())
configuration["main"] = "dashboard-entry.mjs"
configuration.pop("assets", None)
(worker / "dashboard.jsonc").write_text(json.dumps(configuration, indent=2) + "\n")
