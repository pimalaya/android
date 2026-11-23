-- Fork of ROLL_COVERAGE (sha256 0e1ff592d56869b9) for SQLite 3.18: an
-- aggregate's `FILTER` (3.30) as the aggregate of a `CASE` naming NULL
-- where the filter fails, which the aggregate skips as the filter would.
--
-- The coverage of the sources of the collections of the `collections`
-- slot (STORAGE §4.4) rolled up in one row, the temporal axis of a search
-- (SEARCH §8.2): the highest floor and the lowest ceiling of those that
-- closed a round, and how many never closed one. What `list_coverage`
-- answers one collection at a time, read in one statement.
SELECT max(CASE WHEN covered_at IS NOT NULL THEN covered_since END),
       min(CASE WHEN covered_at IS NOT NULL THEN covered_until END),
       count(*) - count(covered_at)
FROM sources
WHERE collection IN (SELECT value FROM temp.param WHERE slot = 'collections');
