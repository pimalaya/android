-- :page_limit rows of a mail read past its first :skip.
-- Wraps: LIST_MAIL_PAGE_FILTERED, SEARCH_MAIL, SEARCH_MAIL_INDEXED, MAIL_HIDING(LIST_MAIL_PAGE_FILTERED), MAIL_HIDING(SEARCH_MAIL), MAIL_HIDING(SEARCH_MAIL_INDEXED).
SELECT collection, seq, link_id, flags, object_hash, sort_key, level,
       message_id, subject, sender, sender_name, date, size, attachment
FROM (:inner)
LIMIT :page_limit OFFSET :skip;
