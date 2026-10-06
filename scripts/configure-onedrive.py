#!/usr/bin/env python3
"""Set the public Microsoft application identifier; no secrets are requested."""
from pathlib import Path
from uuid import UUID
import re

root = Path(__file__).resolve().parent.parent
value = input("ID d’application (client) Microsoft : ").strip()
try:
    client_id = str(UUID(value))
except ValueError:
    raise SystemExit("Identifiant invalide. Copier l’ID d’application, pas un secret.")
path = root / "local.properties"
content = path.read_text() if path.exists() else ""
content = re.sub(r"(?m)^onedrive\.clientId=.*\n?", "", content)
path.write_text(content.rstrip() + "\nonedrive.clientId=" + client_id + "\n")
print("Configuration enregistrée. Compiler avec ./scripts/build.sh")
