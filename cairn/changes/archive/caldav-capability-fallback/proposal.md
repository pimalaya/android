---
cairn: change
id: caldav-capability-fallback
status: landed
created: 2026-11-16
---

# Respect CalDAV collection sync capabilities

## Why

Mail.ru calendars are discovered successfully but synchronization fails with HTTP 404. The supplied DAVx5 evidence reports canCollectionSync=false and successful synchronization of 172 events. The bridge currently starts every collection enumeration with sync-collection, ignoring supported-report-set. io-webdav 0.11.0 already exposes capabilities and a PROPFIND Depth 1 enumeration fallback, but does not classify a generic 404 as an unsupported report.

This is a confirmed capability-selection defect, not a confirmed identification of the user's failing HTTP request: the supplied obfuscated stack has no HTTP method/body, and matching release mappings are unavailable. DAVx5's queryCapabilities and CalendarSyncManager select full enumeration when collection sync is not advertised. No server request, build, test or formatter has been run for this investigation.

## What

- Before a token-less CalDAV round, query the collection's supported-report-set with PROPFIND Depth 0 and verify the returned row identifies the target collection.
- If collection sync is not advertised, select io-webdav's existing PROPFIND Depth 1 fallback. Return a full snapshot without a cursor so subsequent passes enumerate correctly.
- Preserve token-based incremental rounds and the existing rejected-token and unsupported-report handling. CardDAV keeps its current selection behavior.
- Propagate HTTP 404 and other real failures from capabilities, enumeration and calendar-multiget unchanged. Do not add a status-only 404 fallback or a Mail.ru hostname exception.
- Reuse the existing Rust unit-test/FakeServer infrastructure for capability selection, two calendars, repeat enumeration, supported providers, and genuine failures. Add no test framework or dependencies.
- Update the calendar spec and changelog after implementation.

## Scope and limits

Rust only, including existing test modules. No Java/JNI/schema changes, dependency-cache edits, capability persistence, getctag optimization or new logging. A token-less pass gains one capability request; ordinary incremental rounds keep their current request count. The user initially requested no execution, then approved implementation without tests and subsequently authorized building the project. Run cargo fmt and assemble the debug APK; do not run tests, the application or live-server probes. Successful loading of both real Mail.ru calendars remains an unverified acceptance criterion until the user tests the change.
