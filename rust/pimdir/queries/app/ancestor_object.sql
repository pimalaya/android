-- The body a binding last agreed with the item (its shared body, else its
-- base) that is neither side of a divergence, by source.
SELECT ifnull(shared_object, base_object) FROM bindings
WHERE collection = :collection AND link_id = :link_id
  AND ifnull(shared_object, base_object) NOT IN (:object, :diverging)
ORDER BY source LIMIT 1;
