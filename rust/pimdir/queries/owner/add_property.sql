-- Fork of ADD_PROPERTY (sha256 0af2298ff81bc61a) for SQLite 3.18:
-- `ON CONFLICT DO NOTHING` (3.24) as an insert of the value where the key
-- does not hold it already; any other constraint still raises, where
-- `INSERT OR IGNORE` would swallow a key or a value past its bounds.
--
-- Adds one value under a key to the properties of the item a seq names
-- (§15.3), a value the key holds already changing nothing and stamping
-- nothing. The schema refuses a seq no row holds, a key or a value past its
-- bounds and a 65th value under one key (§13), and stamps every row holding
-- the seq (§4.5). A label compared folded is the caller's, which reads the
-- values held first (list_item_properties), since SQLite folds no Unicode.
INSERT INTO item_property(seq, key, value)
SELECT :seq, :key, :value
WHERE NOT EXISTS (SELECT 1 FROM item_property
                  WHERE seq = :seq AND key = :key AND value = :value);
