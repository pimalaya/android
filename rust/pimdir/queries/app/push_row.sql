-- What a push reads of one item: its body, whether it is removed, and the
-- source's handle, base body and revision.
-- Each body by its hash.
SELECT (SELECT o.hash FROM objects o WHERE o.id = i.object), i.deleted, b.handle,
       (SELECT o.hash FROM objects o WHERE o.id = b.base_object), b.base_revision
FROM items i
LEFT JOIN bindings b ON b.item = i.id
                    AND b.source = :source
WHERE i.collection = :collection AND i.link_id = :link_id;
