-- Gives a message the address rows of the message it was created from, on
-- copy_mail_summary's terms.
INSERT OR REPLACE INTO item_address(collection, link_id, role, position, address, name)
SELECT :to_collection, :to_link_id, role, position, address, name
FROM item_address WHERE collection = :from_collection AND link_id = :from_link_id;
