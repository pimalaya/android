-- Releases one item's body, :object_hash by its hash, as release_bases_before
-- and release_before release theirs (STORAGE §11.4): a base or a shared body
-- naming it lets go of it, the binding staying based, then the item goes back
-- to Meta, its summary, flags and bindings kept. The caller checked that the
-- item may let it go, and restates the body's count.
UPDATE bindings SET
       base_object = CASE WHEN base_object = (SELECT id FROM objects WHERE hash = :object_hash)
                          THEN NULL ELSE base_object END,
       shared_object = CASE WHEN shared_object = (SELECT id FROM objects WHERE hash = :object_hash)
                            THEN NULL ELSE shared_object END
WHERE item = (SELECT id FROM items WHERE collection = :collection AND link_id = :link_id);
UPDATE items SET object = NULL, level = 1
WHERE collection = :collection AND link_id = :link_id
  AND object = (SELECT id FROM objects WHERE hash = :object_hash);
