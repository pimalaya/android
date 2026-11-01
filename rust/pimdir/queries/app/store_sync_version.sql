-- The SYNC version the store's sync state follows, NULL before any sync
-- wrote (STORAGE §4.3).
SELECT sync_version FROM store_meta WHERE id = 1;
