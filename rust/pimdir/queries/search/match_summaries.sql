-- Fork of MATCH_SUMMARIES (sha256 ac2e2f26ef028e9a) for FTS4: no bm25, so
-- `rank` is NULL. :match is a text term compiled for FTS4, over
-- summary_text's columns.
SELECT p.collection, p.seq, NULL AS rank
FROM summary_text
JOIN placement p ON p.id = summary_text.docid
WHERE summary_text MATCH :match;
