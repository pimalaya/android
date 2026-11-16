---
cairn: change
id: the-store-follows-pimdir-draft-13
status: landed
created: 2026-11-16
---

# The store follows pimdir draft-13

## Why

pimdir tagged storage-draft-13, sync-draft-13 and search-draft-13 (1c6442e) and io-pimdir 0.14 follows them, its draft-12 never released. Drafts are exact (STORAGE §4.2): the app opens the draft its build names and no other. The two drafts bring two rules the app was waiting on:

- **A narrowed round reads its enumeration alone** (SYNC §5): a member it lacks is absent whatever its date, and a round handing none finds nothing absent. io-pimdir 0.12 still retired a narrowed round's member dated before the round opened, which cairn/log/2026-11-15-a-partial-listing-retires-nothing.md left open: a Microsoft 365 message whose `Date` lies in the download window and whose `sentDateTime` does not was dropped from the phone.
- **A push refused as it stands is held** (SYNC §4, §5, STORAGE §15.4): a connector answering `Refused { reason }` has the refusal recorded on the binding, the engine holding that change while it derives it under the same key and reporting it every run. The app's mail push failed the mailbox's whole pass on every sync for a change the server will never take (an IMAP `NO [CANNOT]`, a Graph 400, a JMAP `invalidProperties`), or pushed it again forever.

The same night the family released the libraries around it (io-http 0.8, io-oauth 0.7 and pimalaya-stream 0.4, which every HTTP and IMAP crate moved to), and the app is to be committed on crates.io releases alone.

## What

- **The manifest**: io-pimdir 0.14, io-http 0.8, io-oauth 0.7, io-imap 0.11.2, io-smtp 0.9, io-jmap 0.10, io-gmail 0.9, io-gcal 0.5.1, io-gpeople 0.8.1, io-msgraph 0.9.2, io-webdav 0.11, mime-rs 0.2, io-gtasks 0.2, io-pim-discovery 0.13; ical-rs 0.7 and vcard-rs 0.6 are their latest already. No `[patch]`, git or path source.
- **The draft**: the store created, checked and stamped at draft-13 by the crate's names; a store of an earlier draft refused and the app opening on its reinstall screen, as at the draft-11 move; an index of an earlier draft rebuilt. The fork of `init_index_meta` read again.
- **Refusals end to end**: the bridge classifies a mail write the server refuses as it stands (`BridgeError::refused`, Java's `PimalayaException.refused`), the mail push answers it refused with the server's text, the engine's `RefusePlacement` and `ClearRefusal` are written by the canonical `refuse_binding` and `clear_refusal`, a load hands the source's refusals (`list_refusals`), and the sync report carries them, logged.
- **What a release changed that would drop data**: an IMAP FETCH decoded in part (io-imap 0.11), a JMAP object io-jmap 0.10 could not read, a CardDAV card listed without its data (io-webdav 0.9) are each not whole, as a skipped line was.
- **The Graph task left open on 2026-11-15**: the dated member added to its test.

## Not in this change

- No screen shows a refusal: it is logged. The queue's receipts name no item for a submission, so `load_receipt_refusals` has nothing to read in this app.
- Calendars, contacts and tasks answer no refusal: their pushes keep their own 422 rule.
