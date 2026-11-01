-- Every category of every card of the store (SEARCH §6), by placement, each
-- value folded as STORAGE Annex A.2 folds `fn` and recorded once: what
-- `category:` reads a card by (`categorised`), read here for every card at
-- once.
SELECT p.collection, p.seq, c.value
FROM placement p
JOIN object o ON o.hash = p.hash
JOIN object_category c ON c.object = o.id
WHERE p.collection IN (SELECT id FROM store.collections WHERE kind = 'text/vcard')
ORDER BY p.collection, p.seq, c.value;
