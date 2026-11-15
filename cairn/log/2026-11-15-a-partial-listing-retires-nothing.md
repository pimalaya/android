---
cairn: log
change: a-partial-listing-retires-nothing
date: 2026-11-15
---

# A partial listing retires nothing

Two defects of the app's own mail connectors, found by the read-only audit of the live testing campaign of 2026-11-15: each handed the engine a round's listing lacking members of the mailbox without saying so, so the round's last page found them absent and unbound them from the phone's replica (pimdir SYNC §4, §5).

## What landed

- **Graph, the delta's first pass** (rust/src/mail/msgraph.rs, `delta_page`): the round that walks it keeps a row by `sentDateTime` (`changes`), which Exchange stamps at submission: on the test tenant the io-smtp CI mails carry a `Date` months older than it. Under a scope with a bound, every page of that round is now `narrowed`, and its last page hands the folder's every id (`folder_ids`, `/messages` selecting the id alone, 1,000 a request, after the page holding the delta link), as the band's round already did. Under an open scope the round drops nothing, so it is neither narrowed nor enumerating. The round runs when the pass after a first chunk makes the delta link, after an expired link (410), and when the scope moved inside the coverage before any link was made.
- **JMAP, a narrowed round's enumeration** (rust/src/mail/jmap.rs, `mailbox_ids`): it read an absent `total` as 0, so an answer stating none ended the enumeration after its first page. It now pages through the shared `paged` (rust/src/client/jmap.rs), ending at the `total` the server states, else at an empty page, `calculateTotal` still asked; an enumeration ending short of a stated total (an empty or a non-advancing page) is handed as none, and the round then finds no member absent. RFC 8620 §5.5: `total` is returned "only if requested" and MUST be omitted when `calculateTotal` is not true, the server may clamp `limit` to its own maximum, and a position past the end answers an empty `ids`; no other end is stated.
- **Docs**: the doc comments of `MESSAGE_SELECT`, `floor`, `DELTA_SELECT`, `Band` and its fields, `band_page`, `delta_page` and `changes` no longer call `sentDateTime` the `Date` header.

## The fix chosen for Graph, and what the engine still does

Narrowed with the folder's ids, not filtered on the `Date`: the delta selects no header field, and selecting `internetMessageHeaders` on it would carry every message's header block on the first pass, which names the whole folder whatever the scope (some 150 MB for 50,000 messages at 3 KB each, unmeasured), and on every later delta, the link keeping its `$select`, the links made before refused for new ones. Narrowing costs one enumeration of ids on a rare round.

