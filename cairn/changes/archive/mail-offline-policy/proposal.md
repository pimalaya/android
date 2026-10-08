---
cairn: change
id: mail-offline-policy
status: landed
created: 2026-10-08
---

# Mail bodies downloaded by policy, up to a whole mailbox

## Why

Mail is listed at the meta tier and a body is fetched only when a message is opened (MailEngine.java:34-45, MessageView.java:150). The fill widens metadata, never bodies. The only setting is the per-account bound in months (`MailScope`). So offline, an unopened mail cannot be read, searched by body, or restored from Deleted items; a user who wants a full offline copy has no way to ask for it.

## What

A per-account setting, **Offline mail**:
- **Bodies on open** (default): today's behaviour.
- **Bodies in the background**: after each pass and fill step, the bodies of listed messages within the bound are raised to `Full` (`PimdirUpgrade`, rust/src/offline.rs `upgrade`), newest first, shown collections first, unmetered network by default.
- **Whole mailbox**: the bound set to everything and bodies in the background: a full sync. A per-collection action "Download this mailbox", on the mail filter page, does the same for one collection.

Attachments ride with the body (the body is the whole RFC 5322 message); no separate attachment policy.

## Decisions

- No storage cap: the account's bound (`MailScope`, months) is the only limit. Free space is managed from Deleted items and by narrowing the bound.
- Body downloads run on unmetered networks only by default, with a per-account switch to allow metered ones.
- Gmail body fetches count against the per-minute units budget already in the throttle (5 units a `messages.get`); no extra pacing.
- Background sync stays off (cairn/log/2026-10-08-local-first-actions.md, release plan item 3): "in the background" means after each manual pass and each fill step the app runs while open, inside the existing fill loop, never periodic work. The step does not outlive the foreground: the only service, SyncService, is the contacts sync adapter the OS schedules, with no mail pass to hang it on.
- "Download this mailbox" lives on the mail filter page's collection row (a "Download" action beside the name, confirmed by a dialog, "Stop" once on), the one place that lists every mailbox with room for a per-row action.
- Bodies cross the JNI wire in base64 where they are not UTF-8 (a fetch reply's `bodyBase64`, a write's `storeObject`), since a message is bytes: the lossy UTF-8 string the wire used for vCards and iCalendar would corrupt 8-bit parts and break their hash.
