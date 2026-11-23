-- Lands a relocation's destination create on the handle the relocation
-- stated (SYNC §4, io-pimdir's `LandPlacement`): the binding this source
-- holds under the provisional :handle, which holds no base naming anything,
-- moves to :landed, based on its item's flags and body and on :revision,
-- states no server object id, and agrees with the item's body as its shared
-- one when the item holds a body. A binding holding a base naming anything,
-- or none under that handle, is left as it is: the create was landed,
-- withdrawn or delivered since. The caller records the create delivered
-- instead when the source binds :landed in the collection already
-- (link_for_handle). Seeks bindings_by_handle.
UPDATE bindings SET handle = :landed, base_present = 1,
       base_flags_known = (SELECT i.flags_known FROM items i WHERE i.id = bindings.item),
       base_marks = (SELECT i.marks FROM items i WHERE i.id = bindings.item),
       base_keywords = (SELECT i.keywords FROM items i WHERE i.id = bindings.item),
       base_object = (SELECT i.object FROM items i WHERE i.id = bindings.item),
       base_revision = :revision, server_object_id = NULL,
       shared_object = coalesce((SELECT i.object FROM items i WHERE i.id = bindings.item),
                                shared_object)
WHERE collection = :collection AND source = :source AND handle = :handle
  AND substr(handle, 1, 1) = char(1)
  AND base_flags_known = 0 AND base_revision IS NULL AND base_object IS NULL;
