---
cairn: change
id: files-tab
status: active
created: 2026-10-10
---

# The Files tab

## Why

Part C step 4 of docs/mail-window-files-references-plan.md (C.4). attachments-as-files stores every attachment as a file and lets the reader save one into a folder, but there is nowhere to see the folders, nor every attachment at once.

## What

- **A fourth bottom-bar tab, Files** (`FilesList`, panel_files.xml), in the list design of the other three: large title, a meta line counting the files, a search over the file name and the sender, chips, the filter page, an extended add button (*New folder*). Functional, to be redesigned later.
- **The list** is one card per collection the filter shows: the device's folders by name, then each mail account's attachments, headed *Attachments · address*. A folder with no files shows one placeholder row. The chips narrow to folders or attachments (exclusive), and to images, PDFs or documents by media type (exclusive). An attachment row names the sender of its message.
- **A file's actions** (tap a row): open, share (`ACTION_SEND` through `OpenedFiles`), save to a folder, export (`ACTION_CREATE_DOCUMENT`), *Show the message* when a message references it (`references_to`, then the message's placement), and delete for a folder's copy. A stand-in is not deletable by itself. A stand-in's bytes come from its message the way the reader reads them, the message fetched when not held.
- **A folder's actions** (tap its header or placeholder): import a file into it (`ACTION_OPEN_DOCUMENT`, keyed `file:` and 128 random bits, Annex A.7), rename, delete (`delete_collection`, `recompute_refcounts`, after asking).
- **The filter page** lists the device's folders under *On this device* and each account's attachments collection as *Attachments*, never as a folder. `FileStore.isAttachments` is the one place that recognises those by their id until pimdir gives them a role. `MergedFilter` is keyed by a string, so Files keeps its own filter without being a `PimDomain`: no account connects files, and `PimDomain` drives onboarding and sync.
- The drawer lists no collection for any domain (one merged view per tab), so nothing changes there.

## Statements

Two reads and one write are not canonical yet, so this change uses the closest existing ones:

- **Attachments across an account, with their message's sender:** per stand-in, `references_to`, `list_link_placements` and `get_mail`, cached per message. A canonical `list_attachments_by_account(account, after_key, after_seq, limit)` joining the reference and `mail_summary` would make it one paged query.
- **Deleting a file from a collection no source syncs:** `retain_item` then `purge_item` in one transaction, releasing the body. §14.3 names the owner's inserts but no outright delete; a `delete_unbound_item` would say it.
- Files of a folder are `list_files_page_asc`, read 500 at a time.
