-- Marks a binding conflicted at the remote revision observed, its diverging
-- body left for the next upgrade to capture.
UPDATE bindings SET conflicted = 1, conflict_revision = :conflict_revision
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id)
  AND source = :source AND conflicted = 0;
