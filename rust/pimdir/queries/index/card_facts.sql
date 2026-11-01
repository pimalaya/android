-- What the contacts list asks the index of every card of the store (SEARCH
-- §6): whether its body holds a TEL, an ADR, a BDAY and a PHOTO, as its
-- `object` row records it, by placement. The facts `has:phone` and its
-- siblings read by a seek (`holding`), read here for every card at once.
SELECT p.collection, p.seq, o.tel, o.adr, o.bday, o.photo
FROM placement p
JOIN object o ON o.hash = p.hash
WHERE p.collection IN (SELECT id FROM store.collections WHERE kind = 'text/vcard')
ORDER BY p.collection, p.seq;
