-- Whether a message's key is another message's in its account, so that a
-- `part:` key built over it would not be its own (STORAGE §14.3): a row of
-- the account other than :collection's, holding under :link_id another body
-- than that row's, whose summary does not state the subject, date and sender
-- that row's states (§9), NULL matching NULL, a row holding no mail summary
-- agreeing with one holding none alone. The body then records no stand-in
-- and no `attachment` reference. One disagreeing holder is enough.
SELECT 1 FROM items own
LEFT JOIN mail_summary mine ON mine.item = own.id
JOIN items i ON i.link_id = own.link_id AND i.id <> own.id
JOIN collections c ON c.id = i.collection AND c.account IS :account
LEFT JOIN mail_summary m ON m.item = i.id
WHERE own.collection = :collection AND own.link_id = :link_id
  AND i.object_hash IS NOT NULL AND i.object_hash IS NOT own.object_hash
  AND NOT (m.subject IS mine.subject AND m.date IS mine.date AND m.sender IS mine.sender)
LIMIT 1;
