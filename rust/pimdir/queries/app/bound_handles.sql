-- Each handle the `handles` slot names that a source binds in a collection,
-- with its link id and agreed revision.
SELECT handle, link_id, base_revision FROM bindings
WHERE collection = :collection AND source = :source
  AND handle IN (SELECT value FROM temp.param WHERE slot = 'handles');
