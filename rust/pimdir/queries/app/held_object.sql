-- Every body the store holds for a link id outside the contacts (a link id
-- names immutable bytes in mail and calendars alone), within one account, or
-- any when :account is NULL (STORAGE §14), each with its size and what its
-- row's mail summary states, NULL for a row holding none: what a link is
-- checked against (lookup_objects, SYNC §6), and the server object id the
-- binding of :source on the row states, NULL where it binds it not or
-- states none.
SELECT o.hash, o.size, m.title, m.date, m.sender, b.server_object_id FROM items i
JOIN collections c ON c.id = i.collection
JOIN objects o ON o.id = i.object
LEFT JOIN summary m ON m.item = i.id AND m.component = 'mail'
LEFT JOIN bindings b ON b.item = i.id AND b.source = :source
WHERE i.link_id = :link_id AND c.kind <> 'text/vcard'
  AND (:account IS NULL OR c.account = :account)
ORDER BY i.collection;
