-- Fork of INIT_INDEX_META (sha256 68f22269d4bdfbf7) for the app's shape:
-- `shape` is `pimalaya-android` (SEARCH §2), never `canonical`, the rest as
-- the canonical text writes it.
INSERT INTO index_meta(id, format_version, shape, version, tokenizer, store_id, store_incarnation)
SELECT 1, 'search-draft-08', 'pimalaya-android', :version, :tokenizer, store_id, incarnation
FROM store.store_meta WHERE id = 1;
