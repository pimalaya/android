-- A body's text, by its object row's hash, once no placement names it
-- (SEARCH §4): object_placed folded into drop_object_text, run before
-- drop_unplaced_object_row takes the row.
DELETE FROM object_text WHERE docid IN (SELECT o.id FROM object o WHERE o.hash = :hash)
AND NOT EXISTS (SELECT 1 FROM placement p
                WHERE p.object = (SELECT o.id FROM object o WHERE o.hash = :hash));
