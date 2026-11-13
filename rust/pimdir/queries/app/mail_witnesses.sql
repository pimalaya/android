-- What a message's summary states of the three fields an identity is
-- checked against (STORAGE §9), and its size: nothing for a row holding no
-- summary.
SELECT m.subject, m.date, m.sender, m.size FROM mail_summary m
WHERE m.item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id);
