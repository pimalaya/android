-- The staged move a pending create targets: a bound tombstone, not retained,
-- of its identity (:link_id, or :key a minted key was minted over, NULL for
-- none) in another collection, its collection and handle.
SELECT i.collection, b.handle FROM items i
JOIN bindings b ON b.item = i.id
               AND b.source = :source
WHERE i.link_id IN (:link_id, :key) AND i.collection <> :collection
  AND i.deleted = 1 AND i.retained_at IS NULL AND b.base_present = 1
LIMIT 1;
