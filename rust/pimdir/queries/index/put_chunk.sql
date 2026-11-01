-- One chunk of a body's `body` or `note` field (SEARCH §6), keyed on its
-- object row by its hash: insert_chunk by hash.
INSERT INTO chunk(object, ord, field, start, end)
SELECT o.id, :ord, :field, :start, :end FROM object o WHERE o.hash = :hash;
