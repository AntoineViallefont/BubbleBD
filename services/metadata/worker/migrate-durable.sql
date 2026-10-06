-- Preserve existing successful searches, without deleting or replacing any fiche.
CREATE TABLE IF NOT EXISTS lookup_locks(key TEXT PRIMARY KEY,token TEXT NOT NULL,expires INTEGER NOT NULL);
UPDATE cache SET expires=0 WHERE CASE WHEN json_valid(body) THEN json_extract(body,'$.matched') ELSE 0 END=1;
