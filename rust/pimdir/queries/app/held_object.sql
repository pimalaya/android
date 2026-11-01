-- A body the store holds for a link id outside the contacts (a link id names
-- immutable bytes in mail and calendars alone), within one account, or any
-- when :account is NULL (STORAGE §14), with its size.
SELECT i.object_hash, o.size FROM items i
JOIN collections c ON c.id = i.collection
JOIN objects o ON o.hash = i.object_hash
WHERE i.link_id = :link_id AND c.kind <> 'text/vcard'
  AND (:account IS NULL OR c.account = :account)
LIMIT 1;
