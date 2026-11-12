-- A live pending create in a collection under a link id or its minted key
-- (:minted), which a move staged, a delivered one included (pimdir SYNC §2):
-- based under its provisional handle, until a listing lands it.
SELECT i.link_id FROM items i
JOIN bindings b ON b.item = i.id
               AND b.source = :source
WHERE i.link_id IN (:link_id, :minted) AND i.collection = :collection
  AND i.deleted = 0 AND (b.base_present = 0 OR substr(b.handle, 1, 1) = char(1))
LIMIT 1;
