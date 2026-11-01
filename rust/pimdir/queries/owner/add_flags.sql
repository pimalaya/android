-- Fork of ADD_FLAGS (sha256 68b52ffcf4a83a42) for SQLite 3.18:
-- `ON CONFLICT DO NOTHING` (3.24) as an insert of the distinct flags the
-- placement lacks; any other constraint still raises, where `INSERT OR
-- IGNORE` would swallow a malformed flag.
--
-- The second half of retain_flags: adds every flag the `flags` slot names that
-- the placement lacks, a flag named twice once.
INSERT INTO placement_flag(collection, link_id, flag)
SELECT DISTINCT :collection, :link_id, p.value FROM temp.param p
WHERE p.slot = 'flags'
  AND NOT EXISTS (SELECT 1 FROM placement_flag f
                  WHERE f.collection = :collection AND f.link_id = :link_id
                    AND f.flag = p.value);
