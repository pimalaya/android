---
cairn: delta
change: drop-google-dav
---

# Delta

## ADDED Requirements

### Requirement: Google connects over its APIs alone
The setups, standard and advanced, SHALL NOT offer Google's CardDAV or CalDAV, whether io-pim-discovery's provider rule names them or not: Google's contacts SHALL connect over the People API and its calendars over the Google Calendar API. The app's Google client SHALL NOT ask for the `https://www.googleapis.com/auth/carddav` scope, nor sign in to Google's CalDAV host.

#### Scenario: A Gmail address in the advanced setup
- GIVEN an address the Google provider rule matches, its services discovered with CardDAV and CalDAV among them
- WHEN the advanced setup lists the ways to connect contacts and calendars
- THEN it lists the People API and the Google Calendar API, and no CardDAV or CalDAV row of Google's

## MODIFIED Requirements

### Requirement: A provider's own rules are discovered first
Scenario "A Google address": contacts are offered over the People API and calendars over the Calendar API, no longer over CardDAV and CalDAV too.

### Requirement: A placement no source binds is offered under its provisional handle
Scenario "The server files the create under a name of its own": the server is any CardDAV server that files a create under its own name and says so in `Location`, no longer Google's, which the app no longer reaches.

### Requirement: A write records the revision the server holds
Scenario "A server answering no ETag": "as Google's does" goes; the behaviour is any CalDAV server's.

## REMOVED Requirements
