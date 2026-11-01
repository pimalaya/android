-- A mail count with the attachment chip off: the counts read mail_summary
-- for the chip alone, so an empty relation of that name stands in and the
-- read joins no summary.
-- Wraps: COUNT_MAIL, COUNT_MAIL_BY_DAY, COUNT_UNREAD.
WITH mail_summary(collection, link_id, attachment) AS (SELECT NULL, NULL, NULL WHERE 0)
:inner;
