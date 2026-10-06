---
cairn: log
change: graph-mail
landed: 2026-10-06
---

# Microsoft mail over Graph

**Setup rows are distinct.** Every Microsoft service carries a code grant and a device grant, and the advanced setup listed one row per method, so each read "OAuth" twice. Rows are now collapsed on what they read, protocol, server and authentication method, after ranking the device grant behind the other OAuth ones, so the code grant is the one kept.

**Graph reads mail.** `client/graph_mail.rs` lists mail folders recursively as mailboxes named by path, recognising the trash by asking for `deleteditems`; lists a folder's newest messages whole, as the JMAP side does, since the message delta is not wired; reads a source as MIME from `$value`; writes `\Seen` as `isRead` and `\Flagged` as the follow-up flag; deletes by moving into `deleteditems`; and sends the composed bytes through `sendMail`, which files the sent copy itself. The mail session gained a Graph kind sharing the JMAP listing cache, and opens with an inbox read so an expired token fails at open, where the app renews it.

**The app offers it.** `searchMerge` keeps `msgraph` among the services the app drives, which it had dropped, so discovery no longer merged Graph mail away before the screen. `msgraph` serves the mail domain, and the sending group, headed Submission, appears once IMAP or Graph is picked for reading and follows that choice: IMAP reading is offered the SMTP servers, Graph reading one Graph row, and both the row for not sending. Graph sending is never paired with IMAP reading, since SMTP signs in with Outlook's token and Graph with Graph's, and pairing them would store and renew two credentials for one mailbox. A Graph account sending stores its own `msgraph://` base as its submit endpoint, so the composer offers it as it offers any sender, and the account settings show no sending server to change.

Capabilities moved: mail (Graph backend, markers, trash, submission, source), onboarding (distinct rows, Graph mail at Microsoft).
