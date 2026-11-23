-- The app's own since io-pimdir dropped LIST_GARBAGE_OBJECTS (46d851f), as
-- its 0.19.1 text stated it; the Java owner runs it.
--
-- What the collector takes (§5), after recompute_refcounts has settled every
-- count, and no write's business: the batch that attaches a body may not be
-- the one that indexed it. `<= 0` matches the partial index objects_garbage,
-- so neither statement scans.
SELECT hash FROM objects WHERE refcount <= 0;
