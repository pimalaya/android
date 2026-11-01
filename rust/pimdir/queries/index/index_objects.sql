-- The tables, indexes and triggers index.db declares, by name, with their
-- text (SEARCH §2), SQLite's own and the platform's locale table left out;
-- the shadow tables of each virtual table are left out by the caller.
SELECT name, sql FROM main.sqlite_master
WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata';
