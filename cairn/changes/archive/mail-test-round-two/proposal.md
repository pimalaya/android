---
cairn: change
id: mail-test-round-two
status: landed
created: 2026-10-06
---

# A second round of mail testing against Fastmail

## Why

- **Dates were right.** The io-smtp test messages carry a fixed `Date` well before they were received, and the list sorts by the `Date` header. Nothing to change.
- **A marker set on the phone never reached the server.** Logcat showed the engine deriving `update` rather than `setFlags`, which `MailEngine` refuses (`unsupported mail push update`, `rejected:2`). Opening a message stores its body against an item whose base holds none, so the engine reads it as a content edit, pushes that, and withholds the flags beside it.
- **The advanced setup listed SMTP twice**, once with *Password* and once with no method: a second discovery mechanism found the same server without naming one, and the submission rows offered a methodless row where the reading rows offer none.
- **The outbox could not be found.** Waiting messages sat on top of the list with no mailbox name, and the filter did not offer it.
- **Several recipients needed commas**, which most people would not guess.
- **The bars were inconsistent.** The contacts add button carried a person glyph where mail and calendars carry a plus, and each list bar carried its own row of icons.

## What

- The mail sync declares no content push right (`PimdirPushRights.content = false`, through `PimalayaClient.offlineSyncImmutable`), so a stored body is never pushed and the flags go out as `setFlags`.
- A submission row naming no method is dropped when the same endpoint is offered with one.
- Waiting messages are filed under an *Outbox* mailbox, which the filter offers first.
- The composer's address fields hold one removable chip per recipient (`RecipientField`).
- The contacts add button is the plus. Every list bar keeps search out and puts the filter, and on contacts the birthdays, duplicates, address books, import and export, behind one ⋮ overflow. An open search takes the bar up to its clear cross, beside the overflow.
