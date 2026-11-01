-- The flag set a source last agreed on, one text on load_bindings' terms,
-- NULL unknown.
SELECT CASE WHEN b.base_flags_known = 1
            THEN coalesce((SELECT group_concat(g.flag, ' ') FROM binding_base_flag g
                           WHERE g.collection = b.collection AND g.link_id = b.link_id
                             AND g.source = b.source), '') END AS base_flags
FROM bindings b
WHERE b.collection = :collection AND b.link_id = :link_id AND b.source = :source;
