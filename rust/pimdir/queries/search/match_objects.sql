-- Fork of MATCH_OBJECTS (sha256 e195b69aab2a76e5) for FTS4: no bm25, the
-- shape ranking nothing (SEARCH §8.2: `newest-first` and `title` alone), so
-- `rank` is NULL. :match is a text term compiled for FTS4 as the bridge does
-- (SEARCH §8.5), never the user's text.
SELECT p.collection, p.seq, NULL AS rank
FROM object_text
JOIN placement p ON p.object = object_text.docid
WHERE object_text MATCH :match;
