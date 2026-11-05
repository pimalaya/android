-- Fork of SUPERSEDE_ITEM (sha256 8e22115c1df49e57) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
--
-- Records that an `add` superseded an item (§15.3), in the transaction that
-- applies the add and tombstones the item: the item at :collection and
-- :link_id, the add's at :by_collection and :by_link_id. A second add
-- superseding the same item replaces the pair.
UPDATE supersessions
SET by_item = (SELECT id FROM items WHERE collection = :by_collection AND link_id = :by_link_id)
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id);
INSERT INTO supersessions (item, by_item)
SELECT i.id, (SELECT id FROM items WHERE collection = :by_collection AND link_id = :by_link_id)
FROM items i
WHERE i.collection = :collection AND i.link_id = :link_id
  AND NOT EXISTS (SELECT 1 FROM supersessions WHERE item = i.id);
