-- A live task's CATEGORIES in order (STORAGE Annex A.4), by its collection
-- and seq: what load_categories_by_link reads, in the round the item's
-- summary is read in.
SELECT t.value FROM store.items i
JOIN store.task_category t ON t.collection = i.collection AND t.link_id = i.link_id
WHERE i.collection = :collection AND i.seq = :seq AND i.deleted = 0
ORDER BY t.position;
