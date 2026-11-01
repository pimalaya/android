-- How many rows a mail read lets through, the read taken whole as a
-- subquery rather than restated: what a search or a list hiding rows counts.
-- Wraps: LIST_MAIL_PAGE_FILTERED, SEARCH_MAIL, SEARCH_MAIL_INDEXED, MAIL_HIDING(LIST_MAIL_PAGE_FILTERED), MAIL_HIDING(SEARCH_MAIL), MAIL_HIDING(SEARCH_MAIL_INDEXED).
SELECT count(*) FROM (:inner);
