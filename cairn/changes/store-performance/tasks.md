---
cairn: tasks
change: store-performance
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, tests to android/app/src/test/java/org/pimalaya/.

## App

- [x] 1. `PimdirStorage.scopeClause`: scope lists bound once through `json_each`, `SCOPE_MAX` gone; a handle scope driven by `bindings_by_handle`, an unbound row reached by its provisional name; `bound` one statement
- [x] 3. `MailStore.changed` (`RETURNING` dropped, `changes()` counted) for `collectBefore` and `release`; `DeletedItems.purgeBefore` counts the same way and leaves the pins to the collector's recount
- [x] 4. `MailStore.below`: the keyset bound on `sort_key` through a flattened `items` common table expression, in `page`, `datesBelow`, `bodyRows` (`bodyRows(null)` linear)
- [x] 6. `MailStore.unjoined`: the counts with the attachment chip off read an empty `mail_summary`
- [x] 7. `MailList.seek` and `MailStore.pageBelow`: a fling reads from the start of its day, from the layout's day counts
- [x] 8. `Native.mailStep(.., OfflineDriver store)`; `Client::held` upcalls `serve({op: "held", handles})` per listed page in Gmail (`GmailSource::held`), JMAP (`JmapStepReads::held`) and Graph (`GraphMail::held`); `held` leaves the request; `MailEngine.held`/`stepRead(.., holding, ..)`; `PimdirStorage.heldOf`, `bindsAny` (land's emptiness), `steppedIn` per page; `handlesOf` gone
- [x] 9. `MailReader.fetch` through `MailEngine.sources(session, ..)` into a file, `Native.describeMessage` (hash, summary, attachments in one native read), `PimdirBlobs.adopt` (moved, not copied), `PimdirItems.putObject(.., hash, file)`, `Native.parseMessageFile`; `mail::write_part` writes the part from the parser's buffer; `fetchMessageSource` and the per-backend whole reads gone; a lone Gmail fetch on a reader's session stays urgent
- [x] Body landing: `MailEngine.stored` describes the file natively and adopts it (no `readAllBytes`, no Java hash, no copy, no `pimdirDerive`/`parseMessage` JNI copies)
- [x] 10. `CalendarList.reload` off the main thread (last reload wins), expansions kept per week and object
- [x] Tests: `StorePerformanceTest` (a 1000-handle scope and a provisional name; collect, release and purge of 120k rows past a cursor window; the keyset plan; keyset walk, body plan and flings at three offsets equal one read; counts read the summary for the chip alone and plan without it; the held query through serve; an open streamed into the store, its hash the store's); `MailStepTest`/`GraphStepTest` fakes ask `held`; Rust `a_message_is_described_by_one_read`, the step fakes answer `held`
- [x] Build: assembleDebug, app and client unit tests, cargo fmt/clippy/test, cairn verify, R8 seeds
- [x] Fold, log, CHANGELOG

## Library proposals (io-pimdir / pimdir, not edited here)

- **L1 keyset bound (audit 4)**: every newest-first keyset statement states its bound on the leading key, `AND i.sort_key <= coalesce(:after_key, x'')` in `list_mail_page_filtered`, `search_mail`, `list_mail_page_desc`, `list_items_page_desc`; every ascending one `AND i.sort_key >= coalesce(:after_key, '')` (`list_contacts_page_asc`, `list_events_page_asc`, `list_items_page_asc`, `list_tasks_page_asc`, `list_journals_page_asc`, `list_files_page_asc`). invariants.sh asserts `items_by_sort_global (sort_key>? AND sort_key<?)` with a cursor bound. The app's `MailStore.below` then goes.
- **L2 counts (audit 6)**: `count_mail`, `count_mail_by_day`, `count_unread`, `sum_mail` test the chip without the join, `AND (:attachment IS NULL OR EXISTS (SELECT 1 FROM mail_summary s WHERE s.collection = i.collection AND s.link_id = i.link_id AND s.attachment = :attachment))`, and the seen test without `json_each`, `instr(i.flags, '"\\Seen"') > 0`, flags written through `json()` so the spelling is fixed (or a `seen` column the writer maintains). The app's `MailStore.unjoined` then goes.
- **L3 delta loads (audit 2)**: `sync.rs` loads a delta page and a step landing by the handles it lists and the ones it finds vanished, plus the pending creates (unbound or locally changed items, an indexable predicate), never `PimdirLoadScope::All`; a round closes on the unstamped bindings it already asks for. At 1M a Gmail step's landing is 22.8 s of 28.4 s in these loads (93 MB of JSON).
- **L4 search (audit 5)**: an FTS5 external-content table over `mail_summary(subject, sender, sender_name)`, trigram tokenizer (LIKE's substring semantics), kept by triggers, `search_mail` and a `count_search_mail` through `MATCH` (SEARCH spec).
- **L5 owner writes**: `collect_before`, `release_before`, `purge_retained_before` document that a caller runs them for `changes()`, never steps their `RETURNING` through a cursor; `purge_retained_before` gets a pin release that needs no returned rows (or the spec says to recount).
- **L6 scoped load**: a canonical `load_placements` with `:handles`/`:links` as JSON arrays (`json_each`, `bindings_by_handle`), so the app's load SQL moves into the crate.
