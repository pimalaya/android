-- Fork of REMOVE_REFERENCE (sha256 4d2f5325e8b8db96) for SQLite 3.18:
-- `RETURNING` (3.35) as a read of the row, then its delete, in one
-- transaction.
--
-- Removes one reference (§14.2), whatever its origin: a plain delete, which
-- a rule matching again records anew. Returns the row removed.
SELECT from_kind, from_link_id, to_kind, to_link_id, role, origin, created_at
FROM item_reference
WHERE from_kind = :from_kind AND from_link_id = :from_link_id
  AND to_kind = :to_kind AND to_link_id = :to_link_id AND role = :role;
DELETE FROM item_reference
WHERE from_kind = :from_kind AND from_link_id = :from_link_id
  AND to_kind = :to_kind AND to_link_id = :to_link_id AND role = :role;
