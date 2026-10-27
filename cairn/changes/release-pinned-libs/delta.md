---
cairn: delta
change: release-pinned-libs
---

Folds into spec/network-trust.md.

## ADDED Requirements

### Requirement: A server's answer is read up to a bound
An HTTP answer the bridge reads whole (a JSON reply, a multistatus, a card or an event) SHALL be refused once its body passes 64 MiB, the call failing as a server error and nothing past the bound held. A message body SHALL be read under a bound of its own, never the default of the HTTP library.

#### Scenario: A server answering without end
- GIVEN a CardDAV server answering a REPORT with a body of 1 GiB
- WHEN the book syncs
- THEN the read stops at 64 MiB and the pass fails on that book, the app's memory never holding the rest
