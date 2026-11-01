-- A collection's live and tombstoned items, each with the source's binding
-- and what the engine reads of a message's summary (its date, subject and
-- sender): the rows a load projects placements from (SYNC §3, §5, §6).
-- Retained rows are left out (STORAGE §11). Flags as one text, on
-- load_items' terms (§4.4).
SELECT i.link_id,
       CASE WHEN i.flags_known = 1
            THEN coalesce((SELECT group_concat(f.flag, ' ') FROM placement_flag f
                           WHERE f.collection = i.collection AND f.link_id = i.link_id), '') END AS flags,
       i.object_hash, i.sort_key, i.level, b.handle,
       CASE WHEN b.base_flags_known = 1
            THEN coalesce((SELECT group_concat(g.flag, ' ') FROM binding_base_flag g
                           WHERE g.collection = b.collection AND g.link_id = b.link_id
                             AND g.source = b.source), '') END AS base_flags,
       b.base_object, b.base_revision, b.base_present, b.conflicted, b.conflict_revision,
       b.conflict_object, i.deleted, i.conflicted, c.kind, s.date, s.subject, s.sender
FROM items i
JOIN collections c ON c.id = i.collection
LEFT JOIN bindings b ON b.collection = i.collection AND b.link_id = i.link_id
                    AND b.source = :source
LEFT JOIN mail_summary s ON s.collection = i.collection AND s.link_id = i.link_id
WHERE i.collection = :collection AND i.retained_at IS NULL;
