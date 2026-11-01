-- Every category of every live to-do of every collection of a kind, in
-- document order (STORAGE Annex A.4): the states and contexts the Tasks
-- list reads off task_summary's rows rather than off the bodies.
SELECT t.collection, t.link_id, t.value
FROM task_category t
JOIN items i ON i.collection = t.collection AND i.link_id = t.link_id
JOIN collections c ON c.id = t.collection
WHERE c.kind = :kind AND i.deleted = 0
  AND i.retained_at IS NULL
ORDER BY t.collection, t.link_id, t.position;
