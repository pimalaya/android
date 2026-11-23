-- Gives a held item a body at a level, named by its hash and written as its
-- `objects` id, its row otherwise untouched.
UPDATE items SET object = (SELECT id FROM objects WHERE hash = :object_hash), level = :level
WHERE collection = :collection AND link_id = :link_id;
