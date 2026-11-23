-- Every body the account holds under a message's key, never a derived one,
-- each with its size and what its row's summary states, NULL for a row
-- holding none: the engine links a body only from a row agreeing with the
-- message's own summary (lookup_objects, SYNC §6) and never from one whose
-- binding of :source states another server object id, which rides along,
-- NULL where :source binds it not or states none.
SELECT o.hash, o.size, m.title, m.date, m.sender, b.server_object_id FROM items i
JOIN collections c ON c.id = i.collection AND c.account IS :account
JOIN objects o ON o.id = i.object
LEFT JOIN summary m ON m.item = i.id AND m.component = 'mail'
LEFT JOIN bindings b ON b.item = i.id AND b.source = :source
WHERE i.link_id = :link_id
  AND NOT (i.link_id GLOB 'alt:*' OR i.link_id GLOB 'dup:*' OR i.link_id GLOB 'hash:*')
ORDER BY i.collection;
