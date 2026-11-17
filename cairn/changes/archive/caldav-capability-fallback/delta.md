---
cairn: delta
change: caldav-capability-fallback
---

## ADDED Requirements

### Requirement: CalDAV enumeration respects collection capabilities

Before a token-less CalDAV enumeration, the bridge SHALL query the target collection's supported-report-set. A successfully identified calendar that does not advertise sync-collection SHALL be enumerated with io-webdav's existing PROPFIND Depth 1 fallback, returning a complete snapshot without a cursor. Following passes SHALL reconcile that snapshot normally. A provider advertising sync-collection SHALL keep the REPORT path, and existing incremental cursors SHALL keep their current handling. HTTP 404 from capabilities, enumeration or multiget SHALL remain an error, never an empty successful listing or an unconditional fallback trigger.

#### Scenario: Two Mail.ru-style calendars without collection sync

- GIVEN two existing calendars that do not advertise sync-collection
- WHEN each is synchronized initially and again without a cursor
- THEN each returns its own complete member list through PROPFIND, and event bodies remain fetched through calendar-multiget.

#### Scenario: A real missing collection or failed multiget

- GIVEN capabilities, enumeration or calendar-multiget returns HTTP 404
- WHEN the bridge receives that failure
- THEN synchronization fails with that error and does not treat the calendar as empty.

## MODIFIED Requirements

## REMOVED Requirements
