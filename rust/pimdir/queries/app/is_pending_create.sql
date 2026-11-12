-- Whether a link id is a live pending create in a collection: unbound by
-- the source, bound with no base, or delivered, based under its provisional
-- handle until a listing lands it (pimdir SYNC §2): none names a member to
-- fetch or push.
SELECT 1 FROM items i
LEFT JOIN bindings b ON b.item = i.id
                    AND b.source = :source
WHERE i.collection = :collection AND i.link_id = :link_id AND i.deleted = 0
  AND i.retained_at IS NULL
  AND (b.item IS NULL OR b.base_present = 0 OR substr(b.handle, 1, 1) = char(1));
