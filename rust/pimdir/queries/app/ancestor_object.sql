-- The body a binding last agreed with the item (its shared body, else its
-- base) that is neither side of a divergence, by source: a hash in and out,
-- the pointers joined to `objects` by id.
SELECT o.hash FROM bindings b
JOIN objects o ON o.id = ifnull(b.shared_object, b.base_object)
WHERE b.item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id)
  AND o.hash NOT IN (:object, :diverging)
ORDER BY b.source LIMIT 1;
