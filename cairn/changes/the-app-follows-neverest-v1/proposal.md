---
cairn: change
id: the-app-follows-neverest-v1
status: active
created: 2026-11-18
---

# The app follows neverest v1, io-pimdir 0.16 and then pimdir v1

## Why

neverest was declared v1-ready by the owner on 2026-11-18 (neverest 1ebd57d), after about 75 fixes logged in neverest/cairn/log between 2026-11-13 and 2026-11-18, most found live on the Google, Microsoft, Fastmail and iCloud test accounts. The app runs the same engine (io-pimdir) over its own connectors in rust/src, so a bug neverest found in its IMAP, Gmail, Graph, DAV, People or Calendar connector is often in the app's connector too, and no live campaign has run the app (.github/cairn/changes/live-testing-2026-11-15/proposal.md:39 leaves Android out).

This is meant to be the last migration before production. The production track waits for a storage-v1 store (production-release proposal, "The production track and F-Droid wait for a storage-v1.0.0 store", and its task "One release once everything is ready"): a release on a draft store would send every user to the reinstall screen at the next format, losing the outbox and staged edits. So everything that is written into a store and cannot be changed in place once stores must survive (an item's link id, a collection's key, the bytes of a body) has to be right before the app follows pimdir v1.

Three read-only analyses ran on 2026-11-18 against android a59655c, neverest 1ebd57d and io-pimdir v0.16.0: the io-pimdir 0.15.1 to 0.16.0 bump, neverest's mail logs, and neverest's contacts, calendars and tasks logs. Their verified findings are the tasks in tasks.md. The heaviest claims were re-read in the code on 2026-11-18 before being written here: the HTTP send disposition (rust/src/mail/session.rs:1258-1270), the QRESYNC ENABLE answer dropped (rust/src/mail/imap.rs:397-414), the Graph series create sending the master alone (rust/src/calendar/msgraph.rs:145-180), the People update never calling `GpeoplePerson::merge_unprojected` (rust/src/contact/gpeople.rs:251), the DAV drain restarting without bound (rust/src/client/webdav.rs:63-113) and the lossy UTF-8 decoding of DAV bodies (rust/src/contact/carddav.rs:129, :253; rust/src/calendar/caldav.rs:278).

## What

Five phases, in this order. Within a phase the tasks are independent unless a task says otherwise. Tasks are grouped by door, as the family's audit rule asks: a one-way door (what a v1 store keeps for good) first, then a data door (a loss or a duplicate on a server), then the patchable ones.

1. **Phase B, io-pimdir 0.16.0.** Mechanical: two version strings, one line of the vector ledger, a one-time index rebuild. The format stays storage-, sync- and search-draft-13. No Android code changes.
2. **Phase I, identity and bytes (one-way doors).** Link ids of cards, entries and tasks against STORAGE §9 and Annex A; the key of a Graph mail folder whose name holds `/` or `%`; a JMAP message's identity derived from its header bytes; a DAV body stored as the server sent it. Each needs an owner decision (below) before code.
3. **Phase D, data doors.** A send delivered twice; a Google contact losing fields the vCard cannot hold; a new series losing its exceptions on Graph and Google; a member retired on the phone by a 403 or 5xx multiget row; an IMAP session read out of step after a batch error.
4. **Phase S, stalls and mis-syncs (patchable).** QRESYNC trusted where the server did not enable it (iCloud); Graph and JMAP bands keyed on the wrong date; Gmail SENT and DRAFT modifies; contact and calendar pushes failing a whole collection on one refused item; floating entries refused by Google and Graph; an unbounded DAV drain; a Google delete of an entry already gone; an SMTP session reused after a refusal; the SMTP sending-limit codes; a Graph override with no instance answered as written.
5. **Phase L, low.** Throttle sleep not cut by a stop; Graph contact folders one level deep; two stray `decode` calls; the unknown-flags write; optional ports (search cursor, `move-in-place`).
6. **Phase V, pimdir v1.** Blocked until pimdir tags storage-v1, sync-v1 and search-v1 and io-pimdir releases the crate following them. Then the version strings, the index version ordering in rust/src/search/held.rs, the conformance statement, the vectors, and the production-release item it closes.