Narrowing protects a member only as far as the engine reads the enumeration. io-pimdir 0.12 (sync-draft-11, the app's pin) finds a narrowed round's member absent whenever its `date` precedes the instant the round opened, whatever the enumeration holds (`list_unstamped_bindings`: `OR s.date < r.round_started_at`; the app runs that statement as io-pimdir writes it, its own shape holding none). So with io-pimdir 0.12:

- a member stating no `Date` (in every scope) whose `sentDateTime` lies out of the scope is kept now, the enumeration naming it;
- a member whose `Date` lies in the scope and whose `sentDateTime` does not is still retired, until the app takes the io-pimdir whose rule reads the enumeration alone (the pending, uncommitted io-pimdir change removes that clause, among other engine changes). spec/offline-store.md says so ("stands short of this requirement").

A scratch build of the bridge on io-pimdir 0.12.0 with that one clause removed, the rest of the engine as released, kept both members and still retired a message gone. It models the corrected rule for a narrowed round handing an enumeration; it is not the io-pimdir change itself. Nothing outside the scratchpad was touched for it.

## Tests

New, each seen failing on the old code, in one run of `cargo test --offline --lib -- mail::msgraph::tests mail::jmap::tests` over a scratchpad copy of rust/ holding the new tests, HEAD's msgraph.rs, and HEAD's jmap.rs with only `mailbox_ids`' return lifted to `Option` (its old loop answering `Some(ids)`), so the new JMAP tests compile against the old behaviour; the 51 other tests of both modules passed there:

- `mail::msgraph::tests::a_delta_round_keeps_a_member_its_sent_date_leaves_out` (rust/src/mail/msgraph/tests.rs): through io-pimdir's engine, a folder of 101 listed whole, then the round over the delta's first pass under a bound before any link, a message stating no `Date` whose `sentDateTime` lies 40 hours below the bound kept, one removed retired. Old code: `panicked at src/mail/msgraph/tests.rs:1006:5: no `Date`, in every scope`. With a temporary print, the old code also lost the dated member and the new one kept the undated member only, on io-pimdir 0.12.
- `mail::msgraph::tests::a_delta_round_under_a_bound_hands_the_folders_every_id`: at the connector, a folder of 10, pages of 4, under a bound listing 6: three pages, every one narrowed, the last alone enumerating the 10 ids; under an open scope, none narrowed, none enumerating, no id requested. Old code: `assertion failed: bounded.iter().all(|page| page.narrowed)`.
- `mail::jmap::tests::an_enumeration_stating_no_total_reads_every_page` (rust/src/mail/jmap/tests.rs): five ids, two a page, no `total`. Old code: `assertion left == right failed: past the first page`, left `Some(["e0", "e1"])`, right the five; on the unchanged tree before the fix, left `["e0", "e1"]`.
- `mail::jmap::tests::an_enumeration_short_of_its_total_is_none`: three ids answered, a `total` of five stated. Old code: left `Some(["e0", "e1", "e2"])`, right `None`.

The fake Graph folder learned header fields (`Message::fields`, answered as `internetMessageHeaders` by a read), and the JMAP enumeration's fake moved into `mailbox_ids_over`, which `a_mailboxs_every_id_is_queried_by_the_mailbox_alone` now uses, asserting `calculateTotal` is asked; its assertions are otherwise unchanged and it passes on both codes.

## Evidence

Each as `nix develop --command <cmd>` from the repository root, `PIMALAYA_IO_PIMDIR_SPEC` at io-pimdir v0.12.0's spec/ (taken by `git archive v0.12.0 spec` from the local io-pimdir clone into the scratchpad), on 2026-11-15:

- `cargo fmt --check` in rust/: clean.
- `cargo clippy --offline --all-targets --locked -- -D warnings` in rust/: clean.
- `cargo test --offline --locked` in rust/: 682 passed, 1 ignored (678 before, four added).

The APK was not built and the gradle unit tests were not run; no Java changed. Nothing was installed on a device, no live suite was run and nothing connected to an account.

## Not done

- **The dated Graph member** waits on the io-pimdir bump (above). Then add such a member to `a_delta_round_keeps_a_member_its_sent_date_leaves_out` and drop the shortfall sentence from spec/offline-store.md.
- **A delta from the checkpoint** still keeps or drops a change by `sentDateTime`: a marker change on a bound member whose `sentDateTime` lies out of the scope is not applied, and a new message whose `Date` lies in the scope and whose `sentDateTime` does not is not listed. Neither retires anything.
- **The band, the floor and a step** still read `sentDateTime`; the band's round was narrowed already, and is exposed to io-pimdir 0.12's rule as the delta's round is.
- **`folder_ids` pages by `$skip`**: a message removed while it runs can shift another out of the enumeration, on the delta's round now as on the band's.
- **A device check** against a Microsoft 365 account: how long `folder_ids` takes on a large folder after the delta's first pass, on a phone.

## Capabilities moved

- mail (cairn/spec/mail.md): modified "A Graph mailbox keeps one unfiltered delta link" (`sentDateTime` is no `Date`, the delta's round narrowed under a bound, a scenario added).
- offline-store (cairn/spec/offline-store.md): modified "A narrowed round enumerates its collection" (an app's own check of another date narrows, JMAP paged to its total or an empty page, a short enumeration handed as none, the io-pimdir 0.12 shortfall, a scenario added).
