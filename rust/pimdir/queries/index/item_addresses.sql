-- A live item's address rows (STORAGE Annex A.6), by its collection and seq:
-- what load_addresses reads, in the round the item's summary is read in.
SELECT a.role, a.position, a.address, a.name FROM store.items i
JOIN store.item_address a ON a.item = i.id
WHERE i.collection = :collection AND i.seq = :seq AND i.deleted = 0
ORDER BY a.role, a.position;
