-- A commit lasts once it is answered (STORAGE §4.1): the writer syncs the
-- log at each one, whatever the platform's default under write-ahead logging.
PRAGMA synchronous = FULL;
