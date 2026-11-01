-- Fork of SET_CAPABILITY (sha256 cd3e9bec2c91a570) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
--
-- One row of a source's declaration (§15.6), after delete_capabilities in the
-- same transaction. `account` is the one the source syncs for, NULL in a
-- single-account store. `collection` NULL is the source-wide row; a collection
-- overrides it there alone. `support` is 'full', 'partial' or 'none', `detail`
-- what a human reads about it, or NULL.
UPDATE capabilities SET account = :account, support = :support, detail = :detail
WHERE source = :source AND ifnull(collection, '') = ifnull(:collection, '')
  AND capability = :capability;
INSERT INTO capabilities(account, source, collection, capability, support, detail)
SELECT :account, :source, :collection, :capability, :support, :detail
WHERE NOT EXISTS (SELECT 1 FROM capabilities
                  WHERE source = :source AND ifnull(collection, '') = ifnull(:collection, '')
                    AND capability = :capability);
