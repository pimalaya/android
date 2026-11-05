-- Whether a binding's base flag set is known, and its system flags as the
-- binding's bits, on set_flags_known's terms.
UPDATE bindings SET base_flags_known = :base_flags_known,
  base_marks = CASE WHEN :base_flags_known = 1 THEN
    (SELECT coalesce(sum(CASE value WHEN '\Seen' THEN 1 WHEN '\Answered' THEN 2
                                    WHEN '\Flagged' THEN 4 WHEN '\Draft' THEN 8
                                    WHEN '\Deleted' THEN 16 ELSE 0 END), 0)
     FROM (SELECT DISTINCT value FROM temp.param WHERE slot = 'flags'))
  ELSE 0 END
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id)
  AND source = :source;
