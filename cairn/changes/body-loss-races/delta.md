---
cairn: delta
change: body-loss-races
---

Folds into `spec/offline-store.md` and `spec/mail.md`.

## ADDED Requirements

### Requirement: A body filed ahead of its write keeps its file
A body moved into the blob store before the write naming it (a downloaded message) SHALL be pinned from before it is moved in until that write has returned, and a collector SHALL NOT unlink a pinned body, leaving it for a later run. A collector SHALL read an object gone and unlink its file inside a transaction of its own, so no writer is between filing a body and committing its row. A write recording a body already filed SHALL be refused when the file is missing, the batch rolled back and the body still owed.

#### Scenario: A download racing the last reference to the same bytes
- GIVEN a message downloading a body whose bytes another message holds
- WHEN the other message drops them, its collector running before the download's write lands
- THEN the file stays, and the downloaded message holds its body once the write lands

#### Scenario: A body whose file is missing
- GIVEN a write recording a downloaded body whose file is not in the blob store
- WHEN it is applied
- THEN it is refused, and the message stays at meta, its body owed

### Requirement: A push converges on a message gone
A write on one message that the server answers 404 (a message deleted for good, purged from the trash or the spam, or a Gmail draft saved under a new id since the listing) SHALL be taken as converged: a removal, a move and a marker accepted, a move's target create withdrawn, and a copy of it refused. The bridge SHALL answer such a write as done on Gmail and Graph, and a JMAP `notFound` likewise, a copy keeping its 404. A push SHALL NOT fail a pass with a 404, so only a mailbox's own reads are taken as the server refusing it.

#### Scenario: A message purged between the listing and the push
- GIVEN a deleted message staged for removal, purged from the trash on the server after the pass listed it
- WHEN the pass pushes the removal
- THEN it is accepted, and the page the pass listed lands

#### Scenario: A copy of a message gone
- GIVEN a copy staged of a message the server no longer holds
- WHEN it is pushed
- THEN it is refused, and its row says so

## MODIFIED Requirements

### Requirement: A refresh keeps the bodies it does not restate
A write carrying no body SHALL keep the object the item already holds, and an item's level SHALL follow what it holds: meta with no body, full with one. A sync write of a message SHALL NOT take away a body the row holds: an upsert carrying no object at a lower level than the row's, neither a staged removal nor a conflict, SHALL keep the row's body and level, and the binding's base when it was that body, whatever the sync loaded before the body was filed (a reader opening the message, a download landing it). Only an explicit release (a narrowed window, a cross-linked body, a purge) SHALL free a message's body.

#### Scenario: A sync after a read
- GIVEN a message whose body was stored by opening it
- WHEN the mailbox is refreshed
- THEN the stored body survives the refresh

#### Scenario: A read during a sync
- GIVEN a pass that loaded a new message at meta and is pushing over the network
- WHEN the message is opened, its body stored, and the pass then writes it with new markers
- THEN the message keeps its body at full, the markers written

#### Scenario: An envelope with no body
- GIVEN a message the sync has just stored
- WHEN its row is read
- THEN it stands at meta

## REMOVED Requirements
