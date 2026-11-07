---
cairn: log
change: locked-notifications-show-the-count
date: 2026-11-07
---

# A locked phone shows only the count of new mail

Asked by the owner on 2026-11-07 (production-release §5, audit-s1-residuals I1), folded without a proposal.

Capabilities moved: onboarding (added: A locked phone shows only how many messages arrived).

A new-mail notification posted sender and subject with no public version, so a locked phone showed them whenever its own setting showed all content, Android's default. Every notification now carries a public version reading only the count ("1 new message", the summary "3 new messages"), and the app's Settings page gains a Notifications card with "Sender and subject on the lock screen", off by default.

A notification's own `VISIBILITY_PRIVATE` redacts only when the phone hides sensitive content; a channel's lock-screen visibility of `VISIBILITY_PRIVATE` redacts on every phone, but an app sets it only when it creates the channel. So the choice picks the channel (BackgroundJob `channel`): off, `hidden-mail-<email>`, made private; on, `mail-<email>`, the channel accounts already had, the phone's setting deciding. The other one is deleted, so turning the setting over resets the account's sound and importance in the phone's notification settings, and Android counts a deleted category there.

Left from I1: the channel is still named by the email address.
