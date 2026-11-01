-- A placement's summary text, by its key (SEARCH §4): delete_summary_text by
-- the placement's id, before delete_placement takes the row.
DELETE FROM summary_text
WHERE docid IN (SELECT p.id FROM placement p WHERE p.collection = :collection AND p.seq = :seq);
