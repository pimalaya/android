---
cairn: log
change: google-apis
landed: 2026-10-06
---

# Google mail and calendars over their APIs

**Discovery keeps them.** `searchMerge` drops every service the app does not drive, and the Gmail and Calendar APIs were among them, so the Google rule's own entries never reached the screen. Both are kept now, map to mail and calendars, and are named Gmail API and Google Calendar API. Their accounts sit behind `google://`, the marker the People API already used, `googlePeopleBase` becoming `googleBase`.

**Gmail reads mail.** `client/gmail.rs` lists the labels that file mail, leaving out the marker and view ones (`UNREAD`, `STARRED`, `IMPORTANT`, `CHAT`, the categories), and reads `TRASH` as the trash. A label answers ids alone, so an envelope is a metadata read, its headers parsed by the message parser so encoded words decode. A label's first round reads its newest messages; every round after replays the history from the `historyId` stored as its checkpoint and reads again only the messages that moved, each an item if it still carries the label and gone if it does not, and an expired history (404) falls back to a full round. The mail session gains a Gmail kind whose cache reads a message once per pass. Markers are `UNREAD` and `STARRED`, a delete trashes, the source is the `raw` format, and sending goes through `messages.send` with `Bcc` kept, Gmail filing the copy under `SENT`.

**The Calendar API reads calendars.** `client/gcal.rs` lists the calendar list, and enumerates a calendar in full each pass: masters and lone events, with a series' changed and cancelled instances folded into its revision so an instance edit moves the entry. A series is read with its instances through its `iCalUID` and projected by io-gcal. Writes check that folded revision, then go to the master with Google's own `If-Match`; a create is an import, which keeps the UID an insert would replace. Calendar ids are percent-encoded, a subscribed calendar's `#` otherwise ending the path.

**Sending follows reading.** The submission row that sends through the reading API is generalised from Graph to Gmail, and the account settings show no sending server for either.

Capabilities moved: mail (Gmail backend), calendar (Calendar API backend), onboarding (Google offers).

**SMTP rows read like IMAP rows.** A submission row named its server and no sign-in method, and one was built per discovered config, so Fastmail, found by two mechanisms, listed the same SMTP server twice. Rows are now one per server and method, labelled with the method, collapsed when they read the same, and offered only when their method is of the kind the mail sign-in uses, since sending signs in with that credential.
