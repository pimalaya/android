---
cairn: delta
change: ical-rs-0-6
---

Folds into `spec/calendar.md`.

## ADDED Requirements

### Requirement: A written line is folded
Every content line the app writes into a calendar object SHALL be folded at 75 octets (RFC 5545 3.1), never inside a UTF-8 sequence; a line it parsed and did not change SHALL keep its bytes.

#### Scenario: A long title
- GIVEN an entry carrying an unfolded `X-` line of 99 octets
- WHEN its title is edited to 90 characters
- THEN the `SUMMARY` line goes out folded after its 75th octet and reads back whole, and the `X-` line goes out as it came

### Requirement: A phone time past a gap keeps its hour
A time the phone sends as an instant in a zone only the object defines SHALL be written as the local time that zone's clock shows at that instant, a time just past a gap included.

#### Scenario: Spring forward in an object's own zone
- GIVEN an object defining Paris rules under a `TZID` no database knows
- WHEN the phone moves an event to 01:30 UTC on 29 March 2026
- THEN its `DTSTART` reads 03:30 in that zone

## MODIFIED Requirements

None.

## REMOVED Requirements

None.
