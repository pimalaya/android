-- An account's live messages holding a body another collection holds under
-- the same link id (a UID once read as an identity), which no binding needs:
-- release_before's predicate (STORAGE §11.4), unconflicted, bound, every
-- binding based on that body or on none.
SELECT i.collection, i.link_id, i.object_hash FROM items i
JOIN collections c ON c.id = i.collection
WHERE c.account = :account AND c.kind = 'message/rfc822'
  AND i.deleted = 0 AND i.conflicted = 0
  AND i.object_hash IS NOT NULL
  AND EXISTS (SELECT 1 FROM items o
              WHERE o.link_id = i.link_id AND o.collection <> i.collection
                AND o.object_hash = i.object_hash)
  AND EXISTS (SELECT 1 FROM bindings b
              WHERE b.collection = i.collection AND b.link_id = i.link_id)
  AND NOT EXISTS (SELECT 1 FROM bindings b
                  WHERE b.collection = i.collection AND b.link_id = i.link_id
                    AND (b.conflicted = 1
                         OR NOT (b.base_present = 1 OR b.base_flags_known = 1
                                 OR b.base_object IS NOT NULL OR b.base_revision IS NOT NULL)
                         OR (b.base_object IS NOT NULL AND b.base_object != i.object_hash)));
