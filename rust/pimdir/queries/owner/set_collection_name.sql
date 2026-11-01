-- Fork of SET_COLLECTION_NAME (sha256 8ba4411ae5522ee9) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
--
-- What a collection is called, as opposed to how it is addressed. The id is
-- the key every foreign key cascades on and the name is a label, so this
-- moves one without touching the other. An owner namespacing its ids (§9.2)
-- records the bare name here, the separator being its own convention and not
-- one a reader can strip. Only the name is updated, so a declared kind and
-- the account survive.
UPDATE collections SET name = :name WHERE id = :collection;
INSERT INTO collections(id, account, kind, name)
SELECT :collection, :account, '', :name
WHERE NOT EXISTS (SELECT 1 FROM collections WHERE id = :collection);
