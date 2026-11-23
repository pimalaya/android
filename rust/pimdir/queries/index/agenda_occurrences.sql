-- Every occurrence overlapping [:start, :end) of a live calendar item of the
-- store, one row each, on occurrences_between's rule and in its order (SEARCH
-- §7): one starting before the range ends and ending after it starts, or one
-- of zero length starting inside it. Each with the row an agenda draws, read
-- from the store's summaries (STORAGE Annex A.3 to A.5) and its source's
-- binding: an item deleted since the index folded it is not listed, and no
-- body is read.
SELECT o.collection, o.seq, o.start, o.end, i.link_id,
       (SELECT x.hash FROM store.objects x WHERE x.id = i.object) AS object_hash,
       b.handle, b.base_revision,
       CASE s.component WHEN 'event' THEN 'VEVENT'
                        WHEN 'task' THEN 'VTODO'
                        WHEN 'journal' THEN 'VJOURNAL' END AS component,
       coalesce(s.title, '') AS summary,
       coalesce(s.location, '') AS location,
       coalesce(s.dtstart_value, s.due_value) AS value
FROM occurrence o
JOIN store.items i ON i.collection = o.collection AND i.seq = o.seq
LEFT JOIN store.bindings b ON b.item = i.id
                          AND b.source = :source
LEFT JOIN store.summary s ON s.item = i.id AND s.component IN ('event', 'task', 'journal')
WHERE o.start < :end AND (o.end > :start OR o.start >= :start)
  AND i.deleted = 0 AND i.retained_at IS NULL AND i.object IS NOT NULL
ORDER BY o.start, o.seq, o.collection;
