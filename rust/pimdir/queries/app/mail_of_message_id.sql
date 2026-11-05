-- Where a live message of a Message-ID is filed, its collection and link id.
SELECT i.collection, i.link_id FROM mail_summary m
JOIN items i ON i.id = m.item
WHERE i.deleted = 0 AND m.message_id = :message_id
LIMIT 1;
