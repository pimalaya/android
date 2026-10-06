---
cairn: change
id: graph-mail
status: landed
---

## ADDED Requirements

None.

## MODIFIED Requirements

- mail: three backends answer, Microsoft Graph behind `msgraph://`.
- mail: markers map onto `isRead` and the follow-up flag on Graph, which keeps no answered marker.
- mail: a Graph account's trash is `deleteditems`.
- mail: a Graph account submits through `sendMail`, Graph filing the sent copy; sending rows follow the reading choice, SMTP beside IMAP and Graph beside Graph.
- mail: a Graph source is the MIME served at `messages/{id}/$value`.
- onboarding: setup options reading the same are one, the code grant kept; a Microsoft address is offered mail over IMAP and Graph.

## REMOVED Requirements

None.
