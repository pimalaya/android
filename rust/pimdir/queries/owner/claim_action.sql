-- Fork of CLAIM_ACTION (sha256 6e09c747694a19cf) for SQLite 3.18:
-- `RETURNING` (3.35) as a read of the row, then its delete, in one
-- transaction: the claim's own transaction, which holds the writer, so no
-- second owner deletes the row between the two.
--
-- Runs FIRST in the transaction that applies the action, not last: the pending
-- rows are read outside any transaction, so a second owner holding the same
-- list would otherwise apply every one twice, and `add` and `copy` are not
-- idempotent. A claim that deletes nothing means another owner got there
-- first (§15.2).
SELECT id FROM queue WHERE id = :id;
DELETE FROM queue WHERE id = :id;
