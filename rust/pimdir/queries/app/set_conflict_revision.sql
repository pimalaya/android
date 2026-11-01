-- The remote revision a conflicted binding was marked against, so the
-- resolving push is conditioned on the state the merge used.
UPDATE bindings SET conflict_revision = :conflict_revision
WHERE collection = :collection AND link_id = :link_id AND source = :source;
