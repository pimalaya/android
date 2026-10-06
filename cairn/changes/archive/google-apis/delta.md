---
cairn: change
id: google-apis
status: landed
---

## ADDED Requirements

- calendar: a Google calendar can run over the Calendar API.

## MODIFIED Requirements

- calendar: four backends answer, the Google Calendar API behind `google://`.
- mail: four backends answer, the Gmail API behind `google://`, with an incremental round over the history.
- mail: markers map onto `UNREAD` and `STARRED` on Gmail; its trash is `TRASH`; it sends through `messages.send`; its source is the `raw` format.
- mail: Gmail reading is offered Gmail alone to send through.
- onboarding: a Google address is offered mail over IMAP and the Gmail API, calendars over CalDAV and the Calendar API.

## REMOVED Requirements

None.
