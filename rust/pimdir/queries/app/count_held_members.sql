-- How many live items of a collection hold a body.
SELECT count(*) FROM items
WHERE collection = :collection AND deleted = 0 AND retained_at IS NULL
  AND object_hash IS NOT NULL;
