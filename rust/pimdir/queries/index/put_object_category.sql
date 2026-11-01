-- One of a card's CATEGORIES values (SEARCH §6), folded, keyed on its object
-- row by its hash: insert_object_category by hash.
INSERT OR IGNORE INTO object_category(object, value)
SELECT o.id, :value FROM object o WHERE o.hash = :hash;
