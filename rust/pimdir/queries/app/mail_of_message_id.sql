-- Where a live message of a Message-ID is filed, its collection and link id.
SELECT m.collection, m.link_id FROM mail_summary m
JOIN items i ON i.collection = m.collection AND i.link_id = m.link_id
WHERE i.deleted = 0 AND m.message_id = :message_id
LIMIT 1;
