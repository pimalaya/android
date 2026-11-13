---
cairn: log
change: a-graph-message-is-read-raw
date: 2026-11-13
---

# A Graph message is read raw

pimdir audit line D2, its Android half on Graph (pimdir cairn/changes/audit-2026-11-12/tasks.md): a Graph mail member is named off its raw header fields through io-pimdir's `derive_meta`, as an IMAP and a Gmail one already were, wherever Graph answers them, and from the values Graph decodes only where it answers none (pimdir STORAGE Annex A.1 as corrected on 2026-11-13). It mirrors neverest 55358e3 (its cairn/log/2026-11-13-a-graph-message-is-read-raw.md).

## What landed

- **The pin** (rust/Cargo.toml, rust/Cargo.lock): io-msgraph by git at 92c28d5 in `[patch.crates-io]`, the dependency line staying `0.7`. `cargo update -p io-msgraph` moved that one package, offline, the commit being in cargo's git cache already; the lock reads 0.7.1 for it now, 01105a5 having held the whole of 0.7.1's code (the To Do surface, 67ab457's alarm restore) under the 0.7.0 version.
- **The select** (rust/src/mail/msgraph.rs, `MESSAGE_SELECT`): it names `internetMessageHeaders`. It is the `$select` of every read naming a mail member: a band's `/messages` page (`band_messages`, under a first chunk, a step's band, a widening and the fill), and a read by id (`message_url`), which the `$batch` of 20 naming a delta's unbound members (`name_messages`, `read_messages`) and the read alone of a GET the batch could not serve both use. A delta (`DELTA_SELECT`), a step's listing (`STEP_SELECT`), the floor's listing and the folder enumeration name no member and are unchanged; so is the copy check by `internetMessageId`, which names nothing.
- **The reading** (rust/src/mail/msgraph.rs, `graph_summary`): a row listing header fields has the nine a summary reads (`SUMMARY_FIELDS`: `Message-ID`, `In-Reply-To`, `Subject`, `From`, `To`, `Cc`, `Bcc`, `Date`, `Content-Type`) laid out as a header block, each from its first entry of that name, compared without regard to case, and handed to `derive_meta` with the MAPI size and Graph's `hasAttachments`. So the link id, the subject, the date, the sender, `in_reply_to` and the address rows are the format's reading; the size and the markers are Graph's as before, and the mark is `hasAttachments`, the top-level `Content-Type`'s only on a row stating none. A row listing none keeps the decoded reading in the same function, its `internetMessageId` now read by io-pimdir's `message_id`: the app's own parser (trim, then one `<` and one `>` stripped) is gone.
- **The scope** of a band page and of a named member is still read off the summary's date (`named`), now the `Date` field's reading where the row lists one, while the band's `$filter` and its ceiling read `sentDateTime`.

## What io-msgraph moved, and what each needed

- 7480799, ids percent-encoded as one path segment, an empty, `.` or `..` id refused before any request: no edit. Every id the app hands a coroutine is one Graph minted or a well-known folder name; Graph's ids are URL-safe base64 in practice, whose letters, digits, `-`, `_` and `=` are pchars passed unchanged, and every request-line assertion of the Graph tests (mail, calendar, contacts, To Do) passes unchanged. The URLs the app builds itself still splice the id raw (`message_url`, `holds_copy`, `permanent_delete_url`, the `$value` and size batches): not changed here.
- 7480799, `MsgraphMessage::categories` a `MsgraphField`: no edit. The app reads and writes no message category; the flag patch is built from `MsgraphMessage::default()`, the field unset and left out of the body as the empty list was before.
- 7480799, `MsgraphMessagesListResponse::count`: two struct literals of the Graph mail tests (the fake folder's `/messages` page and the step's) take `..Default::default()`. No production line.
- 9a203bb, `MsgraphMessageGet::new` and `MsgraphClientStd::message_get` take a select and an expand: the app calls neither (its read by id goes over `MsgraphSend::get` with `message_url`, shared with the batch). No edit.
- 9a203bb and 92c28d5, `MsgraphMessage::internet_message_headers`, a `null` read as none: no literal broke, every one the app writes ending in `..Default::default()`.

## What a row now carries

Every header field of its message as a name and a value, in Graph's order: the nine read and all the others (the `Received` trace, the DKIM and ARC signatures, the authentication results, the server's own `X-` fields), `$select` naming the collection whole or not at all. A received message's header block commonly runs to a few kilobytes, so a band page of 1,000 rows may grow from a few hundred kilobytes to several megabytes, held whole before it is parsed; a batch of 20 grows by as much a row.

The page sizes are unchanged: 1,000 a band page (`GRAPH_PAGE`, `$top`), 20 a batch. No row and no page was measured: no live suite was run. What bounds a page is io-http's 256 MiB a body and the 30 s the Java transport waits on one socket read (android/client Transport.java `READ_TIMEOUT_MS`), not the whole answer. A phone on a slow link pays the larger pages on every first chunk, step band, widening and fill.

