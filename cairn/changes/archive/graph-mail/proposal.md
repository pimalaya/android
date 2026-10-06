---
cairn: change
id: graph-mail
status: landed
created: 2026-10-06
---

# Read and send Microsoft mail over Graph

## Why

A Microsoft address was offered mail over IMAP only, each option listed twice. The provider rule names Graph mail beside IMAP, but no mail backend read it, so the domain mapping left it out; and every Microsoft service carries two OAuth grants, code and device, which the advanced setup listed as two identical rows.

## What

- A Graph mail backend behind `msgraph://`: folders as mailboxes by path, the newest messages of each whole per pass, the source read as MIME, `\Seen` and `\Flagged` written, deletes moved into `deleteditems`, sending through `sendMail`.
- Graph mail offered under the mail switch; the sending rows follow the reading choice, SMTP beside IMAP and Graph beside Graph.
- Setup options that read the same collapse into one, the code grant kept.
