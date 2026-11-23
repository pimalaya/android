-- Whether a binding's base flag set is known, its system flags as the
-- binding's bits and every other flag as its keywords, on set_flags_known's
-- terms.
UPDATE bindings SET base_flags_known = :base_flags_known,
  base_marks = CASE WHEN :base_flags_known = 1 THEN :marks ELSE 0 END,
  base_keywords = CASE WHEN :base_flags_known = 1 THEN :keywords ELSE '' END
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id)
  AND source = :source;
