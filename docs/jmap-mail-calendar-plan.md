# JMAP mail and calendars in the app: the missing layers

Status: done. See [Landed](#5-landed).

A JMAP account can be connected for contacts and nothing else. Connecting one for mail or calendars produced an account that saved correctly and was then never read, because `syncMail` speaks IMAP and `syncCalendars` speaks CalDAV and neither looks at what the account actually is. `PimDomain.servedBy("jmap")` now advertises contacts alone, which is honest but is not the answer; this is the answer.

## 1. Why contacts works and the other two do not

Contacts has three layers. Mail and calendars have one each.

| layer | contacts | mail | calendars |
|---|---|---|---|
| entry point | `ffi/card.rs` → `client/dispatch.rs` `Backend::of(base_url)` | `ffi/mail.rs` hardcodes `client::imap::sync_account` | `ffi/calendar.rs` hardcodes the CalDAV verbs |
| protocol client | `client/jmap.rs`, eight RFC 9610 verbs | nothing | nothing |
| library | io-jmap `rfc9610` | io-jmap `rfc8621`, complete and uncalled | does not exist |

So the two are not the same size of problem. **Mail is a two-file job in this repository.** **Calendars need the protocol implemented upstream first** ([io-jmap/docs/jmap-calendars-plan.md](../../io-jmap/docs/jmap-calendars-plan.md)), and only then the same two files.

`Backend::of` already recognises a `jmap://` base (`rust/src/account.rs`), and the Java layer already passes the account's base URL down, so nothing above the bridge changes for either.

## 2. Mail

### 2.1 The client verbs

`rust/src/client/jmap.rs` gains the mail half, beside the contacts half it already has:

- `list_jmap_mailboxes` — `Mailbox/get`, the mailbox roster, mapping to the app's mailbox names.
- `list_jmap_messages` — `Email/query` for the newest N ids per mailbox, then `Email/get` for the envelope spine (`subject`, `from`, `receivedAt`, `keywords`). One round trip each, or one request with a back-reference if the core coroutines expose it.

The shapes to fill are the existing `Message` type: mailbox, uid, subject, from, date, seen. Two mismatches to decide on rather than discover:

- **JMAP has no UID.** `Message.uid` is a `long` because IMAP's is. A JMAP `Email` id is an opaque string. The store already keys items by `link_id` (a string) and only `MailStore` narrows it to a long, so the honest fix is widening `Message.uid` to a string id and letting `MailStore.parseUid` go. Do that first, as its own change, or the JMAP path will be forced through a lossy parse.
- **`seen` is a keyword, not a flag.** `Email.keywords` is a map with `$seen`; map it at the client boundary so the rest of the app keeps one notion of seen.

### 2.2 The dispatch

`rust/src/ffi/mail.rs` stops calling `imap::sync_account` directly and routes:

```rust
match Backend::of(&url) {
    Backend::Jmap => client.sync_jmap_account(&url, &credentials, limit),
    _ => client::imap::sync_account(&mut client, &url, &credentials, limit),
}
```

A `client/dispatch.rs`-style function is the better home if a third mail backend ever appears (Graph, Gmail); for two, the match in the entry point is enough and adding a dispatch module for one branch is premature.

### 2.3 Then

`PimDomain.servedBy("jmap")` adds `MAIL` back, and `PimDomainTest.jmapIsOfferedOnlyWhereThereIsSomethingToReadItWith` becomes a test that mail and contacts are offered and calendars are not. That test is the tripwire: it fails the day someone widens the mapping without the reader, which is exactly the mistake this plan exists to undo.

## 3. Calendars

Blocked on io-jmap. Once the crate has `Calendar/get`, `CalendarEvent/query` and `CalendarEvent/get`:

### 3.1 The client verbs

- `list_jmap_calendars` — `Calendar/get`, into the existing `Calendar` type (id, name, url, description, colour). The "url" of a JMAP calendar is its id; the app's collection id for it should be namespaced by the account the way mailboxes are (`PimdirAccount.collectionId`), not a URL.
- `list_jmap_events` — `CalendarEvent/query` over the window, then `CalendarEvent/get`.

### 3.2 The payload decision, which is the real one

The app's `Event` carries **iCalendar text**, and `rust/src/calendar.rs` expands it with ical-rs to produce the agenda's occurrences. A JMAP event arrives as **JSCalendar JSON**. Two ways:

1. **Convert at the client boundary**: `IcalJscalendar::from_jscalendar` (ical-rs has both directions) turns the payload into the iCalendar the rest of the app already understands. One code path downstream, one conversion per event, and the stored body stays the format `PimdirMeta.CALENDAR` declares (`text/calendar`).
2. **Carry JSCalendar through** and give the agenda a second expansion path.

**Take (1).** The store's `collections.kind` is a media type and the whole point of it is that one kind means one shape; two payload formats under `text/calendar` would fork every reader. The cost is a conversion whose fidelity is ical-rs's problem, which is where it belongs.

Note what this makes cheap: JMAP servers expand recurrence themselves, so the occurrences could come straight from `CalendarEvent/query`. Do not take that shortcut in the first pass — going through iCalendar keeps one agenda path, and the second one can be added later as an optimisation with the first as its oracle.

### 3.3 The dispatch

`rust/src/ffi/calendar.rs` routes on `Backend::of` exactly as mail does, and `PimDomain` adds `CALENDAR` to the JMAP mapping.

## 4. Order

1. Widen `Message.uid` to a string id (§2.1), alone, so the IMAP path proves it.
2. JMAP mail client verbs and the `ffi/mail.rs` dispatch; `PimDomain` gains `MAIL`.
3. io-jmap calendars, upstream.
4. JMAP calendar client verbs, the `ffi/calendar.rs` dispatch, JSCalendar to iCalendar at the boundary; `PimDomain` gains `CALENDAR`.

Steps 1 and 2 unblock a Fastmail mail account today, which is the only way to read that mail without solving IMAP's RFC 8707 `resource` problem. Step 3 is the long pole and is not on this repository's critical path until it lands.

## 5. Landed

All four steps of §4, in order, against io-jmap's calendars module (draft-ietf-jmap-calendars-27, [its plan](../../io-jmap/docs/jmap-calendars-plan.md)). `PimDomain.servedBy("jmap")` now returns all three domains, and `PimDomainTest.jmapIsOfferedOnlyWhereThereIsSomethingToReadItWith` stays an exhaustive assertion so a fourth domain cannot be mapped there without someone stating its reader exists.

The two files per domain the plan predicted were the two files per domain it took: `rust/src/client/jmap.rs` grew the verbs, `rust/src/ffi/mail.rs` and `rust/src/ffi/calendar.rs` each grew a `Backend::of` match. Nothing above the bridge changed for mail. Four things did not go to plan, and they are the parts worth keeping:

- **The id widened to `Message.id`, not `Message.uid`.** §2.1 is titled "JMAP has no UID", so keeping the name would have carried the wrong noun into the new backend. The field is now the string the backend addresses the message by, `MailStore.parseUid` is gone, and the store's link ids are unchanged for IMAP accounts (a UID's text is what was already written).
- **The dates did not agree.** IMAP hands over an RFC 5322 envelope date and JMAP an RFC 3339 `receivedAt`, and `MailDate` only read the first, so every JMAP message would have sorted with a zero stamp: under every IMAP message, in a list whose whole point is one order across accounts. `MailDate` now reads both, which is where the normalisation already belonged.
- **`Native.listEvents` took the account's base URL.** A CalDAV collection URL addresses itself; a JMAP calendar id does not, so the entry point could not tell which backend to route to, let alone find the session. It now takes both URLs, exactly as the card entry points do.
- **The calendar collection id is namespaced by the account**, per §3.1, which meant `EventStore` had to keep the store key and the backend address apart: `StoredCalendar.id` is the namespaced collection id, `StoredCalendar.url` the address a listing round asks for. That was a real bug waiting rather than tidiness: two accounts on one provider routinely hold a calendar `c1`, and one collection row flipping between them merges two people's agendas. `EventStoreTest` pins it. The same latent collision remains for JMAP address books, whose collection id is still a bare `jmap://host/<bookId>`; it is out of this plan's scope and is the next thing to fix here.

§3.2's decision held: the JSCalendar payload converts to iCalendar at the client boundary (`jmap_event` in `client/jmap.rs`, through ical-rs's `from_jscalendar`, with the crate's `jscalendar` feature switched on), so the agenda keeps one expansion path and the store one shape under `text/calendar`. Recurrence is deliberately left folded rather than expanded by `CalendarEvent/query`: that shortcut is the optimisation to add later, with this path as its oracle.

`rust/Cargo.toml` patches io-jmap to git until the calendars module ships in a release; io-jmap 0.3 drops the patch.
