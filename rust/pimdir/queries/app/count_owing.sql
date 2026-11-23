-- How many items of an account's collections of a kind owe the server a push.
SELECT count(*) FROM items i
JOIN collections c ON c.id = i.collection
LEFT JOIN bindings b ON b.item = i.id AND b.source = 'server'
WHERE c.account = :account AND c.kind = :kind AND c.id IS NOT :except
  AND i.retained_at IS NULL
  AND CASE WHEN b.handle IS NULL
      THEN i.deleted = 0 AND i.object IS NOT NULL
      ELSE b.conflicted = 1 OR i.conflicted = 1 OR i.deleted = 1
           OR NOT (b.base_present = 1 OR b.base_flags_known = 1
                   OR b.base_object IS NOT NULL OR b.base_revision IS NOT NULL)
           OR (i.flags_known = 1 AND b.base_flags_known = 1
               AND (i.marks != b.base_marks OR i.keywords != b.base_keywords))
           OR (c.kind != 'message/rfc822' AND i.object IS NOT NULL
               AND i.object IS NOT b.base_object)
      END;
