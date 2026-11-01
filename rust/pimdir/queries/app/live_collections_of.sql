-- The collections a link id is live in: neither removed nor retained.
SELECT collection FROM items
WHERE link_id = :link_id AND deleted = 0 AND retained_at IS NULL;
