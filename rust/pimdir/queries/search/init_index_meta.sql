-- Fork of INIT_INDEX_META (sha256 5cb3e1c422883bd1) for the app's shape:
-- `shape` is `pimalaya-android` (SEARCH §2), never `canonical`, the rest as
-- the canonical text writes it.
INSERT INTO index_meta(id, format_version, shape, version, tokenizer, store_id, store_incarnation,
                       extraction, extractor)
SELECT 1, 'search-draft-15', 'pimalaya-android', :version, :tokenizer, store_id, incarnation,
       :extraction, :extractor
FROM store.store_meta WHERE id = 1;
