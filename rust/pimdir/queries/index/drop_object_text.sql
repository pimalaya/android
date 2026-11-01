-- A body's text, by its object row's hash (SEARCH §4): delete_object by hash,
-- before delete_object_row takes the row.
DELETE FROM object_text WHERE docid IN (SELECT o.id FROM object o WHERE o.hash = :hash);
