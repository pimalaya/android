-- The body the account holds for the same message, never under a derived key.
SELECT i.object_hash, o.size FROM items a
JOIN mail_summary t ON t.item = a.id
JOIN items i ON i.link_id = a.link_id AND i.object_hash IS NOT NULL
JOIN collections c ON c.id = i.collection AND c.account = :account
JOIN mail_summary s ON s.item = i.id
JOIN objects o ON o.hash = i.object_hash
WHERE a.collection = :collection AND a.link_id = :link_id
  AND NOT (a.link_id GLOB 'alt:*' OR a.link_id GLOB 'dup:*' OR a.link_id GLOB 'hash:*')
  AND s.subject IS t.subject AND s.date IS t.date AND s.sender IS t.sender
LIMIT 1;
