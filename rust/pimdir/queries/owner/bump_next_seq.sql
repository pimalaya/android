-- Fork of BUMP_NEXT_SEQ (sha256 eee8d34b3105ca67) for SQLite 3.18:
-- `RETURNING` (3.35) as the update, then a read of the counter it moved,
-- in one transaction.
--
-- Run only when the item has no id yet.
UPDATE store_meta SET next_seq = next_seq + 1 WHERE id = 1;
SELECT next_seq - 1 FROM store_meta WHERE id = 1;
