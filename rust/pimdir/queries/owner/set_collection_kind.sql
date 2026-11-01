-- Fork of SET_COLLECTION_KIND (sha256 874a88750dd6e560) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
--
-- Unlike ensure_collection this is deliberate, so it does overwrite a kind
-- already declared; the row's name is untouched.
UPDATE collections SET kind = :kind WHERE id = :collection;
INSERT INTO collections(id, account, kind, name)
SELECT :collection, :account, :kind, :collection
WHERE NOT EXISTS (SELECT 1 FROM collections WHERE id = :collection);
