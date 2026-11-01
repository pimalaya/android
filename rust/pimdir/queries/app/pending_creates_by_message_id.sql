-- A collection's live pending mail creates by Message-ID, read off the range
-- of provisional handles [:low, :high) (SYNC §2), so the cost follows the
-- creates.
SELECT b.link_id, s.message_id FROM bindings b
JOIN items i ON i.collection = b.collection AND i.link_id = b.link_id
JOIN mail_summary s ON s.collection = b.collection AND s.link_id = b.link_id
WHERE b.collection = :collection AND b.source = :source
  AND b.handle >= :low AND b.handle < :high
  AND b.base_present = 0 AND i.deleted = 0
  AND s.message_id IS NOT NULL;
