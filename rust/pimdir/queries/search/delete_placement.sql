-- Fork of DELETE_PLACEMENT (sha256 625338af73528067) for SQLite 3.18: the
-- RETURNING (3.35) as a read of the id before the delete, run in one
-- transaction. Takes the placement's flags and occurrences by cascade.
SELECT id FROM placement WHERE collection = :collection AND seq = :seq;
DELETE FROM placement WHERE collection = :collection AND seq = :seq;
