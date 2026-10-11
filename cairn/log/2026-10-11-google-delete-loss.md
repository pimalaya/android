---
cairn: log
change: google-delete-loss
landed: 2026-10-11
---

# A write keeps the name, the revision and the identity the server holds

Capabilities moved: offline-store (modified: a create's binding takes the name the server filed it by; added: a write records the revision the server holds, a bound member keeps its identity).

**Why.** The e2e suite found, live on Google, a contact or entry created on the phone and then deleted coming back (CardDAV, CalDAV), the same after an edit on the Calendar API, and a contact's edit staged right after its create pushed a pass late. Four causes: the bridge dropped the name a create was filed by (CardDAV bound `x.vcf.vcf` on every server, and Google relocates a card), Google's CalDAV answers no ETag to a PUT so the binding kept no revision, `PimalayaClient.updateEvent` kept Google Calendar's quoted ETag escaped, and a bound member whose body states another identity (Google rewrites a card's UID) was retired with whatever was staged on it. Each left the binding disagreeing with the next listing, which then undid the staged removal.

**Fix.** rust/src/client/carddav.rs and caldav.rs answer the name io-webdav reports for a create (dispatch.rs, ffi/calendar.rs pass it on). `OfflineEngine.landed` (CardDAV) and `CalendarEngine.landed` read a write's revision back with one multiget when the server answered none. `PimalayaClient.etag` reads an update's ETag as the JSON string it is. `PimdirEngine.keepBoundNames` names a bound member by the identity the store files it under, on every contacts and calendar fetch and on a listing carrying bodies.

**Other backends.** Graph, JMAP, Google People and Google Calendar name created items by the id they answer and answer a revision on every write (JMAP reads its own back; Graph's `changeKey` carries no quote). The name and the revision causes reach any plain CardDAV or CalDAV server; the identity one any server rewriting a UID or relocating a create, and the phone spokes.

**Tests.** rust: carddav_tests.rs, caldav_tests.rs (a relocated create kept under the server's name, a plain one under the name asked). Java: `OfflineEngineDavCreateTest` (an edit of a card Google rewrote survives an edit elsewhere; a create then delete on a server answering no ETag), `CalendarDavWriteTest` (create, edit, delete over CalDAV answering no ETag), `PimalayaClientTest.anUpdatedEtagIsReadAsTheStringTheReplyEncodes`; the three app tests fail with the fixes taken out. Live, 2026-10-11:

| Test | google-gcal | google-caldav | google-carddav |
|---|---|---|---|
| E2eCalendarTest.anEventRoundTripsThroughAScratchCalendar | passed | passed | |
| E2eCalendarTest.anEventWrittenElsewhereIsDeleted | passed | passed | |
| E2eCalendarTest.anEventCreatedOnThePhoneIsDeleted | passed | passed | |
| E2eContactsTest.aContactRoundTripsThroughTheAddressBook | | | passed |
| E2eContactsTest.aContactWrittenElsewhereIsDeleted | | | passed |
| E2eContactsTest.aContactCreatedOnThePhoneIsDeleted | | | passed |

google-gcal ran on the primary calendar, Google refusing new scratch calendars that day (`Calendar usage limits exceeded`); the suite's scratch calendars stay its default.
