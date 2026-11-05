-- Whether a placement's flag set is known, and its system flags as the item's
-- bits (STORAGE §13), from the `flags` slot holding the whole set: written
-- before its rows, which add_flags writes from the same slot, the system
-- flags skipped. 0 forgets them, no bit kept and the schema's trigger
-- dropping the rows.
UPDATE items SET flags_known = :flags_known,
  marks = CASE WHEN :flags_known = 1 THEN
    (SELECT coalesce(sum(CASE value WHEN '\Seen' THEN 1 WHEN '\Answered' THEN 2
                                    WHEN '\Flagged' THEN 4 WHEN '\Draft' THEN 8
                                    WHEN '\Deleted' THEN 16 ELSE 0 END), 0)
     FROM (SELECT DISTINCT value FROM temp.param WHERE slot = 'flags'))
  ELSE 0 END
WHERE collection = :collection AND link_id = :link_id;
