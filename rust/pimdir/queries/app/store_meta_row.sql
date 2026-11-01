-- The identity row of a database file a start-up has to judge (STORAGE §6
-- step 2), which may be another draft's, another program's or no store at
-- all: every column it has, by name, so a column it lacks is a fact the
-- check reads rather than a read that fails. The one read of the shape
-- that names no columns, its table's shape being what is in question.
SELECT * FROM store_meta WHERE id = 1;
