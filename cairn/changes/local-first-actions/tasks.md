---
cairn: tasks
change: local-first-actions
---

# Tasks

## 0. Settle the landing
- [ ] io-pimdir test: a `Move` whose target already holds the identity (minted `dup:` key) is landed by its arrival, not pushed twice; same for an `alt:` key. Fix in io-pimdir if not.

## 1. Move and Copy reach pimdir
- [x] `MutationJson` `move` / `copy` (rust/src/offline.rs) and their `From` arms
- [x] `PimdirEngine.mutateMove` / `mutateCopy`

## 2. Push honours the seam
- [x] Mail `Remove { to }`: IMAP MOVE, else COPY + `\Deleted` + `UID EXPUNGE` (UIDPLUS); Graph move; Gmail label modify; JMAP `mailboxIds`; the move's target create withdrawn once delivered
- [x] Mail `Remove` without `to`: permanent delete per backend (IMAP `UID EXPUNGE` with UIDPLUS, else marker; Graph `permanentDelete`, a bridge request since io-msgraph has none; Gmail `messages.delete`; JMAP destroy)
- [x] Mail `Add` with origin: server-side copy per backend; without: append (IMAP), waiting for the submission; a move's target waits for its source
- [x] Calendar rejects a `Remove { to }` it cannot relocate
- [x] `delete_message` and the trash special cases in the bridge removed
- [x] Tests per backend: relocate, copy, delete, reject

## 3. Delete and trash
- [x] `MessageView.stageDelete`: `mutateMove` into the trash collection outside the trash, `mutateRemove` inside; JMAP without a trash still refused
- [x] A row marked `\Deleted` (no UIDPLUS) says so in the list

## 4. Sent copy
- [x] Send stages an `Add` into Sent beside the submission; IMAP `append_sent` replaced by its push; landed by `Message-ID` where the provider files it
- [ ] Live on Gmail, Graph and Fastmail test accounts (never a personal account): one Sent copy, visible at once (owed by the owner, on a device)

## 5. Pending mark
- [x] Row mark on visible rows (`Created` or `Dirty` not yet pushed; a move's on its target's row; no `Tombstone` is listed), refused distinct; mail, contacts, calendar

## 6. Contacts
- [x] `PimdirContacts.save` / `stageDelete` through `mutateAdd` / `mutateEdit` / `mutateRemove`

## 7. Land
- [ ] Fold delta into cairn/spec/mail.md, calendar.md, contacts spec; log; CHANGELOG
