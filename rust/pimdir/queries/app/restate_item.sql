-- Restates a held item from an upsert: its body, level and liveness, revived
-- if it was retained. An empty :sort_key keeps the stored one (STORAGE
-- §9.3), so a write not restating the key keeps the order.
UPDATE items SET object_hash = :object_hash, level = :level,
       sort_key = CASE WHEN :sort_key = '' THEN sort_key ELSE :sort_key END,
       deleted = :deleted, retained_at = NULL, retained_by = NULL
WHERE collection = :collection AND link_id = :link_id;
