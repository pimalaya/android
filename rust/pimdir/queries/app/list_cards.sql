-- An address book's live items holding a body, in store order, with the
-- server's binding (its conflict, diverging body, handle and revision), the
-- item's own conflict, and what a contact row renders read from the store's
-- summaries alone (STORAGE Annex A.2, A.6): the account and seq a search
-- hit names, the card's uid, fn, kind and org, and its first email address.
-- No body is read.
SELECT i.link_id, i.object_hash, b.conflicted,
       b.conflict_object, b.handle, b.base_revision,
       i.conflicted, i.conflict_object,
       i.seq, c.account, s.uid, s.fn, s.kind, s.org,
       (SELECT a.address FROM item_address a
        WHERE a.collection = i.collection AND a.link_id = i.link_id
          AND a.role = 'email'
        ORDER BY a.position LIMIT 1) AS email
FROM items i
JOIN collections c ON c.id = i.collection
LEFT JOIN bindings b ON b.collection = i.collection AND b.link_id = i.link_id
                    AND b.source = :source
LEFT JOIN contact_summary s ON s.collection = i.collection AND s.link_id = i.link_id
WHERE i.collection = :collection AND i.deleted = 0
  AND i.retained_at IS NULL AND i.object_hash IS NOT NULL
ORDER BY i.sort_key;
