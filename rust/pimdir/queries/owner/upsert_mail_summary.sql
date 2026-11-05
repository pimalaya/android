-- Fork of UPSERT_MAIL_SUMMARY (sha256 b5baf9be6a833982) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
--
-- An unknown invitation (NULL) never clears a known one: the message is
-- immutable, so what its body once said it still says (Annex A.1).
UPDATE mail_summary SET
    message_id = :message_id,
    subject = :subject, sender = :sender,
    sender_name = :sender_name, date = :date,
    size = :size, attachment = :attachment,
    invitation = coalesce(:invitation, invitation)
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id);
INSERT INTO mail_summary(item, message_id, subject,
                         sender, sender_name, date, size, attachment, invitation)
SELECT i.id, :message_id, :subject,
       :sender, :sender_name, :date, :size, :attachment, :invitation
FROM items i
WHERE i.collection = :collection AND i.link_id = :link_id
  AND NOT EXISTS (SELECT 1 FROM mail_summary WHERE item = i.id);
