---
cairn: change
id: imap-step-fixes
status: landed
created: 2026-10-11
---

# IMAP step fixes: bodies linked across mailboxes, an honest floor

## Why

The e2e suite on google-imap found two faults in the IMAP step.

**A message showed another message's body.** A message's link id is its handle (rust/src/client/listing.rs `Named`), which on IMAP is a UID, and a UID names a message within its mailbox (and UIDVALIDITY) alone. `PimdirStorage.lookupObjects` answered a link id from any non-contact collection of the store, so once `ImapStep` had read `[Gmail]/All Mail`, every later mailbox linked its UIDs to All Mail's bodies of the same number (144 in the inbox, 287 in another): the scratch mailbox's UID 1 showed All Mail's UID 1. The engine's size witness (SYNC §6) did not catch it: a load carries a message's date alone, never its size. `WidenStep` masked it, its opening fetching by address.

**The first step brought 730 bodies, not 50.** Without `SORT` (Gmail), the floor was the oldest timely `Date` among the 50 newest UIDs. Imported mail has an `INTERNALDATE` equal to its `Date` and high UIDs, so the floor fell to 2025-04: the inbox listed 308 messages, the first step 1,386, and about 730 bodies were read.

## What

- **The identity model, stated.** A message's link id is its handle. On Gmail, JMAP and Graph it is a provider id unique across the account, so the same link id in two mailboxes is one message and its body is linked. On IMAP it is a UID, a key of its mailbox alone: it names nothing in another collection. Rekeying IMAP mail on `Message-ID` or `X-GM-MSGID` would be pimdir's own model, but it breaks the handle-is-link-id rule every mail path relies on (the reader's address, pushes, restores, landing creates) and needs a migration of every IMAP store; it is left as follow-up work.
- **Lookup scoped.** The lookup a body upgrade asks (`PimdirEngine.lookup`) is answered by the mail engine within its own account (pimdir STORAGE §14 binds `:account`), and on IMAP not at all: an IMAP body is always fetched. Calendars keep the store-wide lookup.
- **Repair.** Once per IMAP account, on its next pass, every message body the store holds in one of its mailboxes under a link id another collection holds with the same body is released back to a summary (`release_before`'s predicate for a body the item still needs), so the body step reads it again. Both sides of a wrong link go, since which one is right is not known without the body.
- **The no-`SORT` floor as an order statistic.** The newest UIDs are sampled, 50 then doubling (at most 800 header reads), until the last part read holds no message dated or received at or above the 50th newest `Date` of the sample; the floor is that 50th. The `INTERNALDATE` rule (a late message counting without lowering the floor) goes, an order statistic needing none.
- **One floor for the account.** The first IMAP step reads each stepped mailbox's 50 newest dates (the bridge's floor reply carries them) and takes the 50th newest of them all as the frontier, so the step brings about 50 bodies whatever the mailboxes; all mail when the stepped mailboxes hold 50 or fewer in all.
- **e2e.** `E2eMailStepTest` checks a scratch mailbox against the account's frontier on IMAP: every seeded message from the frontier listed, none below.

## Out of scope

- IMAP link ids keyed on `Message-ID` / `X-GM-MSGID` (above), and with it the references and attachment stand-ins two IMAP mailboxes' equal UIDs still share (`FileStore.partKey`, `item_reference`), and a store-wide `seq` shared by two IMAP messages of one UID.
- A load carrying the listed size, for the engine's witness: Gmail's `sizeEstimate` is not the octets and would cost Gmail its cross-label links.
