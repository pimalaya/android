-- The bodies release_bases_before and release_before may let go of in a set
-- of mail collections (the `collections` slot) below a sort key (STORAGE
-- §11.4), at most :limit of them: the body of every live item below
-- :before (NULL for no ceiling) holding one, which is the one body both
-- statements take off an item and its bindings. A superset of what the
-- release lets go of, read before it, so recount_objects over these
-- settles every count the release moved.
SELECT DISTINCT object_hash FROM items
WHERE collection IN (SELECT value FROM temp.param WHERE slot = 'collections')
  AND deleted = 0 AND object_hash IS NOT NULL
  AND sort_key < coalesce(:before, x'')
LIMIT :limit;
