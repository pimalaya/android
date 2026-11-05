-- The flag set a source last agreed on, one text on load_bindings' terms,
-- NULL unknown.
SELECT CASE WHEN b.base_flags_known = 1
            THEN trim(CASE WHEN b.base_marks & 1 THEN '\Seen ' ELSE '' END
                   || CASE WHEN b.base_marks & 2 THEN '\Answered ' ELSE '' END
                   || CASE WHEN b.base_marks & 4 THEN '\Flagged ' ELSE '' END
                   || CASE WHEN b.base_marks & 8 THEN '\Draft ' ELSE '' END
                   || CASE WHEN b.base_marks & 16 THEN '\Deleted ' ELSE '' END
                   || coalesce((SELECT group_concat(g.flag, ' ') FROM binding_base_flag g
                                WHERE g.item = b.item AND g.source = b.source), '')) END AS base_flags
FROM bindings b
WHERE b.item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id) AND b.source = :source;
