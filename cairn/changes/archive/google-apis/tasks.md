---
cairn: change
id: google-apis
status: landed
---

- [x] `client/gcal.rs`: calendars, enumeration, series reads, create, update, delete
- [x] `client/gmail.rs`: labels, label listing, history replay, envelopes, source, markers, trash, send
- [x] Gmail kind on the mail session with a per-pass envelope cache
- [x] Keep `gmail` and `gcal` in the discovery merge; map them to mail and calendars
- [x] Sending rows: Gmail reading offered Gmail alone
- [x] SMTP rows per server and sign-in method, collapsed, matched to the mail sign-in
- [x] Spec folded, log written
