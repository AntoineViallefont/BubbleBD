import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from catalog_backup import convert


class BackupTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.fiche={"matched":True,"title":"L'album ;\nTitre","synopsis":"Résumé, complet\nDeux lignes.","sources":[{"url":"https://publisher.example/tome"}]}
        self.db=sqlite3.connect(":memory:");self.db.execute("CREATE TABLE cache(key TEXT PRIMARY KEY,expires INTEGER NOT NULL,body TEXT NOT NULL)")
        self.db.execute("INSERT INTO cache VALUES(?,?,?)",("a"*64,1,json.dumps(self.fiche)))
        self.backup=self.root/'backup.sql';self.backup.write_text('\n'.join(self.db.iterdump()))
    def tearDown(self):
        self.db.close();self.temp.cleanup()
    def testSqlAndCsvRestorePreserveSourcesAndAccentsWithoutExpiry(self):
        csv=self.root/'backup.csv';merge=self.root/'merge.sql'
        self.assertEqual(1,convert(self.backup,csv,True));self.assertEqual(1,convert(csv,merge))
        target=sqlite3.connect(":memory:");target.executescript(merge.read_text())
        row=target.execute("SELECT expires,body FROM cache").fetchone()
        self.assertEqual(0,row[0]);self.assertEqual(self.fiche,json.loads(row[1]));target.close()
    def testRepeatedImportKeepsNewFichesAndLiveQuotaAndPromotesOnlyNegative(self):
        merge=self.root/'merge.sql';convert(self.backup,merge)
        target=sqlite3.connect(":memory:");target.executescript(merge.read_text())
        target.execute("INSERT INTO quota VALUES(1,100)")
        newer=self.fiche|{"synopsis":"Correction plus récente"}
        target.execute("UPDATE cache SET body=?",(json.dumps(newer),));target.executescript(merge.read_text())
        self.assertEqual(newer,json.loads(target.execute("SELECT body FROM cache").fetchone()[0]))
        self.assertEqual(100,target.execute("SELECT calls FROM quota").fetchone()[0])
        target.execute("UPDATE cache SET body='{}'");target.executescript(merge.read_text())
        self.assertEqual(self.fiche,json.loads(target.execute("SELECT body FROM cache").fetchone()[0]));target.close()
    def testInvalidSourceOrDuplicateFailsBeforeAnyOutput(self):
        for rows in [[('a'*64,1,json.dumps(self.fiche|{"sources":[]}))],[('a'*64,1,json.dumps(self.fiche))]*2]:
            import csv
            source=self.root/'invalid.csv';out=self.root/'invalid.sql'
            with source.open('w',newline='') as stream:
                writer=csv.writer(stream);writer.writerow(['key','expires','body']);writer.writerows(rows)
            with self.assertRaises(ValueError):convert(source,out)
            self.assertFalse(out.exists())
    def testBackupCannotAttachAnotherFileOrOverwriteTheInput(self):
        source=self.root/'attached.sql';source.write_text("ATTACH DATABASE '/tmp/private.sqlite' AS secret;")
        with self.assertRaises(sqlite3.DatabaseError):convert(source,self.root/'out.sql')
        with self.assertRaises(ValueError):convert(self.backup,self.backup)


if __name__=='__main__':unittest.main()
