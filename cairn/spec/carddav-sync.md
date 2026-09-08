---
cairn: spec
capability: carddav-sync
status: current
---

# CardDAV sync

An addressbook is reconciled by enumerating its members incrementally: the RFC 6578 `sync-collection` REPORT with the cursor the previous round returned, or a complete round with no cursor. A round reports whether it was complete, which is what tells the engine a member the listing does not carry was removed rather than merely unchanged.

The other backends answer the same shape through their own mechanisms: a Graph contacts delta round, a JMAP `ContactCard/changes` round, a People connections sync.

### Requirement: A CardDAV collection is enumerated whatever the server implements
A collection sync SHALL run the RFC 6578 `sync-collection` REPORT, and SHALL enumerate with a `PROPFIND` at Depth 1 when the server answers that it implements no such report. A round carrying a cursor that meets the same refusal SHALL be re-run as an initial round rather than answered from the fallback's tokenless snapshot.

#### Scenario: A server with no sync-collection
- GIVEN a CardDAV server refusing the report
- WHEN an initial sync runs
- THEN the collection is enumerated with `PROPFIND` and the round reports itself complete
