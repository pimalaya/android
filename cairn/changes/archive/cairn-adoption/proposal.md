---
cairn: change
id: cairn-adoption
status: landed
created: 2026-09-02
---

# Adopt Cairn

## Why

The repository keeps its history in git and its intentions in docs/, and neither says what the app does *today*. docs/pimalaya-android-plan.md is the plan of record and reads as one: it is prospective, dated, and full of decisions that have since been taken or reversed, so answering "how does a conflict resolve right now" means replaying nine progress sections. Every other Pimalaya repository that carries real behaviour has moved to Cairn for exactly that reason.

## What

Create the Cairn root at cairn/ with spec/, changes/ and log/, the activation stanza in AGENTS.md with CLAUDE.md pointing at it, a cairn.toml pinning the spec version, and the optional bash verifier vendored beside them.

The spec is seeded by accretion rather than backfilled: it starts with the capabilities the changes landing beside this one actually move, and grows one delta at a time. docs/ keeps the plans, which are prospective where a spec is current.
