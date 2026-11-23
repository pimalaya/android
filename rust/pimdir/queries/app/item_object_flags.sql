-- One item's body and its flags as one text, on load_items' terms.
-- The body by its hash.
SELECT (SELECT o.hash FROM objects o WHERE o.id = i.object) AS object_hash,
       CASE WHEN i.flags_known = 1
            THEN trim(CASE WHEN i.marks & 1 THEN '\Seen ' ELSE '' END
                   || CASE WHEN i.marks & 2 THEN '\Answered ' ELSE '' END
                   || CASE WHEN i.marks & 4 THEN '\Flagged ' ELSE '' END
                   || CASE WHEN i.marks & 8 THEN '\Draft ' ELSE '' END
                   || CASE WHEN i.marks & 16 THEN '\Deleted ' ELSE '' END
                   || i.keywords) END AS flags
FROM items i
WHERE i.collection = :collection AND i.link_id = :link_id;
