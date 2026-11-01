-- One collection as a roster lists it, everything the store owns besides
-- (its generation, its conflict policy, its role) left alone: an update of
-- the row, then its insert where none is there, in one transaction (SQLite
-- 3.18 has no upsert).
UPDATE collections SET account = :account, kind = :kind, name = :name,
       description = :description, color = :color
WHERE id = :collection;
INSERT INTO collections(id, account, kind, name, description, color)
SELECT :collection, :account, :kind, :name, :description, :color
WHERE NOT EXISTS (SELECT 1 FROM collections WHERE id = :collection);
