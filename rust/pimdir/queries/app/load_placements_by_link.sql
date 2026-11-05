-- load_placements over the link ids the `links` slot names, each a seek on
-- the item's key.
SELECT i.link_id,
       CASE WHEN i.flags_known = 1
            THEN trim(CASE WHEN i.marks & 1 THEN '\Seen ' ELSE '' END
                   || CASE WHEN i.marks & 2 THEN '\Answered ' ELSE '' END
                   || CASE WHEN i.marks & 4 THEN '\Flagged ' ELSE '' END
                   || CASE WHEN i.marks & 8 THEN '\Draft ' ELSE '' END
                   || CASE WHEN i.marks & 16 THEN '\Deleted ' ELSE '' END
                   || coalesce((SELECT group_concat(f.flag, ' ') FROM placement_flag f
                                WHERE f.item = i.id), '')) END AS flags,
       i.object_hash, i.sort_key, i.level, b.handle,
       CASE WHEN b.base_flags_known = 1
            THEN trim(CASE WHEN b.base_marks & 1 THEN '\Seen ' ELSE '' END
                   || CASE WHEN b.base_marks & 2 THEN '\Answered ' ELSE '' END
                   || CASE WHEN b.base_marks & 4 THEN '\Flagged ' ELSE '' END
                   || CASE WHEN b.base_marks & 8 THEN '\Draft ' ELSE '' END
                   || CASE WHEN b.base_marks & 16 THEN '\Deleted ' ELSE '' END
                   || coalesce((SELECT group_concat(g.flag, ' ') FROM binding_base_flag g
                                WHERE g.item = b.item AND g.source = b.source), '')) END AS base_flags,
       b.base_object, b.base_revision, b.base_present, b.conflicted, b.conflict_revision,
       b.conflict_object, i.deleted, i.conflicted, c.kind, s.date, s.subject, s.sender,
       i.conflict_object AS item_conflict_object
FROM items i
JOIN collections c ON c.id = i.collection
LEFT JOIN bindings b ON b.item = i.id
                    AND b.source = :source
LEFT JOIN mail_summary s ON s.item = i.id
WHERE i.collection = :collection AND i.retained_at IS NULL
  AND i.link_id IN (SELECT value FROM temp.param WHERE slot = 'links');
