-- The store attached to index.db as `store` (SEARCH §2, §3), by its path, so
-- one statement joins both. Not a URI: the platform opens no database with
-- SQLITE_OPEN_URI, so a `file:` name would be a file of that name. The
-- platform turns write-ahead logging off on a handle running an ATTACH,
-- which index.db never had.
ATTACH DATABASE :path AS store;
