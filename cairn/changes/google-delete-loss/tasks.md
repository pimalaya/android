---
cairn: tasks
change: google-delete-loss
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/.

## Root causes

- [x] Live baseline on google-carddav, google-caldav, google-gcal (the e2e-suite findings reproduced), then a diagnostic run logging each write's answer and each listing's revision
- [x] CardDAV and CalDAV creates dropped the name io-webdav answers (`x.vcf.vcf` on every CardDAV server, the asked name where Google relocates)
- [x] Google CalDAV answers no ETag to a PUT: the binding kept no revision, and the next listing undid a staged removal
- [x] `PimalayaClient.updateEvent` kept a quoted ETag escaped (Google Calendar), a revision no listing names
- [x] A bound member whose body states another identity (Google's UID rewrite, a CalDAV create landed under another name) was retired with what was staged on it

## Fixes

- [x] rust/src/client/carddav.rs `create_carddav_card` and caldav.rs `create_caldav_event` answer the filed name; dispatch.rs and ffi/calendar.rs pass it on
- [x] `OfflineEngine.landed` (CardDAV) and `CalendarEngine.landed`: a write answering no ETag reads it back with one multiget
- [x] android/client `PimalayaClient.etag`: the update's ETag read as a JSON string
- [x] `PimdirEngine.keepBoundNames`, applied by both drivers' fetch and by `named` to a listing carrying bodies

## Tests

- [x] rust: carddav_tests.rs, caldav_tests.rs (a relocated create kept under the server's name, a plain one under the name asked)
- [x] `OfflineEngineDavCreateTest`: an edit of a card Google rewrote survives an edit elsewhere; a create then delete on a server answering no ETag is deleted
- [x] `CalendarDavWriteTest`: create, edit, delete on a CalDAV server answering no ETag
- [x] android/client `PimalayaClientTest.anUpdatedEtagIsReadAsTheStringTheReplyEncodes`
- [x] Live e2e on google-gcal, google-caldav, google-carddav: 9 of 9 (google-gcal on the primary calendar, Google refusing new scratch calendars that day)

## Other backends

- [x] Graph, JMAP, Google People and Google Calendar creates: named by the id the server answers
- [x] Revisions: every backend answers one on a write but CalDAV and CardDAV, which may not; JMAP reads its own back; Graph's `changeKey` carries no quote

## Land

- [x] Build: `:app:assembleDebug`, `:app:testDebugUnitTest`; cargo fmt, clippy, test; cairn/verify.sh
- [x] Fold into offline-store, log
