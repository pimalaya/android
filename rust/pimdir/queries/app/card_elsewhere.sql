-- A card's placements in the other collections of its account and kind,
-- retained rows aside, a minted key paired with the identity it was minted
-- over either way: whether each is a staged removal, and whether this source
-- binds it there with a base. What a membership change on an account-level
-- backend reads: a removal deletes the card when none lives on, a patch takes
-- it out of the books it leaves, and a create of a card the account already
-- binds is never sent as a second card. The account is the roster's.
SELECT i.collection, i.deleted,
       EXISTS (SELECT 1 FROM bindings b
               WHERE b.item = i.id AND b.source = :source AND b.base_present = 1)
FROM items i JOIN collections c ON c.id = i.collection
WHERE i.link_id IN (:link_id, 'dup:' || :link_id || '#' || char(1) || :link_id,
                    CASE WHEN :link_id GLOB 'dup:*'
                         THEN substr(:link_id, 5, (length(:link_id) - 6) / 2) END)
  AND i.collection <> :collection AND i.retained_at IS NULL
  AND c.account = (SELECT account FROM collections WHERE id = :collection)
  AND c.kind = (SELECT kind FROM collections WHERE id = :collection)
ORDER BY i.collection;
