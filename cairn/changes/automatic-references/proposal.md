---
cairn: change
id: automatic-references
status: active
created: 2026-10-10
---

# Automatic references, and pimdir's answers to the audit

## Why

pimdir and io-pimdir (f6ca07f) made the C.1 rules canonical statements (pimdir cairn/log/2026-10-10-automatic-references.md) and answered an audit and the requests attachments-as-files, files-tab and item-links raised (2026-10-10-audit-answered.md). The app services every write from Java, so a rule only runs here if the app runs its statement.

## What

- **io-pimdir f6ca07f.** The bridge carries `mail_summary.invitation` on the summary wire; the invitation is derived by io-pimdir's own `derive` (first `text/calendar` part's `UID`, transfer-decoded, unfolded), which the app already calls on every stored body, so no second walk in the app's parser.
- **Reconciliation on open** (`PimdirDb`), as io-pimdir's schema.rs does: `collections` rebuilt by §6's procedure when its role `CHECK` lacks `attachments` (foreign keys off, `legacy_alter_table` on, rows copied, keys checked, its indexes and triggers recreated by the reconcile's second pass); the new index and triggers by that pass; `mail_summary.invitation` added, and when it was missing, every held body's invitation derived and every rule run once over the whole store (`link_senders_of`, `link_invitations_of`, `link_mail_from`, `link_invitations_to` with a `NULL` link id); the attachments collections written before the role given it.
- **Automatic references.** `PimdirSummary.write`, which every summary write goes through (the engine's writes, contacts, calendars, the phone mirrors), runs the rule of the kind it wrote: `link_senders_of` after a mail, `link_mail_from` after a card, `link_invitations_to` after an event or a task. A stored body restates the mail's summary from the body (`MailStore.restateFromBody`, replacing the raw attachment-mark update): mark, size and invitation, then `link_invitations_of`.
- **Stand-ins** only for the parts carrying `Content-Disposition: attachment` (the parts making the mark), none for a message under a writer-derived key (`ItemLinks.derived`, shared by `FileStore.partKey` and `ItemLinks.endpoint`); the attachments collection takes the role `attachments` and `FileStore.isAttachments` reads the role.
- **New statements**: `list_attachments_by_account` (the Files tab's attachments, one paged statement), `delete_unbound_item` (deleting a folder's copy), `get_mail_row` (`MailStore.message`), `describe_endpoint` and `search_contacts`, `search_calendar`, `search_files` (`ItemLinks`), `add_reference` answering the reference standing, `collection_holds_objects` sparing the recount after `delete_collection`.
- **`sum_mail`'s `held`**: the footer and the picker's download line count what a download fetches (`held` 0); a day later in the picker counts what a release frees (`held` 1), shown in the line again.
- Lowercasing with `Locale.ROOT` where the app still used the default locale.
