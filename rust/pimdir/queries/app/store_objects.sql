-- Every table, index and trigger a database file declares, with its text,
-- SQLite's own and the platform helper's (:platform_table) left out: what
-- the start-up check compares with the canonical schema.
SELECT name, sql FROM sqlite_master
WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name <> :platform_table;
