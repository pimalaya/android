---
cairn: delta
change: graph-contact-reads
---

## ADDED Requirements

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

## MODIFIED Requirements

## REMOVED Requirements
