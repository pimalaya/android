---
cairn: change
id: a-search-says-what-it-covered
status: active
created: 2026-10-18
---

# A mail search says what it did not cover

## Why

The mail list's search answers from the store over every stored header (mail.md: the mail list), and a hit beyond the download window is dimmed *Not downloaded* (mail-download-window). What the list does not say is what the search could not reach: mail a first round or a widening has not listed yet (the frontier, the coverage floor, a round still filling), and an account that is off or waiting for the network. An empty result reads as "no such message" while the message may simply not be on the phone yet.

Borrowed from an external engine: a three-axis search coverage (local content, the time range the store is complete for, whether a server was also asked), rolled up conservatively over the shown accounts, with one "complete" answer driving an "older messages not on the phone" line and, later, a "search the server" action.

## What

- While a search is shown, the list ends on a line when the search is not complete: *Mail before 1 March is not on the phone yet* for the oldest coverage floor among the searched mailboxes, *Still syncing* while a round is open on one of them.
- A search that is complete shows nothing extra.
- The value and its roll-up come from io-pimdir (cairn/changes/search-feature, section "Coverage, three axes") through the bridge, so the phone and himalaya say the same thing; until then the app computes the temporal axis from the coverage rows it already reads for the floor.
- The remote axis (a server search, IMAP `SEARCH`, JMAP `Email/query`, Graph `$search`, offered from that line) is left to a later change.

## Decided (owner, 2026-11-19)

- The coverage is io-pimdir 0.17.0's `PimdirSearchCoverage` (temporal, local and remote axes, `merge`, `is_complete`), read through the bridge; the app computes no axis of its own.
- The line names the worst account: *Mail before 1 March on Work is not on the phone yet*; when several shown accounts fall short, *On 2 accounts*, with the oldest date. A Gmail or JMAP account, never filled, is the usual case.
- First cut covers the temporal and the local axes: matched messages whose body is not on the phone are said (*12 messages searched by subject only*). The remote axis (a server search from the line) stays a later change.

## Rulings on the bridge work (owner, 2026-11-19)

- Two inputs beside io-pimdir's coverage are kept: a Gmail or JMAP account passes its frontier (`MailWindow.since`) as one more coverage scope, since its account-wide rounds claim the whole bound; a mailbox with no row from the server source reads as still filling, not as full. Both are arguably gaps of pimdir SEARCH §8, worth raising upstream.
- Lag: the line says *Search is catching up on N new messages* only when `unindexed` in scope is above 0; `behind` alone, which counts the whole store, shows nothing.
- Bodies not on the phone: *N messages searched by subject and sender only* / *N messages cherchés par objet et expéditeur seulement*.
- A floor at the account's own bound says *not kept on the phone*, not *not on the phone yet*.
