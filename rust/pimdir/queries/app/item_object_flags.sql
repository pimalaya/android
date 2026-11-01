-- One item's body and its flags as one text, on load_items' terms.
SELECT i.object_hash,
       CASE WHEN i.flags_known = 1
            THEN coalesce((SELECT group_concat(f.flag, ' ') FROM placement_flag f
                           WHERE f.collection = i.collection AND f.link_id = i.link_id), '') END AS flags
FROM items i
WHERE i.collection = :collection AND i.link_id = :link_id;
