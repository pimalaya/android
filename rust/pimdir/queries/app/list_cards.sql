-- An address book's live items holding a body, in store order, with the
-- server's binding (its conflict, diverging body, handle and revision), the
-- item's own conflict, and what a contact row renders read from the store's
-- summaries alone (STORAGE Annex A.2, A.6): the account and seq a search
-- hit names, the card's uid, fn, kind and org, and its first email address.
-- No body is read.
SELECT i.link_id, o.hash, b.conflicted,
       (SELECT x.hash FROM objects x WHERE x.id = b.conflict_object), b.handle, b.base_revision,
       i.conflicted, (SELECT x.hash FROM objects x WHERE x.id = i.conflict_object),
       i.seq, c.account, s.uid, s.title AS fn, s.card_kind AS kind, s.org,
       (SELECT a.value FROM summary_value a
        WHERE a.item = i.id
          AND a.role = 'email'
        ORDER BY a.position LIMIT 1) AS email
FROM items i
JOIN collections c ON c.id = i.collection
JOIN objects o ON o.id = i.object
LEFT JOIN bindings b ON b.item = i.id
                    AND b.source = :source
LEFT JOIN summary s ON s.item = i.id AND s.component = 'contact'
WHERE i.collection = :collection AND i.deleted = 0
  AND i.retained_at IS NULL
ORDER BY i.sort_key;
