-- Gives a message the stored summary of the message it was created from (a
-- landed create, a copy, a move's target), replacing its own.
INSERT OR REPLACE INTO mail_summary(collection, link_id, message_id, subject, sender,
                                    sender_name, date, size, attachment, invitation)
SELECT :to_collection, :to_link_id, message_id, subject, sender,
       sender_name, date, size, attachment, invitation
FROM mail_summary WHERE collection = :from_collection AND link_id = :from_link_id;
