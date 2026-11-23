-- The body one queue row pins, by its hash, NULL for none.
SELECT (SELECT o.hash FROM objects o WHERE o.id = q.object) FROM queue q WHERE q.id = :id;