Phase X records the code duplicated between neverest and the app that belongs in an io-* crate. It is not done here: the agreed order (owner, 2026-11-18) is neverest, then the other CLIs, then the libraries settled, then pimdir v1, then the releases. Phase X is the app's input to "the libraries settled"; once a helper is released, the app and neverest drop their copies in a change of their own.

### Out of scope

- neverest itself, including its uncommitted src/sync/driver/spine.rs and tests/hydration.rs (another session's, 2026-11-18).
- The UI for a refused change (2026-11-16 log, "No screen shows a refusal"): a UX step of its own, device-checked.
- Device checks and live suites: listed in tasks.md §G for the owner, never run by an agent without the owner's grant of that day.
- The production-release items unrelated to sync (OAuth verification, supply chain, store listings).

## Owner decisions needed

Each is asked before its task starts. The recommendation is the analyst's; the owner decides.

- **Q1, link ids (task I1).** A card's link id is its raw `UID`, or the handle when it has none (android/app/src/main/java/org/pimalaya/contact/ContactEngine.java:686), with no `esc:` escape nor `hash:` fallback; an entry's is its resource name (calendar/CalendarEngine.java:292), not its `UID`. STORAGE §9 assigns the key from the hint with `esc:` and `hash:`. Recommended: follow §9 for both before v1 (a v1 store keeps its link ids, and a departure would have to be stated for good), unless I1's reading shows the engine already escapes what Java hands it.
- **Q2, a DAV body that is not UTF-8 (task I4).** Recommended: store the served bytes verbatim; read them lossily only for the phone's projection; refuse to write back a phone edit of such a card or entry rather than writing U+FFFD to the server, and log it.
- **Q3, Gmail refusing a send with a 403 that names no rate.** The app parks it for good (rust/src/mail/session.rs:1216); neverest leaves it pending, a missing scope being the user's to fix (neverest/src/sync/submit.rs:339-344). Recommended: keep the app's park, which the user sees and can send again.
- **Q4, a host that is not ASCII.** neverest refuses one (Punycode refusal, neverest/cairn/log/2026-11-17-anonymous-dav-and-ascii-hosts.md); the app passes it on and DNS fails. Recommended: refuse it at the onboarding with a message saying to type the Punycode form, as neverest does.
- **Q5, Graph series exceptions past five years** (rust/src/calendar/msgraph.rs:702 `series_windows`). Recommended: keep the bound, state it in cairn/spec/calendar.md.

### Decided

The owner took every recommendation on 2026-11-18 ("Go for the whole plan with recommended"): Q1 follow STORAGE §9 for cards and entries, Q2 store the served bytes and refuse a phone edit of a body that is not UTF-8, Q3 keep the park of a Gmail 403 send, Q4 refuse a host that is not ASCII at the onboarding, Q5 keep Graph's five-year window and state it.

## Rules for whoever runs this

- Read the repository's AGENTS.md and the spec files a task names first. Line numbers in tasks.md are those of android a59655c, neverest 1ebd57d and io-pimdir v0.16.0: re-read the code before editing, since the owner edits between sessions.
- No git write (commit, add, push, branch...) without the owner's explicit grant for the session; ask "commit now?" before each commit. Sub-agents never write to git. Commits: conventional messages, no AI attribution trailer.
- Never touch a real account: no sync, no live suite, no network write. Posteo is never used. Give the owner the exact command instead (tasks.md §G).
- Every behaviour fix starts with a test that fails on the current code, built the way the real code path builds its input (a scripted server answer, a fake provider), and is seen failing before the fix. Never change production code to make a test pass.
- Rust: no `//` comments added (only short `///` and `//!`, and `// SAFETY:` on unsafe), 80 columns, `cargo fmt`. Java stays thin: a rule belongs in Rust unless a task says otherwise.
- After each phase: fold the delta into cairn/spec, add a dated log entry cairn/log/YYYY-MM-DD-<id>.md (shape: cairn/log/2026-11-16-the-store-follows-pimdir-draft-13.md, with Evidence and Left sections), a CHANGELOG.md line for anything a user sees, and run cairn/verify.sh. The change is archived only once phase V lands.
