-- Whether the row a collection holds under a key is another message than the
-- member a source names by it (STORAGE §9, §14; SYNC §6): a row the source
-- does not bind, live, a tombstone or retained, whose summary states another
-- :subject, :date or :sender, NULL matching NULL, a retained one another
-- size too where both state one. A row holding no summary says nothing
-- against. The member is then keyed apart, never joining, landing nor
-- reviving the row.
SELECT 1 FROM items i
JOIN mail_summary m ON m.item = i.id
WHERE i.collection = :collection AND i.link_id = :link_id
  AND NOT EXISTS (SELECT 1 FROM bindings b WHERE b.item = i.id AND b.source = :source)
  AND NOT (m.subject IS :subject AND m.date IS :date AND m.sender IS :sender
           AND (i.retained_at IS NULL OR m.size IS NULL OR :size IS NULL OR m.size = :size));
