-- A collection's live and tombstoned items, each with the source's binding
-- and what the engine reads of a message's summary (its date, subject and
-- sender, and the size, mark and invitation its body told) and a file's
-- whole summary: the rows a load projects placements from (SYNC §3, §5, §6),
-- and the item's diverging body, what an edit meets beside its binding's
-- conflict (SYNC §7), and the binding's shared body, what tells a body
-- another source folded in (SYNC §9). Retained rows are left out (STORAGE
-- §11). Flags as one text, on load_items' terms (§4.4).
-- Every body by its hash, the pointers joined to `objects` by id; last, the
-- server object id the source's binding states (SYNC §4).
SELECT i.link_id,
       CASE WHEN i.flags_known = 1
            THEN trim(CASE WHEN i.marks & 1 THEN '\Seen ' ELSE '' END
                   || CASE WHEN i.marks & 2 THEN '\Answered ' ELSE '' END
                   || CASE WHEN i.marks & 4 THEN '\Flagged ' ELSE '' END
                   || CASE WHEN i.marks & 8 THEN '\Draft ' ELSE '' END
                   || CASE WHEN i.marks & 16 THEN '\Deleted ' ELSE '' END
                   || i.keywords) END AS flags,
       (SELECT o.hash FROM objects o WHERE o.id = i.object) AS object_hash,
       i.sort_key, i.level, b.handle,
       CASE WHEN b.base_flags_known = 1
            THEN trim(CASE WHEN b.base_marks & 1 THEN '\Seen ' ELSE '' END
                   || CASE WHEN b.base_marks & 2 THEN '\Answered ' ELSE '' END
                   || CASE WHEN b.base_marks & 4 THEN '\Flagged ' ELSE '' END
                   || CASE WHEN b.base_marks & 8 THEN '\Draft ' ELSE '' END
                   || CASE WHEN b.base_marks & 16 THEN '\Deleted ' ELSE '' END
                   || b.base_keywords) END AS base_flags,
       (SELECT o.hash FROM objects o WHERE o.id = b.base_object) AS base_object,
       b.base_revision, b.base_present, b.conflicted, b.conflict_revision,
       (SELECT o.hash FROM objects o WHERE o.id = b.conflict_object) AS conflict_object,
       i.deleted, i.conflicted, c.kind, s.date, s.title AS subject, s.sender,
       (SELECT o.hash FROM objects o WHERE o.id = i.conflict_object) AS item_conflict_object,
       s.size, s.attachment, s.invitation,
       fs.title AS name, fs.media_type, fs.size AS file_size, fs.part,
       (SELECT o.hash FROM objects o WHERE o.id = b.shared_object) AS shared_object,
       b.server_object_id
FROM items i
JOIN collections c ON c.id = i.collection
LEFT JOIN bindings b ON b.item = i.id
                    AND b.source = :source
LEFT JOIN summary s ON s.item = i.id AND s.component = 'mail'
LEFT JOIN summary fs ON fs.item = i.id AND fs.component = 'file'
WHERE i.collection = :collection AND i.retained_at IS NULL;
