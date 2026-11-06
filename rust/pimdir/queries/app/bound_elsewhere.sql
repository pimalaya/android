-- Where else a source binds a live item of this link id, its collection and
-- handle: the origin a create of an identity held elsewhere is a membership
-- addition from (SYNC §3, origin_for_link). Within the create's account,
-- holding the body it carries, and, for mail, under its Message-ID: an IMAP
-- link id is a UID another mailbox reuses. A minted key pairs with the
-- identity it was minted over, the bare key first.
SELECT b.collection, b.handle FROM bindings b
JOIN items i ON i.id = b.item
WHERE i.link_id IN (:link_id, CASE WHEN :link_id GLOB 'dup:*'
                                   THEN substr(:link_id, 5, (length(:link_id) - 6) / 2) END)
  AND (i.link_id = :link_id OR 'dup:' || i.link_id || '#' || char(1) || i.link_id = :link_id)
  AND b.source = :source AND b.collection <> :collection
  AND substr(b.collection, 1, length(:account)) = :account
  AND b.base_present = 1 AND (:object IS NULL OR b.base_object = :object)
  AND i.deleted = 0 AND i.retained_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM items c JOIN mail_summary cs ON cs.item = c.id
    WHERE c.collection = :collection AND c.link_id = :link_id
      AND cs.message_id IS NOT NULL
      AND cs.message_id IS NOT (SELECT s.message_id FROM mail_summary s WHERE s.item = i.id))
ORDER BY i.link_id <> :link_id, b.collection
LIMIT 1;
