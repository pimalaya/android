-- load_placements over the handles the `handles` slot names, each a seek
-- on bindings_by_handle, and over the identities the `names` slot spells,
-- which an unbound row is placed under (its provisional handle); a row
-- reached by name whose binding holds another handle is not one asked for.
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
  AND i.link_id IN (SELECT link_id FROM bindings
                    WHERE collection = :collection AND source = :source
                      AND handle IN (SELECT value FROM temp.param WHERE slot = 'handles')
                    UNION ALL
                    SELECT value FROM temp.param WHERE slot = 'names')
  AND (b.handle IS NULL OR b.handle IN (SELECT value FROM temp.param WHERE slot = 'handles'));
