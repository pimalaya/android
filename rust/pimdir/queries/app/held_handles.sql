-- Which of the handles the `handles` slot names a source binds in any of the
-- collections the `collections` slot names, one seek per handle on
-- bindings_by_handle.
SELECT DISTINCT handle FROM bindings
WHERE collection IN (SELECT value FROM temp.param WHERE slot = 'collections')
  AND source = :source
  AND handle IN (SELECT value FROM temp.param WHERE slot = 'handles');
