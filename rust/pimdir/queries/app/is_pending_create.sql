-- Whether a link id is a live pending create in a collection: unbound by
-- the source, or bound with no base.
SELECT 1 FROM items i
LEFT JOIN bindings b ON b.item = i.id
                    AND b.source = :source
WHERE i.collection = :collection AND i.link_id = :link_id AND i.deleted = 0
  AND i.retained_at IS NULL
  AND (b.item IS NULL OR b.base_present = 0);
