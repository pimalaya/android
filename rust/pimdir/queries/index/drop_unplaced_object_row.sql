-- A body's object row once no placement names it (SEARCH §4): object_placed
-- folded into delete_object_row, its categories and chunks going with it.
DELETE FROM object WHERE hash = :hash
AND NOT EXISTS (SELECT 1 FROM placement p
                WHERE p.object = (SELECT o.id FROM object o WHERE o.hash = :hash));
