-- recompute_refcounts over the bodies the `hashes` slot names alone: each
-- one's count restated from the six pointers that pin it (STORAGE §5, §7),
-- a seek on each pointer's partial index, as the fork of recompute_refcounts
-- counts. The O(changes) settling of §14 for a write that knows which
-- bodies it touched: a count no write of it moved stays as it is.
UPDATE objects SET refcount =
    (SELECT count(*) FROM items WHERE object = objects.id)
  + (SELECT count(*) FROM items WHERE conflict_object = objects.id)
  + (SELECT count(*) FROM bindings WHERE base_object = objects.id)
  + (SELECT count(*) FROM bindings WHERE conflict_object = objects.id)
  + (SELECT count(*) FROM bindings WHERE shared_object = objects.id)
  + (SELECT count(*) FROM queue WHERE object = objects.id)
WHERE hash IN (SELECT value FROM temp.param WHERE slot = 'hashes')
  AND refcount !=
    (SELECT count(*) FROM items WHERE object = objects.id)
  + (SELECT count(*) FROM items WHERE conflict_object = objects.id)
  + (SELECT count(*) FROM bindings WHERE base_object = objects.id)
  + (SELECT count(*) FROM bindings WHERE conflict_object = objects.id)
  + (SELECT count(*) FROM bindings WHERE shared_object = objects.id)
  + (SELECT count(*) FROM queue WHERE object = objects.id);