## Tests

New, in rust/src/mail/msgraph/tests.rs, over ten of pimdir's mail fixtures read from io-pimdir's spec (`PIMALAYA_IO_PIMDIR_SPEC`, the checkout clean at v0.12.0): mail-id-comment, mail-bracketless-id, mail-repeated-fields, mail-folded-subject, mail-military-zone and the five mail-charset ones, each against what vectors/summaries.json states for it (link id, sort key, `message_id`, `in_reply_to`, subject, sender, sender name, date, address rows):

- `a_row_listing_its_header_fields_is_named_as_the_vectors_state`: each fixture's fields served as a row's `internetMessageHeaders`, in document order, repeated names kept, a folded value with its line breaks, beside a `subject`, `sentDateTime`, `from` and `internetMessageId` stating another message, Graph stating no mark (so the vector's `meta_attachment` is checked too).
- `a_row_listing_its_header_fields_keeps_the_size_and_the_mark_graph_states`: `In-Reply-To` read, the MAPI size kept, `hasAttachments` the mark when stated and `Content-Type`'s when not.
- `a_row_listing_no_header_field_is_keyed_by_its_internet_message_id`: the seven fixtures stating a `Message-ID`, the field's value as `internetMessageId`, the member left out, `[]` and `null`; the subject, sender and date stay Graph's.
- `every_read_naming_a_member_selects_its_header_fields`: over a scripted Graph answering the header fields only to a `$select` naming them, a band round's page, a `$batch` of two reads by id and the read alone of the one the batch answered 503 each name the member as mail-id-comment's vector states.

Each was seen failing on the old code, in one run of `cargo test --offline --lib mail::msgraph::tests` with the old `MESSAGE_SELECT` and the old `graph_summary` (no header branch, the app's own parser) put back by hand: the first failed on all ten fixtures (mail-id-comment keyed `decoded@example.net` where the vector states `comment-1@example.org`), the second on `in_reply_to` (`[]`), the third on mail-id-comment alone (keyed `(via <relay@example.net>) <comment-1@example.org> (trailing)`), the fourth on all three reads (keyed `decoded@example.net`). A second run with the new `graph_summary` and the old `MESSAGE_SELECT` failed the fourth alone, on every read, the fake answering no header field. The new file was restored from a copy taken in the scratchpad, never through git.

The tests of the decoded reading pass unchanged (`the_summary_reads_the_select`, `the_date_is_the_sent_date_not_the_reception`, `the_size_is_the_expanded_mapi_size`), as does every listing test over the fake Graph folder, whose rows list no header field.

## Evidence

Each as `nix develop --command <cmd>` from the repository root, `PIMALAYA_IO_PIMDIR_SPEC` at the io-pimdir v0.12.0 checkout's spec/, on 2026-11-13:

- `cargo fmt --check` in rust/: clean.
- `cargo clippy --offline --all-targets -- -D warnings` in rust/: clean.
- `cargo test --offline` in rust/: 662 passed, 1 ignored (658 before, four added).
- `gradle -p android :app:assembleDebug :app:testDebugUnitTest :client:testDebugUnitTest --offline --console=plain`: build successful; app 1,021 tests (5 skipped, the sync-vector suite among them), client 14, none failing.

No live suite was run, nothing was installed on a device, and nothing connected to an account.

## Not done

- **A device check** against a Microsoft 365 account: that a band page and a batch answer `internetMessageHeaders` as the probe of 2026-11-13 saw, how long a first chunk and a page of 1,000 now take on a phone, and how a draft is answered.
- **A draft**: what Graph answers for it is not known (the probe found Drafts empty). An absent member, `[]` or `null` reads as a row listing none.
- **A row listing header fields but none a summary reads** is named off them, so with no subject, date or sender, nothing of what Graph decodes: Annex A.1 reads it so, and whether Graph answers such a row is not known.
- **The decoded reading still differs from neverest's** on a row listing no header field: the app runs `decode` over Graph's `subject` and trims it, drops an address with no address and trims a name; neverest takes all as Graph states them. Such a row's `alt:` key (no `Message-ID`) can differ between the two. Left as it was, by the scope of this change.
- **The scope** under Graph: the band's filter and ceiling read `sentDateTime`, the member's scope its `Date` field's reading, so a message whose `Date` Graph reads otherwise than Annex A.1 is listed where both agree, else left to the delta's pass, which lists it by id and names it.
- **What Graph serves in a value** (a folded field folded, an encoded word encoded) is not verified against Graph; `derive_meta` reads both.
- A message an earlier build keyed otherwise keeps its key.

## Capabilities moved

- mail (cairn/spec/mail.md): added "A Graph message is named from its raw header fields"; modified "A message's date is its Date header" and "A listing's text is decoded before it is stored" (what each says of Graph).
