-- Fork of RELEASE_BASES_BEFORE (sha256 99002c0ddb3caf73) for SQLite 3.18:
-- `UPDATE ... FROM` (3.33) as correlated reads of the binding's item, the
-- join's predicate an EXISTS over it, and the bindings sought by the
-- collections the slot names, as the join seeks them.
--
-- The bindings' half of release_before (§11.4), run first in its transaction:
-- a base or a shared body naming the item's own body lets go of it with the
-- item, a message having no cross-source merge to read it for (SYNC.md §9).
-- The binding stays based, base_present alone saying so (§13). The predicate
-- is release_before's, which this statement leaves true.
UPDATE bindings SET
       base_object = CASE WHEN bindings.base_object = (SELECT i.object_hash FROM items i
                                                       WHERE i.id = bindings.item)
                          THEN NULL ELSE bindings.base_object END,
       shared_object = CASE WHEN bindings.shared_object = (SELECT i.object_hash FROM items i
                                                           WHERE i.id = bindings.item)
                            THEN NULL ELSE bindings.shared_object END
WHERE bindings.collection IN (SELECT value FROM temp.param WHERE slot = 'collections')
  AND EXISTS (
    SELECT 1 FROM items i
    WHERE bindings.item = i.id
      AND (bindings.base_object = i.object_hash OR bindings.shared_object = i.object_hash)
      AND i.collection IN (SELECT value FROM temp.param WHERE slot = 'collections')
      AND i.deleted = 0 AND i.conflicted = 0 AND i.object_hash IS NOT NULL
      AND i.sort_key < coalesce(:before, x'')
      AND (SELECT kind FROM collections c WHERE c.id = i.collection) = 'message/rfc822'
      AND EXISTS (SELECT 1 FROM bindings b
                  WHERE b.item = i.id)
      AND NOT EXISTS (SELECT 1 FROM bindings b
                      WHERE b.item = i.id
                        AND (b.conflicted = 1
                             OR b.base_present = 0
                             OR (b.base_object IS NOT NULL AND b.base_object != i.object_hash)))
      AND NOT EXISTS (SELECT 1 FROM sources s
                      WHERE s.collection = i.collection
                        AND NOT EXISTS (SELECT 1 FROM bindings b
                                        WHERE b.item = i.id
                                          AND b.source = s.source)));
