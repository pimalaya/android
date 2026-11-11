-- The collection whose add superseded an item: where the move its removal
-- waits for goes (pimdir STORAGE §15.3).
SELECT n.collection FROM supersessions s
JOIN items t ON t.id = s.item
JOIN items n ON n.id = s.by_item
WHERE t.collection = :collection AND t.link_id = :link_id;
