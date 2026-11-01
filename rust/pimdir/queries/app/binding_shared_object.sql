-- The shared body a source's binding last agreed with the item (SYNC §9).
SELECT shared_object FROM bindings
WHERE collection = :collection AND link_id = :link_id AND source = :source;
