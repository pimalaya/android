-- Fork of CANCEL_ACTION (sha256 50693c8ae2813f67) for SQLite 3.18:
-- `RETURNING` (3.35) as a read of the row, then its delete, in one
-- transaction.
--
-- Removal by request rather than by application (§15.5): a queued item
-- withdrawn, or an intent acknowledged by the process that carried it out,
-- which records its receipt (record_receipt) in the same transaction. It
-- returns the hash of the body the row pins, released in the same transaction
-- (§5, §14).
SELECT (SELECT o.hash FROM objects o WHERE o.id = queue.object) AS object_hash FROM queue WHERE id = :id;
DELETE FROM queue WHERE id = :id;
