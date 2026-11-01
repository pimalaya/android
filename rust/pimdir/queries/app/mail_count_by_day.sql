-- A mail read's rows per day of the wall clock :shift minutes east of UTC,
-- newest day first, undated last, by count_mail_by_day's day expression.
-- Wraps: LIST_MAIL_PAGE_FILTERED, SEARCH_MAIL, SEARCH_MAIL_INDEXED, MAIL_HIDING(LIST_MAIL_PAGE_FILTERED), MAIL_HIDING(SEARCH_MAIL), MAIL_HIDING(SEARCH_MAIL_INDEXED).
SELECT date(nullif(sort_key, ''), coalesce(:shift, 0) || ' minutes') AS day, count(*)
FROM (:inner)
GROUP BY day ORDER BY day DESC;
