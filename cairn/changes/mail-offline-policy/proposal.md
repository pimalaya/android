---
cairn: change
id: mail-offline-policy
status: draft
created: 2026-10-08
---

# Mail bodies downloaded by policy, up to a whole mailbox

## Why

Mail is listed at the meta tier and a body is fetched only when a message is opened (MailEngine.java:34-45, MessageView.java:150). The fill widens metadata, never bodies. The only setting is the per-account bound in months (`MailScope`). So offline, an unopened mail cannot be read, searched by body, or restored from Deleted items; a user who wants a full offline copy has no way to ask for it.

## What

A per-account setting, **Offline mail**:
- **Bodies on open** (default): today's behaviour.
- **Bodies in the background**: after each pass and fill step, the bodies of listed messages within the bound are raised to `Full` (`PimdirUpgrade`, rust/src/offline.rs `upgrade`), newest first, shown collections first, unmetered network by default.
- **Whole mailbox**: the bound set to everything and bodies in the background: a full sync. A per-collection action "Download this mailbox" does the same for one collection.

Attachments ride with the body (the body is the whole RFC 5322 message); no separate attachment policy.

## Open questions

- A storage cap per account, or the bound alone?
- Throttling: body fetches count against Gmail's per-minute units (5 a `messages.get`); the existing pacing applies.
