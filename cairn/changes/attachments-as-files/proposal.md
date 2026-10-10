---
cairn: change
id: attachments-as-files
status: active
created: 2026-10-10
---

# Attachments as files, saved to folders

## Why

Part C step 3 of docs/mail-window-files-references-plan.md (C.3). pimdir now has references between items (STORAGE §14.2) and the file kind (§14.3, Annex A.7, io-pimdir fc73663): an attachment is a file holding no body, referenced by its message, and gets a body of its own only when it leaves the message. The reader's badges are read off the parsed message today and lead nowhere: an attachment can be neither opened nor kept.

## What

- **Bridge.** `parseMessage` gives each attachment its IMAP section (RFC 3501 §6.4.5), walked in Rust over mail-parser's part tree; a new pure `messagePart(source, section)` answers its decoded bytes. The media type is empty when the part states none (A.7: `NULL`), no longer defaulted.
- **Store.** `FileStore` owns the file side. When a body is stored (an open, the body step), each attachment the account's stand-in collection does not hold yet gets a stand-in at `Meta` keyed `part:<message link id>#<section>`, its `file_summary`, and an `auto` `attachment` reference from the message, in one transaction. Re-storing writes nothing. The stand-in collection is `<account id>/\u0001attachments`, kind `application/octet-stream`, created on demand: deterministic, like the outbox's id, so it is found from the account id alone with no app state to keep in step, and a control character no server puts in a mailbox name. Forgetting an account deletes it (`delete_collection`, `recompute_refcounts`).
- **Reader.** The badges come from `list_attachments`, so a message whose body was released still lists them. A tap offers Open and Save to folder. The bytes are a saved copy's body when one exists, else the part read from the message's body, else the message is fetched first through the open path. Fetching the part alone per backend is left for later. Open goes through a small `content://` provider of the app's own (`OpenedFiles`, no AndroidX), the copy written in the cache.
- **Folders.** A folder is a file collection on the device account (`LocalBook.ACCOUNT`), id `folder:` plus 128 random bits, its name a label. Save to folder lists them with *New folder*; saving stores the bytes as a blob (deduplicated by hash, refcounted) and places the stand-in's key in the folder with that body, sharing its `seq`, the stand-in staying. `FileStore.folders()` and `attachments()` are what the Files tab (C.4) will read.
- io-pimdir pinned to fc73663; an existing store gains `file_summary` and `item_reference_collects_files` on open (`PimdirDb.reconcileDraftShape`).

Not in this change: the Files tab, the linking interface, automatic references other than `attachment`, fetching a part alone.
