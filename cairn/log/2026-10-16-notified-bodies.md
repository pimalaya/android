---
cairn: log
change: notified-bodies
landed: 2026-10-16
---

# Notified mail downloads before its notification

Capabilities moved: mail (the body step's paragraph gains the background exception).

A background run listed new mail headers-only and notified it, the body step being the open app's alone: tapping the notification showed the new rows dimmed until the app's own body pass landed them. The run now downloads the bodies of the messages it is about to notify, on a session of its own, then notifies; it lists only on an unmetered network, so the download follows the same rule, and a failure still notifies. Trivial fix, no proposal.
