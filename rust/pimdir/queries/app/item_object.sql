-- One item's body, NULL for none; no row for no item.
-- The body by its hash.
SELECT (SELECT o.hash FROM objects o WHERE o.id = i.object) FROM items i
WHERE i.collection = :collection AND i.link_id = :link_id;
