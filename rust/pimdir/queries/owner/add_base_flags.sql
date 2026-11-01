-- Fork of ADD_BASE_FLAGS (sha256 6496b7a3f88b80f7) for SQLite 3.18:
-- `ON CONFLICT DO NOTHING` (3.24) as add_flags rewrites it.
INSERT INTO binding_base_flag(collection, link_id, source, flag)
SELECT DISTINCT :collection, :link_id, :source, p.value FROM temp.param p
WHERE p.slot = 'flags'
  AND NOT EXISTS (SELECT 1 FROM binding_base_flag f
                  WHERE f.collection = :collection AND f.link_id = :link_id
                    AND f.source = :source AND f.flag = p.value);
