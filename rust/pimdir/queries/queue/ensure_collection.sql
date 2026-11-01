-- Fork of ENSURE_COLLECTION (sha256 07de6bf2522edaa4) for SQLite 3.18:
-- the insert skips an id held, as `ON CONFLICT(id) DO NOTHING` (3.24) does,
-- and no other constraint: a NOT NULL or CHECK failure still raises, where
-- `INSERT OR IGNORE` would swallow it.
--
-- Guarantees the FK target exists before a source, item or checkpoint write.
-- Binds the account too, since a collection that appears without one is
-- invisible to every by-account read until it is set.
INSERT INTO collections(id, account, kind, name)
SELECT :collection, :account, '', :collection
WHERE NOT EXISTS (SELECT 1 FROM collections WHERE id = :collection);
