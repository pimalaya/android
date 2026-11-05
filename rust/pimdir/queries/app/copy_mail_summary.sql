-- Gives a message the stored summary of the message it was created from (a
-- landed create, a copy, a move's target), replacing its own.
INSERT OR REPLACE INTO mail_summary(item, message_id, subject, sender,
                                    sender_name, date, size, attachment, invitation)
SELECT (SELECT id FROM items WHERE collection = :to_collection AND link_id = :to_link_id),
       message_id, subject, sender,
       sender_name, date, size, attachment, invitation
FROM mail_summary
WHERE item = (SELECT id FROM items WHERE collection = :from_collection AND link_id = :from_link_id);
