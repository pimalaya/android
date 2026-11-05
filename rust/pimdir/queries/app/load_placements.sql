-- A collection's live and tombstoned items, each with the source's binding
-- and what the engine reads of a message's summary (its date, subject and
-- sender): the rows a load projects placements from (SYNC §3, §5, §6),
-- and the item's diverging body, what an edit meets beside its binding's
-- conflict (SYNC §7). Retained rows are left out (STORAGE §11). Flags as
-- one text, on load_items' terms (§4.4).
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
WHERE i.collection = :collection AND i.retained_at IS NULL;
