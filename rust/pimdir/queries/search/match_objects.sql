-- Fork of MATCH_OBJECTS (sha256 1908a9d9b1c0f7ca) for FTS4: no bm25, the
-- shape ranking nothing (SEARCH §8.2: `date` and `name` alone), so `rank`
-- is NULL. :match is a text term compiled for FTS4 as the bridge does
-- (SEARCH §8.5), never the user's text.
SELECT p.collection, p.seq, NULL AS rank
FROM object_text
JOIN object o ON o.id = object_text.docid
JOIN placement p ON p.hash = o.hash
WHERE object_text MATCH :match;
