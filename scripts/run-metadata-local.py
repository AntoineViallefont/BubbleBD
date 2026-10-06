"""Private local validation server. No API key on the command line or in source."""
from pathlib import Path
import os
import sys
import stat

root = Path(__file__).resolve().parent.parent
private = Path.home() / ".config/bubblebd"
key_file = private / "secrets/mistral-api-key"
if not key_file.is_file():
    raise SystemExit("Clé Mistral absente. Ne pas la copier dans le projet ni dans l’application.")
if stat.S_IMODE(key_file.stat().st_mode) & 0o077:
    raise SystemExit("Le fichier de clé doit être privé (permissions 600).")
key = key_file.read_text().strip()
if not key:
    raise SystemExit("Clé Mistral vide.")
os.environ["MISTRAL_API_KEY"] = key
os.environ["METADATA_PROVIDER"] = "mistral"
os.environ["MISTRAL_MODEL"] = "mistral-small-latest"
sys.path.insert(0, str(root / "services/metadata"))
from server import Catalog, Handler, ThreadingHTTPServer

private.mkdir(parents=True, exist_ok=True)
database = private / "mistral-catalog.sqlite"
Handler.catalog = Catalog(str(database))
database.chmod(0o600)
print("Validation locale : http://127.0.0.1:8080 — aucun accès depuis Internet.")
print("La facturation Mistral doit rester désactivée sur le compte.")
ThreadingHTTPServer(("127.0.0.1", 8080), Handler).serve_forever()
