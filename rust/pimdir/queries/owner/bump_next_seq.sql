-- Fork of BUMP_NEXT_SEQ (sha256 9668f8b714efea20) for SQLite 3.18:
-- `RETURNING` (3.35) as the update, then a read of the counter it moved,
-- in one transaction.
--
-- Run only when the item has no id yet.
UPDATE store_meta SET next_seq = next_seq + 1 WHERE id = 1;
SELECT next_seq - 1 AS seq FROM store_meta WHERE id = 1;
