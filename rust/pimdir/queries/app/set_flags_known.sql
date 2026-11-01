-- Whether a placement's flag set is known (STORAGE §13), written before its
-- rows: 0 forgets them, the schema's trigger dropping the rows.
UPDATE items SET flags_known = :flags_known
WHERE collection = :collection AND link_id = :link_id;
