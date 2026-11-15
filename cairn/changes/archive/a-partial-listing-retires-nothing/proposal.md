---
cairn: change
id: a-partial-listing-retires-nothing
status: landed
created: 2026-11-15
---

# A partial listing retires nothing

## Why

Two of the app's mail connectors hand the engine a round's listing that lacks members of the mailbox without saying so, so the round's last page finds them absent and unbinds them from the phone's replica. pimdir SYNC §4 and §5: a round whose listing a date filter narrowed says so (`narrowed`), its last page hands every handle the collection holds (`enumerated`), and absence is concluded only from that enumeration. Both were found by the read-only audit of the live testing campaign of 2026-11-15.

- **Graph.** The round that walks the unfiltered delta's first pass (`delta_page`, rust/src/mail/msgraph.rs) drops a row whose `sentDateTime` lies out of the scope (`changes`) and answers a round not narrowed. A member is in scope by its summary's date, the `Date` header field, read raw since a-graph-message-is-read-raw. `sentDateTime` is not that field: Exchange stamps it at submission, and on the test tenant the io-smtp CI mails carry a `Date` months older than their `sentDateTime` (live campaign of 2026-11-15). A member whose `Date` lies in the scope and whose `sentDateTime` does not, or one with no `Date` field (in every scope) whose `sentDateTime` lies out of it, is left out of a page the engine reads as complete, and unbound. That round runs when the pass after a first chunk makes the delta link, when a link expired (410), and when the scope moved inside the coverage before any link was made.
- **JMAP.** A narrowed round's enumeration (`mailbox_ids`, rust/src/mail/jmap.rs) asks `calculateTotal` but reads an absent `total` as 0, so an answer stating none ends the enumeration after its first page and every member past it, unlisted by the narrowed listing, reads as absent. RFC 8620 §5.5 has a server return `total` "only if requested" and omit it unless `calculateTotal` is true; it states no other end than an empty `ids` past the last result, and lets a server clamp `limit` to its own maximum. An empty page short of a stated total likewise ends the enumeration today, handing a short list.

## What

- **Graph, the delta's first pass:** under a scope with a bound, the round is narrowed on every page and its last page hands the folder's every id (`folder_ids`: `/messages` selecting the id alone, 1,000 a request), as the band's round already does. Under an open scope it drops nothing, so it stays unnarrowed and asks no enumeration.
- **JMAP, `mailbox_ids`:** paged through the shared `paged` reader (rust/src/client/jmap.rs), ending at the `total` the server states, else at an empty page, `calculateTotal` still asked so a conforming server ends it without that last query. An enumeration ending short of the total its server states (an empty or a non-advancing page) is handed as none: the round then finds no member absent.

### Why not filter the delta on the `Date` field

The other fix the audit offered: filter on the date the summary is named by. The delta selects no header field, so its `$select` would name `internetMessageHeaders`: every row of the first pass, which names the whole folder whatever the scope, would carry the message's whole header block (a few kilobytes, the trace fields included, see a-graph-message-is-read-raw) where it carries an id, a date and two markers now; every later delta too, the link keeping its `$select`; and the links made before would have to be refused for new ones. Reading the `Date` by id instead would name every member of the folder, 20 to a `$batch`. Narrowing keeps the delta as cheap as it is for one enumeration of ids on the round's last page, a rare round, and answers the same mismatch the band's round answers already.

Filtering on the `Date` would hold under any engine, where narrowing leans on the engine reading the enumeration (below). The cost decides it: a folder of 50,000 messages under a first chunk of 50 would carry some 150 MB of header fields (at 3 KB a message, unmeasured) to the phone on each such round, and every later delta its rows' header blocks, against one enumeration of ids.

### What the engine still does

io-pimdir 0.12 (sync-draft-11) finds a narrowed round's member absent whenever its `date` precedes the instant the round opened, whatever the enumeration holds (`list_unstamped_bindings`: `OR s.date < r.round_started_at`). So with this change alone a member stating no `Date` is kept, and a dated member whose `sentDateTime` lies out of the scope is still retired, until the app takes the io-pimdir whose rule reads the enumeration alone (the pending io-pimdir change removes that clause). Patching the clause away in a scratch build of the bridge keeps both.

## Not in this change

- A delta from the checkpoint still keeps or drops a change by `sentDateTime`: a marker change on a bound member whose `sentDateTime` lies out of the scope is not applied, and a new message whose `Date` lies in the scope and whose `sentDateTime` does not is not listed. Neither retires anything, a delta proving no absence (SYNC §5).
- The band's `$filter`, its ceiling, the floor and a step's keyset still read `sentDateTime`; the band's round is narrowed already.
- `folder_ids` pages by `$skip`, so a message removed while it runs can shift another out of the enumeration, as on the band's round today.
- io-pimdir's own change to the engine's absence rule, pending new spec drafts: the app takes it by moving to the io-pimdir that ships it.

## What a user meets

Nothing to see, but a message of a JMAP mailbox on a server that states no total, and a Microsoft 365 message stating no `Date` header, no longer vanish from the phone after a sync; a Microsoft 365 message whose sent date and `Date` header disagree stops vanishing once the app takes the io-pimdir with the corrected rule.
