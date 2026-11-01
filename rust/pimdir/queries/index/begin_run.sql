-- Opens one run's transaction on index.db (SEARCH §4), deferred: a savepoint
-- outside a transaction begins one taking no lock until it reads or writes.
-- The platform's own transactions below API 35 are immediate or exclusive,
-- which take the write lock of every attached database, the store's
-- included, and would hold the store's writer off for a whole run.
SAVEPOINT index_run;
