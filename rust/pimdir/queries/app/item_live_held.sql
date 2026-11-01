-- Whether an item is live (neither removed nor retained) and holds a body.
SELECT 1 FROM items
WHERE collection = :collection AND link_id = :link_id
  AND deleted = 0 AND retained_at IS NULL AND object_hash IS NOT NULL;
