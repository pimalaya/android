-- The diverging body an item's own conflict recorded, by its hash, NULL for
-- none.
SELECT (SELECT o.hash FROM objects o WHERE o.id = i.conflict_object) FROM items i
WHERE i.collection = :collection AND i.link_id = :link_id;
