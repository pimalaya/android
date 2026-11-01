-- How many live calendar items had their occurrences cut before :end by a
-- bound of SEARCH §7 (10,000 occurrences, 1,000,000 periods of a rule), on
-- cut_before's terms over every collection (§8.2): what a range ending at
-- :end does not see in full.
SELECT count(*) FROM placement p
JOIN store.items i ON i.collection = p.collection AND i.seq = p.seq
WHERE p.cut IS NOT NULL AND p.cut < :end
  AND i.deleted = 0 AND i.retained_at IS NULL;
