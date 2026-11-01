-- Gives a held item a body at a level, its row otherwise untouched.
UPDATE items SET object_hash = :object_hash, level = :level
WHERE collection = :collection AND link_id = :link_id;
