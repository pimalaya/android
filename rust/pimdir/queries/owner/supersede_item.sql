-- Fork of SUPERSEDE_ITEM (sha256 de20b34f92792c52) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
--
-- Records that an `add` superseded an item (§15.3), in the transaction that
-- applies the add and tombstones the item: the item at :collection and
-- :link_id, the add's at :by_collection and :by_link_id. A second add
-- superseding the same item replaces the pair.
UPDATE supersessions SET by_collection = :by_collection, by_link_id = :by_link_id
WHERE collection = :collection AND link_id = :link_id;
INSERT INTO supersessions (collection, link_id, by_collection, by_link_id)
SELECT :collection, :link_id, :by_collection, :by_link_id
WHERE NOT EXISTS (SELECT 1 FROM supersessions
                  WHERE collection = :collection AND link_id = :link_id);
