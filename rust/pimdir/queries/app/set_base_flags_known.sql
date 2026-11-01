-- Whether a binding's base flag set is known, on set_flags_known's terms.
UPDATE bindings SET base_flags_known = :base_flags_known
WHERE collection = :collection AND link_id = :link_id AND source = :source;
