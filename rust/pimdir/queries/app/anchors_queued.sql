-- Whether a queued action, pending or parked, is anchored on a collection.
SELECT 1 FROM queue WHERE collection = :collection LIMIT 1;
