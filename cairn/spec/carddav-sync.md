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

### Requirement: A Graph book reads its changed contacts 20 to a batch
A Graph addressbook pass SHALL take a contact's body from the complete round's listing when it carries one, and SHALL read every other body it needs 20 requests to a `$batch`, each request the one a single contact read sends, a request the batch could not serve being sent again on its own. A contact Graph no longer holds when it is read SHALL be left out, and the rest of the book stored. Each read SHALL log how many contacts it was asked for, how many the listing gave, how many it read in how many batches and its network time.

#### Scenario: Hundreds of contacts edited in Outlook
- GIVEN a Graph book of which 120 contacts were edited elsewhere since the last pass
- WHEN the book syncs
- THEN the delta names them and their bodies are read in seven `$batch` calls (the pass asks 64 at a time: four and three), rather than 120 requests

#### Scenario: A contact deleted on Outlook since the delta
- GIVEN a contact the delta names and Outlook deletes before it is read
- WHEN the pass reads it
- THEN it is left out and the rest of the book is stored
