-- Fork of UPSERT_PLACEMENT (sha256 672e151e4f40a631) for SQLite 3.18: the
-- upsert (3.24) as an update of the row by key, then its insert where none
-- is there, and the RETURNING (3.35) as a read by key, run in one
-- transaction. The body is named by its hash and written as its object.id,
-- a hash the index does not hold resolving to 0, which the CHECK refuses.
UPDATE placement SET link_id = :link_id,
       object = CASE WHEN :hash IS NULL THEN NULL
                     ELSE coalesce((SELECT id FROM object WHERE hash = :hash), 0) END
WHERE collection = :collection AND seq = :seq;
INSERT INTO placement(collection, seq, link_id, object)
SELECT :collection, :seq, :link_id,
       CASE WHEN :hash IS NULL THEN NULL
            ELSE coalesce((SELECT id FROM object WHERE hash = :hash), 0) END
WHERE NOT EXISTS (SELECT 1 FROM placement WHERE collection = :collection AND seq = :seq);
SELECT id FROM placement WHERE collection = :collection AND seq = :seq;
