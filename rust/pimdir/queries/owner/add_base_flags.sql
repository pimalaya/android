-- Fork of ADD_BASE_FLAGS (sha256 429cbd3a3cdb3ac5) for SQLite 3.18:
-- `ON CONFLICT DO NOTHING` (3.24) as add_flags rewrites it, the system flags
-- skipped as there, set_base_flags_known writing them as the binding's bits.
INSERT INTO binding_base_flag(item, source, flag)
SELECT DISTINCT i.id, :source, p.value FROM items i, temp.param p
WHERE i.collection = :collection AND i.link_id = :link_id AND p.slot = 'flags'
  AND p.value NOT IN ('\Seen', '\Answered', '\Flagged', '\Draft', '\Deleted')
  AND NOT EXISTS (SELECT 1 FROM binding_base_flag f
                  WHERE f.item = i.id AND f.source = :source AND f.flag = p.value);
