---
cairn: delta
change: drop-google-imap
---

# Delta

## ADDED Requirements

## MODIFIED Requirements

### Requirement: Google connects over its APIs alone
The setups, standard and advanced, SHALL NOT offer Google's IMAP, SMTP, CardDAV or CalDAV, whether io-pim-discovery's provider rule or another source names them: Google's mail SHALL connect over the Gmail API, sending included, its contacts over the People API and its calendars over the Google Calendar API. The app's Google client SHALL NOT ask for the `https://www.googleapis.com/auth/carddav` scope, nor sign in to Google's IMAP, SMTP or CalDAV hosts.

#### Scenario: A Gmail address in the advanced setup
- GIVEN an address the Google provider rule matches, its services discovered with IMAP, SMTP, CardDAV and CalDAV among them
- WHEN the advanced setup lists the ways to connect mail, contacts and calendars
- THEN it lists the Gmail API, the People API and the Google Calendar API, and no IMAP, SMTP, CardDAV or CalDAV row of Google's

## REMOVED Requirements
