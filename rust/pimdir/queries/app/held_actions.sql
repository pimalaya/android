-- The actions a person staged on delivered creates (pimdir SYNC §2), by
-- collection: a delete or a move tombstoning a create its server took before
-- listing it, held until a listing names the member. How many, and the
-- highest change stamp among them (STORAGE §4.5). A delivered create with
-- nothing staged on it, and a create waiting for its scope, are none.
SELECT b.collection, count(*), max(i.changed) FROM bindings b
JOIN items i ON i.id = b.item
WHERE b.handle >= char(1) AND b.handle < char(2)
  AND b.base_present = 1 AND i.deleted = 1 AND i.retained_at IS NULL
GROUP BY b.collection
ORDER BY b.collection;
