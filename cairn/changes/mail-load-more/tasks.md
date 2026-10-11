---
cairn: tasks
change: mail-load-more
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, tests to android/app/src/test/java/org/pimalaya/.

## Generic

- [x] `MailStep` (interface, `COUNT` 50, `Brought`, `Outcome`, `of(server)`); `GmailStep`; `WidenStep` for IMAP, Graph and JMAP until their own land
- [x] `RemotePass.stepMail(account, session, only, first)`: one step on the account's session, the frontier moved back, all mail when exhausted over the account
- [x] `MailWindow`: `frontier(brought)` (a day of skew), `more`, `reach`; `begin`, `next` go
- [x] `MailEngine`: `land` (a step's members through the engine as a listing), `fetched` (a step's files stored, not read again), `fetchBodies` through `sources` (files, `persisted`), `heldHandles`, `scratch`/`discard`; `fillChunk`, `GMAIL_FILL_CHUNK`, `accountFloor`, `widen(.., stop)` go
- [x] Arrivals: `MailBodies.ARRIVALS` from delta pages, planned last, rows not saying *Not downloaded*
- [x] Wi-Fi only: `MainActivity.wifi`, `waitForWifi`, `wifiWatch`; `mailPass(scope, listing)`; `BackgroundJob` lists nothing on a metered network; body step and fill on Wi-Fi; `MailBodies.CAP`, `fits`, `Host.metered` go
- [x] Footer: *Load more* and its line (`MailList.info`), `MainActivity.loadMore`; `moveWindow`, `reachStep`, the picker's moving back, `MailScope.covering`, `MailStore.newestBelow`/`downloading`/`listing` go; `MailStore.datesBelow` for `WidenStep`
- [x] `MailStore.floorOf`: no coverage floor from a Gmail mailbox; the fill skips Gmail (`edges` filter, `MailFill.batch`)
- [x] Strings en and fr: `mail_more_load`, `mail_more_info`, `mail_more_wifi`, `sync_line_wifi`; the `mail_window_*` footer strings and `mail_row_wifi` go; `filter_download_message` reworded

## Bridge

- [x] `Native.mailStep`, `Native.fetchMessageSources`; `PimalayaClient.mailStep`, `fetchMessageSources`
- [x] rust/src/ffi/mail.rs: `mail_step` (Gmail), `fetch_sources` dispatching per backend (Gmail batched, others one by one through `read_source`); `name_messages` names Gmail members by metadata
- [x] rust/src/offline.rs: `persisted` fetched bodies (`PimdirFetchedBody::Persisted`), a `StoreObject` op with no bytes; `PimdirStorage.storeObject` writes its row alone; `PimdirStorage.handlesOf`

## Gmail

- [x] `gmail_sync::step`: the set-difference walk, history id first; `list_label` keeps the history delta, `round` lists a label's ids since the account's oldest reception, named by markers; the floor walk and the first-chunk machinery go
- [x] `gmail::read_all`: batches of `BATCH` 10, raw batches of about `RAW_BATCH_BYTES` 8 MB, `ALONE_BYTES` 5 MB alone, busy put back after 1 to 2 s, minute after `minute_pause`, daily stops; `read_sources` (minimal pre-pass, raw), `written`, `source_path`
- [x] `gmail::quota`: every unit and rate in one place, the measured figures noted; throttle takes them; a Gmail minute refusal waits `minute_pause`, never the next minute, the budget no longer exhausted
- [x] Java: `MailEngine.nameUnnamed`/`steppedIn` for a Gmail round; `MailRequest.received`

## Tests

- [x] Rust: set-difference step, exhausted walk, spam and trash never stepped, label view, first step landing in every label, expired history by markers (gmail_sync_tests.rs); batches of 10 and refusal re-queue, raw byte batching and lone messages, minute back-off and daily stop, inner answer classification, raw read fields (gmail_tests.rs); throttle's Gmail minute back-off
- [x] Java: `MailStepTest` (a Gmail step landing whole messages in every label, the frontier, the footer line), `MailWindowTest` (frontier skew, `more`), `MailChunksTest` (a step from the store), `MailBodiesTest` (Wi-Fi gate, arrivals), `MailNamingTest` (a Gmail round's naming); removed machinery's tests dropped

## Land

- [x] CHANGELOG [Unreleased] as a net diff
- [x] Build: `cargo test`, `cargo fmt`, `:app:assembleDebug`, `:app:testDebugUnitTest`; cairn/verify.sh
- [x] Fold the delta into spec/mail.md, log entry
- [ ] Device test (the user): a fresh Gmail account on Wi-Fi (first step time, logcat for `gmail` batches and refusals), *Load more* twice, a label view's *Load more*, mobile data (strip, footer, opening one message), an arrival dated in the past
- [x] IMAP, Graph and JMAP steps and body batching (B.13, their own agents)
- [ ] Junk and trash listed on demand when opened (B.13 rule 7)
- [ ] Archive once the device test passes
- [x] A step by reception (Gmail, JMAP without sentAt) never leaves a frontier older than its oldest reception (`byReception` in the step reply, `MailWindow.frontier`)
