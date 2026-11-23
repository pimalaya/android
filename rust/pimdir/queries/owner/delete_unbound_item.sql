-- Fork of DELETE_UNBOUND_ITEM (sha256 34ef8e42ec5e3c66) for SQLite 3.18:
-- `RETURNING` (3.35) as a read of the row, then its delete, in one
-- transaction.
--
-- Deletes an item no source binds (§14.3): a file of a folder on the device,
-- a stand-in or a time entry, which no sync removes and no retention keeps.
-- A bound item is refused (no row): its source removes it. Returns the hashes
-- it pinned, for release_pins in the same transaction.
SELECT (SELECT o.hash FROM objects o WHERE o.id = items.object) AS object_hash,
       (SELECT o.hash FROM objects o WHERE o.id = items.conflict_object) AS conflict_object FROM items
WHERE collection = :collection AND link_id = :link_id
  AND NOT EXISTS (SELECT 1 FROM bindings b
                  WHERE b.item = items.id);
DELETE FROM items
WHERE collection = :collection AND link_id = :link_id
  AND NOT EXISTS (SELECT 1 FROM bindings b
                  WHERE b.item = items.id);
