-- Indexes a body filed in the blob store, its refcount the caller's to
-- settle; a body indexed already keeps its row as it is, which store_object
-- would restate.
INSERT INTO objects(hash, size, refcount)
SELECT :hash, :size, 0
WHERE NOT EXISTS (SELECT 1 FROM objects WHERE hash = :hash);
