-- A live pending create in a collection under a link id or its minted key
-- (:minted), which a move staged.
SELECT i.link_id FROM items i
JOIN bindings b ON b.item = i.id
               AND b.source = :source
WHERE i.link_id IN (:link_id, :minted) AND i.collection = :collection
  AND i.deleted = 0 AND b.base_present = 0
LIMIT 1;
