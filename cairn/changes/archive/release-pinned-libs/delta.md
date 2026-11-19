---
cairn: delta
change: release-pinned-libs
---

Folds into spec/network-trust.md.

## ADDED Requirements

### Requirement: A server's answer is read up to a bound
An HTTP answer the bridge reads whole (a JSON reply, a multistatus, a card, an event or a message) SHALL be refused once its body passes the HTTP library's bound, 256 MiB (io-http `HTTP_DEFAULT_MAX_BODY_LEN`), the call failing and nothing past the bound held.

#### Scenario: A server answering without end
- GIVEN a CardDAV server answering a REPORT with a body of 1 GiB
- WHEN the book syncs
- THEN the read stops at 256 MiB and the pass fails on that book, the app's memory never holding the rest

## MODIFIED Requirements

## REMOVED Requirements
