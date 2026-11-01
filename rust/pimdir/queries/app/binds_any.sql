-- Whether a source binds anything in a collection, one probe.
SELECT EXISTS (SELECT 1 FROM bindings WHERE collection = :collection AND source = :source);
