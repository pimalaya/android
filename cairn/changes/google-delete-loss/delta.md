---
cairn: delta
change: google-delete-loss
---

Capability: offline-store.

## ADDED Requirements

### Requirement: A write records the revision the server holds
A pushed create or edit SHALL record the revision the server holds the item at: the ETag the write answered, read as the string it is (quotes of its own included), or, when the write answered none, the one a read of that member answers. A read that fails SHALL leave the revision unknown and SHALL NOT fail the write.

#### Scenario: A server answering no ETag
- GIVEN a CalDAV server that answers a PUT with no ETag, as Google's does
- WHEN an entry created on the phone is pushed, then deleted on the phone
- THEN the pass after the delete pushes it, rather than reading the listing as a change made elsewhere and pulling the entry back

#### Scenario: An ETag carrying quotes
- GIVEN a Google Calendar event edited on the phone, whose new ETag is `"3583433504310878"`
- WHEN the edit is pushed
- THEN the binding's revision is `"3583433504310878"`, the one the next listing names, and a delete staged after it is pushed

### Requirement: A bound member keeps its identity
A contacts or calendar driver SHALL name a member its source already binds by the identity the store files it under, whatever identity its body states now, so the store never retires an item for its body alone.

#### Scenario: Google rewrites a card's UID
- GIVEN a card created on the phone, which Google stored with its UID rewritten to its own id
- WHEN the card is edited elsewhere while the phone stages an edit of its own
- THEN the two edits merge onto the same card and the merge is pushed, rather than the card being replaced and the phone's edit dropped

## MODIFIED Requirements

### Requirement: A placement no source binds is offered under its provisional handle
The store SHALL hand out `U+0001` followed by the link id as the handle of a placement the source does not bind (SYNC §2), and SHALL resolve that handle back to the identity it spells. A push SHALL name the resource by that identity, never by the provisional handle, and the binding SHALL take the name the server filed the create by, which a server may choose itself.

#### Scenario: A card staged for a book the phone has never seen
- GIVEN an item with no binding for the phone spoke
- WHEN that spoke loads the collection
- THEN the placement carries the provisional handle, and a row read under it resolves to the item

#### Scenario: The create is pushed
- GIVEN a staged create under its provisional handle
- WHEN the push offers it to the server
- THEN the resource is named after the card, and the binding takes whatever handle the push assigns

#### Scenario: The server files the create under a name of its own
- GIVEN a card created on the phone, pushed to Google's CardDAV, which files it under its own name and says so in `Location`
- WHEN the card is deleted on the phone
- THEN the next pass deletes the resource Google named, and the card is not listed back

## REMOVED Requirements
