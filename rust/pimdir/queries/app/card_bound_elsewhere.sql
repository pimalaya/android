-- Where this source holds a card, with the body a pending create of it
-- carries, in another collection of its account and kind: the origin the
-- create is a membership addition from (pimdir origin_for_link, SYNC §3). A
-- holder whose removal is staged counts, as the canonical rule has it, a live
-- one first. A minted key pairs with the identity it was minted over, the bare
-- key first. The account is the roster's: an id shaped as a URL names none. A
-- binding under a provisional handle, a delivered create, names no member to
-- copy from.
SELECT b.collection, b.handle FROM items i JOIN bindings b ON b.item = i.id
JOIN collections c ON c.id = b.collection
WHERE i.link_id IN (:link_id, CASE WHEN :link_id GLOB 'dup:*'
                                   THEN substr(:link_id, 5, (length(:link_id) - 6) / 2) END)
  AND (i.link_id = :link_id OR 'dup:' || i.link_id || '#' || char(1) || i.link_id = :link_id)
  AND b.source = :source AND b.collection <> :collection
  AND b.base_present = 1 AND substr(b.handle, 1, 1) <> char(1)
  AND (:object IS NULL OR b.base_object = :object)
  AND c.account = (SELECT account FROM collections WHERE id = :collection)
  AND c.kind = (SELECT kind FROM collections WHERE id = :collection)
ORDER BY i.link_id <> :link_id, i.deleted, b.collection
LIMIT 1;
