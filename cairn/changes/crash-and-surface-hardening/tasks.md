---
cairn: tasks
change: crash-and-surface-hardening
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/.

## Bridge

- [x] mail.rs: iterative `sections`, `MAX_DEPTH` 64; `derivable` and `depth` guard every io-pimdir mail derivation (`parse`, ffi/pimdir.rs `pimdirDerive`, gmail.rs `written`)
- [x] Sweep rust/src for recursion on untrusted structure: `collect_scheduled` (calendar) walks ical-rs components, bounded once ical-rs caps depth; nothing else
- [x] calendar.rs: `MAX_WALKED`, `MAX_EXPANSION`; series.rs `remaining` capped
- [x] graph_calendar.rs: `series_windows` within `SERIES_YEARS` for any series
- [x] phone.rs `hidden`: secondly and minutely rules listed
- [x] ffi.rs `ErrorReply`; every export resolved with it; `closeMailSession`, `joinMailRun`, `pimdirVersion` through `with_env`; `JNI_OnLoad` under `catch_unwind`
- [x] logger.rs: warnings until `Native.debugLogs(true)`; client `BuildConfig`

## App

- [x] `MailPoison`: `MailEngine.stored`, `MailEngine.download`, `MailReader.read`, `MailList` row
- [x] `MessageView.linkIntent`: gesture, main frame, scheme allowlist, browsable; URL out of the log
- [x] res/xml/data_extraction_rules.xml, backup_rules.xml, manifest
- [x] `SecureStore.loadAll` drops accounts on a lost key (`keyLost`, `takeLost`); `MainActivity.storedAccounts`
- [x] `SyncService.serves`, `CalendarSyncService.serves`
- [x] `CacheSweep` on start; `OpenedFiles.safe` by bytes
- [x] `CalendarMapping`: `MAX_REMINDERS`, `clip`
- [x] `Accounts.redacted` in release warnings
- [x] `PimalayaClient.read`, `array`: a null reply is an internal error
- [x] Strings en and fr; CHANGELOG

## Tests

- [x] Rust: a 10 000-level message parsed, opened and derived on a 1 MiB thread; depth guard; a three-level message numbered whole; SECONDLY from 1970 bounded; a split far into a huge COUNT; Graph bounded windows; phone listing of sub-hourly rules; panic hint
- [x] Robolectric `HardeningTest`, `CalendarMappingTest`

## Left

- [ ] Device check of the WebView link handling (meta refresh, iframe)
- [ ] OauthFlow loopback listener (S1 L2), with the network-trust change
- [ ] Optional: a UTC variant (`DTSTART:19700101T000000Z`) of `a_secondly_series_from_1970_jumps_to_the_window` beside it in rust/src/calendar/expand.rs:641, the three SECONDLY tests there being floating only (S3 crash input bridge-calendar-expand-secondly.ics)
