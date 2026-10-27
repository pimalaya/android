---
cairn: change
id: a-search-says-what-it-covered
status: draft
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

## Open questions

- Whether the line names one date for all accounts or the worst account by name.
- Body search: the app searches subject and sender only, so the local axis (bodies not held) has nothing to report yet.
