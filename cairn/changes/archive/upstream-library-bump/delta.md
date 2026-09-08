---
cairn: delta
change: upstream-library-bump
---

## ADDED Requirements

### Requirement: The store's shape follows the canonical schema
The store SHALL reconcile its own shape against the canonical pimdir DDL on open, adding every column the schema declares that it lacks and dropping every column it holds that the schema no longer declares. It SHALL NOT carry a transcribed list of the columns a draft folded in or out.

#### Scenario: A column the draft folded in
- GIVEN a store created before `bindings.conflict_object` was declared
- WHEN the app opens it
- THEN the column is added and the store opens

#### Scenario: A column the draft folded out
- GIVEN a store carrying `bindings.ambiguous_handles`, which the schema no longer declares
- WHEN the app opens it
- THEN the column is dropped, or kept with a log where the platform's SQLite cannot drop one

#### Scenario: A shape the parse could not read
- GIVEN a table the canonical DDL yielded no column for
- WHEN the app reconciles
- THEN it changes nothing about that table, rather than reading the empty answer as "every column is stale"

### Requirement: A conflict carries its diverging body
A binding marked conflicted SHALL record the remote body it diverged from, under `bindings.conflict_object`, beside the revision that names it. The engine supplies it: the app SHALL NOT read the remote a second time to capture it.

#### Scenario: The body has not landed yet
- GIVEN a binding marked conflicted holding no `conflict_object`
- WHEN the hydrate pass runs
- THEN that handle is among the ones it upgrades
- AND the conflict is not offered to the resolution form until the body lands

#### Scenario: The body has landed
- GIVEN a conflicted binding holding its diverging body
- WHEN the resolution form opens
- THEN it reads the local body, the base and the remote from the store alone, with no credentials and no network

### Requirement: A resolution rebases onto the whole observed state
Resolving a conflict SHALL adopt both halves of the state it was merged against: the remote revision observed at conflict time becomes the base revision, and the diverging body recorded beside it becomes the base body.

#### Scenario: Keeping the local body
- GIVEN a conflict resolved by keeping the local edit
- WHEN the resolution is staged
- THEN the base holds the remote revision and the remote body
- AND the push is an update conditioned on that revision

## MODIFIED Requirements

### Requirement: One identity under two handles
A write resolving an existing `(collection, link_id, source)` binding to a different handle SHALL be refused, unless the same batch supersedes the handle it holds. The store SHALL NOT record the incoming handle in the bound one's place, and SHALL NOT freeze the item.

#### Scenario: A source reports one identity twice
- GIVEN a binding bound to `v1.vcf`
- WHEN an upsert of the same link id arrives under `v2.vcf` with nothing superseding `v1.vcf`
- THEN the write is refused and the binding keeps `v1.vcf`

#### Scenario: A handle-space rebuild
- GIVEN a batch dropping `v1.vcf` as superseded
- WHEN the same batch upserts the link id under `v2.vcf`
- THEN the binding is replaced and the item stays

### Requirement: A CardDAV collection is enumerated whatever the server implements
A collection sync SHALL run the RFC 6578 `sync-collection` REPORT, and SHALL enumerate with a `PROPFIND` at Depth 1 when the server answers that it implements no such report. A round carrying a cursor that meets the same refusal SHALL be re-run as an initial round rather than answered from the fallback's tokenless snapshot.

#### Scenario: A server with no sync-collection
- GIVEN a CardDAV server refusing the report
- WHEN an initial sync runs
- THEN the collection is enumerated with `PROPFIND` and the round reports itself complete
