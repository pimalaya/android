-- Every body the store holds for a link id outside the contacts (a link id
-- names immutable bytes in mail and calendars alone), within one account, or
-- any when :account is NULL (STORAGE §14), each with its size and what its
-- row's mail summary states, NULL for a row holding none: what a link is
-- checked against (lookup_objects, SYNC §6).
SELECT i.object_hash, o.size, m.subject, m.date, m.sender FROM items i
JOIN collections c ON c.id = i.collection
JOIN objects o ON o.hash = i.object_hash
LEFT JOIN mail_summary m ON m.item = i.id
WHERE i.link_id = :link_id AND c.kind <> 'text/vcard'
  AND (:account IS NULL OR c.account = :account)
ORDER BY i.collection;
