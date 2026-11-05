-- Every occurrence overlapping [:start, :end) of a live calendar item of the
-- store, one row each, on occurrences_between's rule and in its order (SEARCH
-- §7): one starting before the range ends and ending after it starts, or one
-- of zero length starting inside it. Each with the row an agenda draws, read
-- from the store's summaries (STORAGE Annex A.3 to A.5) and its source's
-- binding: an item deleted since the index folded it is not listed, and no
-- body is read.
SELECT o.collection, o.seq, o.start, o.end, i.link_id, i.object_hash,
       b.handle, b.base_revision,
       CASE WHEN e.item IS NOT NULL THEN 'VEVENT'
            WHEN t.item IS NOT NULL THEN 'VTODO'
            WHEN j.item IS NOT NULL THEN 'VJOURNAL' END AS component,
       coalesce(e.summary, t.summary, j.summary, '') AS summary,
       coalesce(e.location, '') AS location,
       coalesce(e.dtstart_value, t.dtstart_value, t.due_value, j.dtstart_value) AS value
FROM occurrence o
JOIN store.items i ON i.collection = o.collection AND i.seq = o.seq
LEFT JOIN store.bindings b ON b.item = i.id
                          AND b.source = :source
LEFT JOIN store.event_summary e ON e.item = i.id
LEFT JOIN store.task_summary t ON t.item = i.id
LEFT JOIN store.journal_summary j ON j.item = i.id
WHERE o.start < :end AND (o.end > :start OR o.start >= :start)
  AND i.deleted = 0 AND i.retained_at IS NULL AND i.object_hash IS NOT NULL
ORDER BY o.start, o.seq, o.collection;
