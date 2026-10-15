---
cairn: tasks
change: jni-boundary
---

# Tasks

Decisions taken 2026-10-15 (proposal.md). Gate per step: `nix develop --command gradle -p android :app:assembleDebug :app:testDebugUnitTest --rerun :client:testDebugUnitTest --rerun --console=plain`, `cargo test`, `cargo clippy --all-targets`; wire values captured before a step and compared after.

- [x] Review: decisions D1, D2, D3, D5, D7
- [x] 1. Dead natives: cardProps, cardSetProp, cardSetPropParts, cardPropLabels, cardSource, discover, listAccountCards; rust/src/contact/props.rs. 7 natives and exports gone, props.rs (2 tests) and what they alone reached: Client::discover, Contacts::list_account_cards, Gpeople::list_cards (1 test), Jmap::list_cards
- [x] 2. `writes_overrides`, `writes_exdates`, `writes_events` and their EventView branches. Gone from Backend, accountInfo, BackendInfo and AccountClient; EventView asks LocalBook.is (2 sites), scope() loses its always-true flag (both callers behind writes()); 1 JVM test gone
- [x] 3. One account argument: contact, calendar, mail session open, token-only reads (one sub-step each). 22 natives take one `account` JSON (contact 13, calendar 6, openMailSession, readGraphCards, readGoogleCards), decoded once by ffi::parse_account into account::AccountJson (secret a SecretString); Account::new and 22 positional decodes gone, MailSession holds the AccountJson; Java `Account.address` and `toJson()`, `EventTarget`/`RemoteAccount` lose the address; no JNI symbol added or removed
- [x] 4. Backend facts on Account; the 38 call sites; AccountClient's memo; spinners from `cardTypeOrder`. accountInfo answers rs AccountInfo (15 facts: 11 flags, 3 enums, host), read once per Account (Account.info, built once per AccountEntry); Backend.java and the 7 AccountClient predicates plus memo gone, 14 files read facts (AccountTraitsTest pins every site per backend); spinners built from cardTypeOrder by TypeLabels, the 5 string-arrays gone (TypeTablesTest became TypeLabelsTest); submitMessage reads submitUrl from the session account (3 args), Account.password renamed secret; no JNI symbol added or removed
- [x] 5. `retainedAt` as millis; server description from Rust. Server description landed in step 4; DeletedItems.Row.retainedAt is epoch millis (0 while bound), parsed once where the row is read (DeletedItems.instantOf beside stamp), the page's 19-char cut gone; rendered dates equal on an 11-stamp corpus, 1 JVM test added
- [ ] 6. PhoneEvent records, CalendarMapping typed (corpus capture like MappingCorpusTest)
- [x] 7. Uniform text replies, `Replies` sniffing gone. writeEvent, removeEvent, resolveEvent, newEvent, applyEvent answer `{ical}` (null when a removal leaves nothing), updateEvent `{etag}`, errors the standard error object; CalendarClient reads EventIcal/EventEtag through Replies.object, 5 brace sniffs and Replies.etag gone; 29 success and error results equal before/after on the real .so, 1 JVM test added; no JNI symbol added or removed
- [ ] 8. `bound` / `pendingCreates` / `held` yields; MailEngine naming in Rust
- [ ] Docs: docs/jni-boundary.md and docs/jni-typing-plan.md marked landed; package-info.java
- [ ] Fold (no requirement moves: a delta only if a step changes behaviour), log, archive
