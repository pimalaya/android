-- Whether a placement holds the only copy of an edit its server has not got:
-- a body no binding of the card on this source agreed on, which no other live
-- placement of the card on its account holds. What an untick of its book
-- would withdraw, the edit then left in Deleted items alone.
SELECT 1 FROM items i JOIN collections c ON c.id = i.collection
WHERE i.collection = :collection AND i.link_id = :link_id
  AND i.deleted = 0 AND i.retained_at IS NULL AND i.object_hash IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM items o
                  JOIN bindings b ON b.item = o.id
                  JOIN collections oc ON oc.id = o.collection
                  WHERE o.link_id = i.link_id AND b.source = :source
                    AND b.base_present = 1 AND b.base_object = i.object_hash
                    AND oc.account = c.account AND oc.kind = c.kind)
  AND NOT EXISTS (SELECT 1 FROM items o
                  JOIN collections oc ON oc.id = o.collection
                  WHERE o.link_id = i.link_id AND o.collection <> i.collection
                    AND o.deleted = 0 AND o.retained_at IS NULL
                    AND o.object_hash = i.object_hash
                    AND oc.account = c.account AND oc.kind = c.kind);
