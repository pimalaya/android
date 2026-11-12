-- A collection's live pending mail creates by Message-ID, read off the range
-- of provisional handles [:low, :high) (SYNC §2), so the cost follows the
-- creates, a delivered one (based there until a listing lands it) included,
-- which the third column says.
SELECT i.link_id, s.message_id, b.base_present FROM bindings b
JOIN items i ON i.id = b.item
JOIN mail_summary s ON s.item = b.item
WHERE b.collection = :collection AND b.source = :source
  AND b.handle >= :low AND b.handle < :high
  AND i.deleted = 0
  AND s.message_id IS NOT NULL;
