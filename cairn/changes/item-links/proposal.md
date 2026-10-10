---
cairn: change
id: item-links
status: active
created: 2026-10-10
---

# Item links: the Linked card on every item page

## Why

Part C step 6 of docs/mail-window-files-references-plan.md (C.5). pimdir records references between items (STORAGE §14.2) and the app records one rule's (a message's attachments), but nothing shows them, and a person cannot link two items.

## What

- **`ItemLinks`** (store side): `references_from` and `references_to` for an item, each other end resolved to its first live placement and titled from its kind's summary (`list_link_placements`, `load_kind`, then `get_mail`, `get_contact`, `component_of` with `get_event`/`get_task`/`get_journal`, `get_file`); `add` records a `user` reference of role `related` (`add_reference`), `remove` deletes one (`remove_reference`), both run as queries since they answer `RETURNING` rows; `search` finds a few items of each kind by title: messages through the mail search (`search_mail`), contacts, calendar resources and files by paging their summaries.
- **One place resolves endpoints**: `ItemLinks.ofMessage`, `ofContact`, `ofEvent`, `ofFile` and the private `endpoint` say which key stands for an item and whether it may be referenced (a message still in an outbox may not). When pimdir stops referencing messages under derived keys (`alt:`, `dup:`), the rule lands there.
- **`LinkedSection`** (UI): a Linked card, rows sorted by the other item's kind, each titled by it and saying its kind and why in words (*Sent by*, *Attachment*, *Invitation*, *Related* for a rule; *Linked by you* for a person), opening it (a message in the reader, a contact on its page, a calendar resource on its page as its series, a file on its sheet), with an unlink action that asks first and warns that a rule's link may come back while its rule matches. The header's *Link to* opens a picker: a search field over mixed results, a tap linking.
- **Where it shows**: inline at the end of the contact page (not while composing or resolving a conflict) and the calendar entry page (not while composing); from a badge under the reader's attachments (its body fills the page, so the card opens in a dialog, the badge counting the links); and as *Linked* in a file's sheet, also a dialog.

Not in this change: further automatic rules (sender, invitation, contact backfill), the cross-domain search.
