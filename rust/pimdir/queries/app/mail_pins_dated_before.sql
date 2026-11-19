-- The bodies evict_before may let go of in one mail collection below a date
-- (STORAGE §11.3), at most :limit of them: every body its candidates pin,
-- the live unconflicted items whose `date` is older than :before, through
-- the item itself and each of its bindings (§5), and the diverging body of
-- a file stand-in a reference ties to one of them, which the delete's
-- triggers may take with it (§14.3). A superset of what the eviction
-- releases, read before it, so recount_objects over these settles every
-- count the eviction moved.
WITH evicted(id, link_id) AS (
  SELECT id, link_id FROM items
  WHERE collection = :collection AND deleted = 0 AND conflicted = 0
    AND id IN (SELECT item FROM mail_summary WHERE date < :before))
SELECT object_hash FROM items
WHERE id IN (SELECT id FROM evicted) AND object_hash IS NOT NULL
UNION SELECT base_object FROM bindings
WHERE item IN (SELECT id FROM evicted) AND base_object IS NOT NULL
UNION SELECT conflict_object FROM bindings
WHERE item IN (SELECT id FROM evicted) AND conflict_object IS NOT NULL
UNION SELECT shared_object FROM bindings
WHERE item IN (SELECT id FROM evicted) AND shared_object IS NOT NULL
UNION SELECT f.conflict_object FROM item_reference r
JOIN items f ON f.link_id = r.to_link_id
WHERE r.from_link_id IN (SELECT link_id FROM evicted)
  AND r.from_kind = 'message/rfc822' AND r.to_kind = 'application/octet-stream'
  AND f.conflict_object IS NOT NULL
UNION SELECT f.conflict_object FROM item_reference r
JOIN items f ON f.link_id = r.from_link_id
WHERE r.to_link_id IN (SELECT link_id FROM evicted)
  AND r.to_kind = 'message/rfc822' AND r.from_kind = 'application/octet-stream'
  AND f.conflict_object IS NOT NULL
LIMIT :limit;
