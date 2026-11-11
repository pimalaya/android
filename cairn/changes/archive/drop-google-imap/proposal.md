---
cairn: change
id: drop-google-imap
status: landed
created: 2026-11-11
---

# Google mail over the Gmail API alone, not IMAP and SMTP

## Why

Google's review checklist (oauth-verification, 2026-11-10) asks the demo video to show every use the scope justification names. The `https://mail.google.com/` justification names two: deleting a message for good (Gmail API `users.messages.delete`) and IMAP and SMTP over XOAUTH2, which take no other scope. The first is filmed (02-mail-app.mp4, Gmail's Bin emptied); the second would take its own sign-in and clip, for a path that brings users nothing once the client is verified: the Gmail API already reads, searches, sends, labels, moves and deletes, with history-based deltas. Decided by the owner on 2026-11-11, mirroring drop-google-dav.

The scope stays: deleting for good needs it alone, and every Gmail scope reading bodies is restricted anyway (scope-audit.md).

## What

- The setups, standard and advanced, no longer offer Google's IMAP or SMTP: SetupPlan.offered drops an IMAP or SMTP config from Google's provider rule or on Gmail's hosts (`imap.gmail.com`, `smtp.gmail.com`), whatever the source names it (an autoconfig database lists them too). Mail connects over the Gmail API, sending through it.
- The app's Google client no longer signs in to those hosts: they leave `Oauth.GOOGLE_HOSTS` and its twin in rust/src/account/identity.rs, so a manual IMAP entry on Gmail's hosts takes a generic grant, which Google cannot serve without a client of the user's own.
- io-pim-discovery is unchanged; the generic IMAP and SMTP handling and its tests stay.
- No migration: a tester's Gmail account over IMAP keeps syncing while its grant holds; to move it onto the API, remove it and add it again.
- oauth-verification follows: the scope's justification keeps the deletion alone, the video drops its IMAP clips, the reply says so.
