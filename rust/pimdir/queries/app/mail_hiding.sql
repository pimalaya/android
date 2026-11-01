-- A mail read with the rows the `hidden_collections` and `hidden_links`
-- slots name left out, each pair at one position (the sent copies a waiting
-- submission's outbox row stands for), at most :hiding_limit rows (-1 all),
-- the order restated, a subquery's own being no promise.
-- Wraps: LIST_MAIL_PAGE_FILTERED, SEARCH_MAIL, SEARCH_MAIL_INDEXED.
SELECT collection, seq, link_id, flags, object_hash, sort_key, level,
       message_id, subject, sender, sender_name, date, size, attachment
FROM (:inner)
WHERE NOT EXISTS (SELECT 1 FROM temp.param c
                  JOIN temp.param l ON l.slot = 'hidden_links' AND l.position = c.position
                  WHERE c.slot = 'hidden_collections'
                    AND c.value = collection AND l.value = link_id)
ORDER BY sort_key DESC, seq DESC, collection DESC
LIMIT :hiding_limit;
