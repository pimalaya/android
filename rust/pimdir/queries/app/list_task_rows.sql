-- Every live to-do of every collection of a kind, read from the store's
-- summaries alone (STORAGE Annex A.4, A.6): the source's handle and
-- revision, the task_summary row, and who it waits on, its first `contact`
-- address. What the Tasks list shows and sorts by; no body is read.
SELECT i.collection, i.link_id, i.object_hash, b.base_revision AS revision, b.handle,
       s.uid, s.summary, s.dtstart, s.dtstart_tzid, s.dtstart_value,
       s.due, s.due_tzid, s.due_value, s.status, s.completed, s.priority,
       s.parent, s.mid, s.created,
       (SELECT a.name FROM item_address a
        WHERE a.collection = i.collection AND a.link_id = i.link_id
          AND a.role = 'contact'
        ORDER BY a.position LIMIT 1) AS contact_name,
       (SELECT a.address FROM item_address a
        WHERE a.collection = i.collection AND a.link_id = i.link_id
          AND a.role = 'contact'
        ORDER BY a.position LIMIT 1) AS contact_address
FROM items i
JOIN collections c ON c.id = i.collection
JOIN task_summary s ON s.collection = i.collection AND s.link_id = i.link_id
LEFT JOIN bindings b ON b.collection = i.collection AND b.link_id = i.link_id
                    AND b.source = :source
WHERE c.kind = :kind AND i.deleted = 0
  AND i.retained_at IS NULL
  AND i.object_hash IS NOT NULL
ORDER BY i.collection, i.sort_key, i.seq;
