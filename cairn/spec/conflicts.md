---
cairn: spec
capability: conflicts
status: current
---

# Conflicts

A conflict is one source diverging from its own remote: both sides edited the same item since the base they last agreed on. It is recorded on that source's binding, which is what makes the two-spoke model work, a server divergence and a phone divergence over one item being two independent questions. Two sources changing one item differently is the item's own conflict, recorded on the item (`items.conflict_object`) and offered through its server binding.

Most divergences are not the user's to settle. A sync pass triages every conflicted row through the three-way merge first: lists merge as sets and a change only one side made flows in, so what survives to the resolution form is a field both sides edited differently. A clean merge is staged as the resolution and pushed by the pass that follows.

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

### Requirement: An item conflict is settled by a person's choice
An item two sources diverged on SHALL be offered, once, as the conflicts of its collection's server binding are: merged three ways by the sync pass first, the item's body the local side, its diverging body the remote one, and the body the disagreeing source last agreed with the item (its `shared_object`, else its base) the ancestor, none when no binding names one; a clean merge staged as the resolution, a field both sides set apart left to the resolution form, the contact or the entry carrying the conflicted mark meanwhile. A person's resolution, staged on that binding and naming the diverging body it was shown, SHALL settle the item whatever body it keeps (SYNC §7): the item takes the body, its diverging one released and kept nowhere, and each source owes the body where its base differs. A binding SHALL be conflicted by its own divergence alone, the remote revision it records, never by the status its item's conflict projects.

#### Scenario: Keeping the phone's card
- GIVEN a card the phone and the server changed, the phone's kept and the server's recorded
- WHEN the form is saved keeping every value the phone set
- THEN the card is no longer conflicted, the phone owes nothing and the server is pushed the phone's card

#### Scenario: A server conflict resolved after the phone moved the card
- GIVEN a card whose server conflict waits for the form, and the phone changing the card meanwhile
- WHEN the form is saved over the card the phone moved
- THEN the item's conflict this records merges with nothing to ask and is settled by the same save, and the card does not read as conflicted

### Requirement: A resolution names the conflicts it settles
A conflict, a binding's or its item's, SHALL be settled only by a resolution naming its diverging body (SYNC §7, STORAGE §15.3): the resolution form and the sync passes' three-way merge state the diverging body they read (the remote side they offered, `resolves`), and an edit SHALL be refused, staging nothing, when it meets a conflict whose diverging body it does not name, states a resolution and meets none, or meets a conflict whose diverging body nobody has read. Every other edit of the app (a contact's or an entry's page, a task's, an import, a merge of duplicates) states none, and a conflict refuses it with a message naming the diverging body. A store load SHALL state the item conflict of each placement it hands the engine, with the item's diverging body, so a conflict is never read as one nobody read. A merge a pass cannot stage, the item conflicted over another body than the one it read, stays for the form rather than failing the pass. The app queues no edit (its queue carries `submit` alone, its drain skipping every other action), so no queued update parks.

#### Scenario: Saving a conflicted contact from its ordinary page
- GIVEN a contact the phone and the server changed, its server version recorded and not yet settled
- WHEN its ordinary page is saved, or its conflict form is saved after a sync replaced the server version it showed
- THEN the save fails naming the server version, nothing is staged, and the contact stays conflicted

#### Scenario: Saving the conflict form
- GIVEN the same contact open in its conflict form
- WHEN the form is saved, whatever values it keeps
- THEN the contact is settled as before, and the version the form showed is released

### Requirement: A body a policy did not adopt is kept
An edit settling a conflict by a rule (an overrule) SHALL keep each diverging body it does not adopt as a retained item (`dup:<link>#<handle>`, SYNC §7, §9), and SHALL be refused while the conflict holds a body nobody has read.

#### Scenario: Keep the phone's
- GIVEN a calendar entry in conflict whose server body was never opened
- WHEN the conflict is settled by a rule keeping the phone's body
- THEN the write is refused until the server's body is read, and once read it is kept as a retained item

### Requirement: A removal names the conflicts it settles
A removal SHALL be staged as the engine's `Remove` stating the body it was decided over where the page read one (`if_match`), refused, staging nothing, once another write replaced it, and, from the conflict form alone, the diverging bodies the form showed (`resolves`). A removal meeting a conflict, the item's or its binding's, whose diverging body it does not name SHALL be refused, staging nothing (SYNC §7): a delete from a list or an ordinary page settles no conflict. A tombstone SHALL clear an item's conflict only when its removal named it (the engine's `Settle`).

#### Scenario: Deleting from the conflict form
- GIVEN a calendar entry in conflict, open in its conflict form
- WHEN it is deleted there
- THEN the removal names the server version the form showed, the conflict is settled and the delete is pushed

#### Scenario: Deleting a conflicted contact from the list
- GIVEN a contact the phone and the server changed, its conflict not settled
- WHEN it is deleted from the contacts list
- THEN the delete is refused, nothing is staged, and the contact stays conflicted
