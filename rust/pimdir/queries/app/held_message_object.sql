-- Every body the account holds under a message's key, never a derived one,
-- each with its size and what its row's summary states, NULL for a row
-- holding none: the engine links a body only from a row agreeing with the
-- message's own summary (lookup_objects, SYNC §6).
SELECT i.object_hash, o.size, m.subject, m.date, m.sender FROM items i
JOIN collections c ON c.id = i.collection AND c.account = :account
JOIN objects o ON o.hash = i.object_hash
LEFT JOIN mail_summary m ON m.item = i.id
WHERE i.link_id = :link_id
  AND NOT (i.link_id GLOB 'alt:*' OR i.link_id GLOB 'dup:*' OR i.link_id GLOB 'hash:*')
ORDER BY i.collection;
