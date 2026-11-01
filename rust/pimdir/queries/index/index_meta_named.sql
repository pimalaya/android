-- The version and the shape an index names (SEARCH §2), read once its
-- index_meta is known to hold both columns.
SELECT format_version, shape FROM main.index_meta WHERE id = 1;
