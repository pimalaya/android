-- Each handle the `handles` slot names that a source binds in a collection,
-- with its link id and agreed revision.
SELECT b.handle, i.link_id, b.base_revision FROM bindings b JOIN items i ON i.id = b.item
WHERE b.collection = :collection AND b.source = :source
  AND b.handle IN (SELECT value FROM temp.param WHERE slot = 'handles');
