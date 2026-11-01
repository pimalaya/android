-- Fork of RECOMPUTE_REFCOUNTS (sha256 adf5ae1ec4245abc) for SQLite 3.18:
-- `UPDATE ... FROM` (3.33) as a sum of six counts per object, each a seek on
-- the partial index of its pointer (STORAGE §4.3: the equality implies the
-- pointer set), so the recount stays linear in the objects: one correlated
-- count over the six pointers' union would scan them per object.
--
-- Recount from the six pointers that pin an object in one grouped pass, linear
-- in the pointers (§7): a correlated subquery per object would be quadratic,
-- the OR across object_hash and conflict_object being a disjunction no index
-- serves. The left join is what settles an object no pointer names any more,
-- counting zero rather than leaving it unvisited (§5).
UPDATE objects SET refcount =
    (SELECT count(*) FROM items WHERE object_hash = objects.hash)
  + (SELECT count(*) FROM items WHERE conflict_object = objects.hash)
  + (SELECT count(*) FROM bindings WHERE base_object = objects.hash)
  + (SELECT count(*) FROM bindings WHERE conflict_object = objects.hash)
  + (SELECT count(*) FROM bindings WHERE shared_object = objects.hash)
  + (SELECT count(*) FROM queue WHERE object_hash = objects.hash)
WHERE refcount !=
    (SELECT count(*) FROM items WHERE object_hash = objects.hash)
  + (SELECT count(*) FROM items WHERE conflict_object = objects.hash)
  + (SELECT count(*) FROM bindings WHERE base_object = objects.hash)
  + (SELECT count(*) FROM bindings WHERE conflict_object = objects.hash)
  + (SELECT count(*) FROM bindings WHERE shared_object = objects.hash)
  + (SELECT count(*) FROM queue WHERE object_hash = objects.hash);
