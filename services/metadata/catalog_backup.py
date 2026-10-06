"""Owner-only local conversion: Cloudflare SQL backup -> CSV or merge-safe restore SQL."""
import argparse
import csv
import json
import re
import sqlite3
from pathlib import Path
from server import safe_source


def validated(key, expires, body):
    if not isinstance(key, str) or not re.fullmatch(r"[0-9a-f]{64}", key):
        raise ValueError("Clé de fiche invalide")
    expires = int(expires)
    if expires < 0 or not isinstance(body, str) or len(body.encode()) > 70000:
        raise ValueError("Fiche trop grande ou échéance invalide")
    record = json.loads(body)
    if not isinstance(record, dict) or type(record.get("matched")) is not bool:
        raise ValueError("Correspondance invalide")
    if record["matched"]:
        sources = record.get("sources")
        if not isinstance(sources, list) or not sources or any(not isinstance(s, dict) or not safe_source(s.get("url")) for s in sources):
            raise ValueError("Sources manquantes ou invalides")
        for name in ["title", "series", "number", "isbn", "artist", "writer", "publisher", "date", "genre", "synopsis"]:
            if not isinstance(record.get(name, ""), str) or len(record.get(name, "")) > (12000 if name == "synopsis" else 400):
                raise ValueError("Champ invalide")
        expires = 0
    return key, expires, json.dumps(record, ensure_ascii=False, separators=(",", ":"))


def read_rows(path):
    path = Path(path)
    if path.suffix.lower() == ".csv":
        with path.open(newline="", encoding="utf-8-sig") as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames != ["key", "expires", "body"]:
                raise ValueError("Colonnes attendues : key,expires,body")
            for row in reader:
                yield validated(row["key"], row["expires"], row["body"])
        return
    if path.suffix.lower() != ".sql":
        raise ValueError("Fichier de sauvegarde SQL ou CSV requis")
    db = sqlite3.connect(":memory:")
    db.set_authorizer(lambda action, *_: sqlite3.SQLITE_DENY if action in (sqlite3.SQLITE_ATTACH, sqlite3.SQLITE_DETACH) else sqlite3.SQLITE_OK)
    try:
        statement = ""
        with path.open(encoding="utf-8-sig") as stream:
            for line in stream:
                statement += line
                if sqlite3.complete_statement(statement):
                    db.execute(statement)
                    statement = ""
            if statement.strip():
                db.execute(statement)
        for row in db.execute("SELECT key,expires,body FROM cache ORDER BY key"):
            yield validated(*row)
    finally:
        db.close()


def quote(value):
    return "'" + value.replace("'", "''") + "'"


def convert(source, target, csv_output=False):
    source, target = Path(source), Path(target)
    if source.resolve() == target.resolve() or target.exists():
        raise ValueError("Choisir un nouveau fichier de sortie")
    # Validate the whole backup before producing an importable file.
    rows = list(read_rows(source))
    if len({row[0] for row in rows}) != len(rows):
        raise ValueError("La sauvegarde contient des clés en double")
    with target.open("x", newline="", encoding="utf-8") as stream:
        if csv_output:
            writer = csv.writer(stream)
            writer.writerow(["key", "expires", "body"])
            writer.writerows(rows)
        else:
            stream.write(Path(__file__).with_name("worker").joinpath("schema.sql").read_text())
            stream.write("-- Restore fiches only: never reset live quotas, leases or existing successful data.\n")
            for key, expires, body in rows:
                stream.write("INSERT INTO cache(key,expires,body) VALUES(" + quote(key) + "," + str(expires) + "," + quote(body) + ") ON CONFLICT(key) DO UPDATE SET expires=excluded.expires,body=excluded.body WHERE CASE WHEN json_valid(cache.body) THEN json_extract(cache.body,'$.matched') ELSE 0 END IS NOT 1 AND json_extract(excluded.body,'$.matched')=1;\n")
    return len(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["csv", "restore"])
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    count = convert(args.input, args.output, args.action == "csv")
    print(f"{count} fiches vérifiées ; fichier prêt : {args.output}")


if __name__ == "__main__":
    main()
