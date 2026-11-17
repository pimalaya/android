---
cairn: log
change: caldav-capability-fallback
landed: 2026-11-16
---

# Choose CalDAV enumeration from collection capabilities

Before a token-less CalDAV round, the Rust bridge now queries supported-report-set and resourcetype with PROPFIND Depth 0. It checks that the response identifies the same-origin target calendar, comparing decoded paths. Calendars that do not advertise sync-collection use io-webdav 0.11.0's existing PROPFIND Depth 1 enumeration, yielding a complete snapshot without a cursor. Subsequent token-less passes repeat that enumeration; normal incremental rounds retain their REPORT and request count. The shared runner accepts its existing options from callers, and CardDAV passes the previous defaults.

There is no generic HTTP 404 fallback, hostname switch, Java/JNI change or dependency update. Missing or mismatched capability rows fail rather than becoming empty calendars. HTTP failures from capabilities, listing, sync REPORT and calendar-multiget remain failures. Existing Google-style 400 and unsupported-report fallbacks are retained.

Five regression tests use the existing Rust FakeServer infrastructure: two independent calendars and repeat enumeration with a changed revision and removed member, supported incremental rounds, existing report refusals, genuine HTTP failures at every phase, and missing or mismatched capabilities. They were not executed, per the user's instruction. Debug assembly does not compile test-only code.

The confirmed defect was ignoring advertised collection-sync capabilities. The user's obfuscated stack does not identify the exact HTTP request behind the reported Mail.ru 404; that attribution remains unproven. The comparison used DAVx5 commit 309e6be50e1572d523356bea4f9d95ac3e1b5845, particularly BaseWebDavCollection.queryCapabilities and CalendarSyncManager.syncAlgorithm. No authenticated Mail.ru requests or device/UI checks were performed; both real calendars loading their 172 events and succeeding again remain manual acceptance checks.

After the user authorized building, cargo fmt and its check passed, and the Windows wrapper completed :app:assembleDebug successfully in 8m 24s, recompiling the Rust bridge for all four ABIs. The APK is android/app/build/outputs/apk/debug/app-universal-debug.apk. It is the debug application, installed separately from release. Tests were not run and no installation was performed.

Capabilities moved: calendar. CHANGELOG.md records the behavior change.
