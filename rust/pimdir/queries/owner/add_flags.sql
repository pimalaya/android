-- Fork of ADD_FLAGS (sha256 f8ba8c662056a9cf) for SQLite 3.18:
-- `ON CONFLICT DO NOTHING` (3.24) as an insert of the distinct flags the
-- placement lacks; any other constraint still raises, where `INSERT OR
-- IGNORE` would swallow a malformed flag. The slot may hold the whole set:
-- the system flags, which set_flags_known writes as the item's bits
-- (STORAGE §13), are no row and are skipped, so a set reaches the store
-- whole and no Java reads which flag is which.
--
-- The second half of retain_flags: adds every flag the `flags` slot names that
-- the placement lacks, a flag named twice once.
INSERT INTO placement_flag(item, flag)
SELECT DISTINCT i.id, p.value FROM items i, temp.param p
WHERE i.collection = :collection AND i.link_id = :link_id AND p.slot = 'flags'
  AND p.value NOT IN ('\Seen', '\Answered', '\Flagged', '\Draft', '\Deleted')
  AND NOT EXISTS (SELECT 1 FROM placement_flag f
                  WHERE f.item = i.id AND f.flag = p.value);
