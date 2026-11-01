-- A live mail item's In-Reply-To ids in order (STORAGE Annex A.1), by its
-- collection and seq: what load_in_reply_to_by_link reads, in the round the
-- item's summary is read in.
SELECT r.msg_id FROM store.items i
JOIN store.mail_in_reply_to r ON r.collection = i.collection AND r.link_id = i.link_id
WHERE i.collection = :collection AND i.seq = :seq AND i.deleted = 0
ORDER BY r.position;
