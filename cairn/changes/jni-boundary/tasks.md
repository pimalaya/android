---
cairn: tasks
change: jni-boundary
---

# Tasks

Waits on the user's review of proposal.md (decisions D1, D2, D3, D5, D7). Gate per step: `nix develop --command gradle -p android :app:assembleDebug :app:testDebugUnitTest --rerun :client:testDebugUnitTest --rerun --console=plain`, `cargo test`, `cargo clippy --all-targets`; wire values captured before a step and compared after.

- [ ] Review: decisions D1, D2, D3, D5, D7
- [ ] 1. Dead natives: cardProps, cardSetProp, cardSetPropParts, cardPropLabels, cardSource, discover, listAccountCards; rust/src/contact/props.rs
- [ ] 2. `writes_overrides`, `writes_exdates`, `writes_events` and their EventView branches
- [ ] 3. One account argument: contact, calendar, mail session open, token-only reads (one sub-step each)
- [ ] 4. Backend facts on Account; the 38 call sites; AccountClient's memo; spinners from `cardTypeOrder`
- [ ] 5. `retainedAt` as millis; server description from Rust
- [ ] 6. PhoneEvent records, CalendarMapping typed (corpus capture like MappingCorpusTest)
- [ ] 7. Uniform text replies, `Replies` sniffing gone
- [ ] 8. `bound` / `pendingCreates` / `held` yields; MailEngine naming in Rust
- [ ] Docs: docs/jni-boundary.md and docs/jni-typing-plan.md marked landed; package-info.java
- [ ] Fold (no requirement moves: a delta only if a step changes behaviour), log, archive
