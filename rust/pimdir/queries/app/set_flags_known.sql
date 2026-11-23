-- Whether a placement's flag set is known, its system flags as the item's
-- bits and every other flag as its keywords (STORAGE §13), as the bridge
-- computed both from the whole set (`PimdirSql.flags`): 0 and '' when the
-- set is unknown, as the schema asks.
UPDATE items SET flags_known = :flags_known,
  marks = CASE WHEN :flags_known = 1 THEN :marks ELSE 0 END,
  keywords = CASE WHEN :flags_known = 1 THEN :keywords ELSE '' END
WHERE collection = :collection AND link_id = :link_id;
