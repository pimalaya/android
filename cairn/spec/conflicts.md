---
cairn: spec
capability: conflicts
status: current
---

# Conflicts

A conflict is one source diverging from its own remote: both sides edited the same item since the base they last agreed on. It is recorded on that source's binding, which is what makes the two-spoke model work, a server divergence and a phone divergence over one item being two independent questions.

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
