---
cairn: log
change: a-dav-listing-keeps-its-status
date: 2026-11-19
---

# A DAV listing failure keeps its HTTP status

A trivial fix, no proposal. io-webdav's `WebdavSyncCollectionError::Send` is `#[error(transparent)]`, so its `source()` skips the `WebdavSendError` it wraps, and rust/src/error.rs `http_status`, which walks the source chain, never saw the status of a `sync-collection` REPORT or of its `PROPFIND` fallback that failed: a 401 or a 404 there crossed JNI with no status. `http_status` now reads inside that variant.

Found while rebasing PR #1 (caldav-capability-fallback), whose test `real_http_failures_are_not_reinterpreted_as_missing_report_support` asserts the status of a failed listing. Test: rust/src/error.rs `a_sync_collection_status_crosses`, which failed on the old code (`left: None, right: Some(401)`).

No spec moved.
