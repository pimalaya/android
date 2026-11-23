-- A live mail item's In-Reply-To ids in order (STORAGE Annex A.1), by its
-- collection and seq: what load_in_reply_to_by_link reads, in the round the
-- item's summary is read in.
SELECT r.value AS message_id FROM store.items i
JOIN store.summary_value r ON r.item = i.id AND r.role = 'in-reply-to'
WHERE i.collection = :collection AND i.seq = :seq AND i.deleted = 0
ORDER BY r.position;
