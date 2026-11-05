-- Gives a message the address rows of the message it was created from, on
-- copy_mail_summary's terms.
INSERT OR REPLACE INTO item_address(item, role, position, address, name)
SELECT (SELECT id FROM items WHERE collection = :to_collection AND link_id = :to_link_id),
       role, position, address, name
FROM item_address
WHERE item = (SELECT id FROM items WHERE collection = :from_collection AND link_id = :from_link_id);
