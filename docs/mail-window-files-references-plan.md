# Offline mail window, files and references: plan

Agreed in the device-test session of 2026-10-10 (Google, Microsoft, Posteo and Fastmail onboardings). Three parts, in order:

- **Part A**: small pending tasks, closing what this session started.
- **Part B**: the mail download window: the list shows what is on the phone, and a footer button moves that edge back a month at a time.
- **Part C**: the file domain and references between items, in pimdir first.

Code references were inventoried on 2026-10-10 against the uncommitted tree; Java paths are relative to android/app/src/main/java/org/pimalaya/.

## Part A. Small pending tasks

### A1. Device test of this session's changes (the user, later)

Landed uncommitted, build and unit tests green:

- Onboarding: the bare steps' back button takes the status-bar inset (`domain_bar`, `signin_bar`, `oauth_bar`, `books_bar`, `result_bar` in `applyBarInsets`); the domain and result footers pad their top; the standard setup's options are checkboxes (`OnboardingFlow.tickRow`); the standard setup opens with no domain ticked, ticks kept when stepping back.
- Sync strip: one bar over the whole pass, drawer sync included (`MainActivity.planSync`, sections per domain and account weighted by stored collections, `SyncSteps.across`); collections told up front; download three fifths and phone the rest within a collection, mail's download all of it; never moves back; the line names the domain only.
- No report toasts after a pass; errors keep their dialog; a pull turned down by a background run says nothing.
- The background mail fill shows a pulsing download glyph beside the mail count (`ListHeader.filling`), still when Android removes animations, long press naming it.

Check on device: a drawer sync over two accounts (one bar, account line moving on); a first mail sync then the glyph pulsing while the count grows; onboarding back arrow and footer; the French strings fitting one line.

### A2. Fold the two active changes

Prepare now, land after A1: the spec text and log entries can be written at once, but a change is archived as landed only once its device test passed (cairn forcing rule). Fold `sync-strip-progress` and `onboarding-options-ask-on-switch` into cairn/spec/offline-store.md and onboarding.md, write their log entries, archive them, settle the CHANGELOG wording. The user commits.

### A3. Graph message size

Graph returns no size today (rust/src/client/graph_mail.rs:856 writes `size: None`), and Part B's info line sums sizes. Read MAPI `PidTagMessageSize` through the listing: `$expand=singleValueExtendedProperties($filter=id eq 'Integer 0x0E08')`, mapped onto `mail_summary.size`. Check the summary-agreement rule (Annex A: server-derived and body-derived summaries agree byte for byte): `size` at the `Meta` tier is already the server's (`RFC822.SIZE`), so Graph's MAPI size is the same kind of value. Test against graph_mail_tests.rs.

### A4. Gmail first-sync stall (the user, later; only if a fresh account reproduces it)

The over-a-minute indeterminate phase seen on repeated Gmail onboardings comes from inside `mailFloor` and the first `enumerate` (rust/src/client/gmail_sync.rs, rust/src/client/throttle.rs). Likely causes, in order:

1. Every Google 429 and every 403 `rateLimitExceeded`/`userRateLimitExceeded` is classed as a per-minute quota: the call sleeps to the next wall-clock minute and spends the shared budget, so every worker waits about 60 s. Two workers sending 50-message batches at once draw Gmail's concurrent-request 429.
2. The two-day margin: a first-chunk round reads every envelope of its page, then drops those below the floor, so it reads 50 plus two days of mail per listing (account and inbox).
3. An imported account with clustered received dates makes `after:` filter nothing.

Diagnose first in logcat (`throttled, retrying in`, per-page `remote N ms`, `mail pass ...: remote summed`). Fixes: a short back-off for concurrency 429s, the minute budget spent only when the answer names a per-minute quota; one batch in flight per account or a smaller batch; stop reading envelopes once a page is past the floor, or narrow the margin.

### Settled, no work

- First sync of every backend: the newest 50 headers of every mailbox, inbox first, then the header fill in the background. Microsoft lands in seconds even on a 1 GB mailbox.
- Gmail: one account-wide listing, labels projected from it; the first chunk is the account's newest 50, the inbox getting its own 50 when left behind. A different design, kept.
- IMAP: per mailbox, `EXAMINE`, cached `UID SEARCH`, headers newest first until 50 dated ones give the floor, listing from it with a two-day margin, CONDSTORE/QRESYNC deltas after. Kept.

## Part B. The mail download window

### B.1 The idea

Today every stored header is listed (infinite scroll widens further), but only opened messages are readable offline, and nothing shows which ones. Text extraction (a pimdir `Text` level with pruned messages) was considered and dropped: it goes against MIME and costs a spec, a construction to make deterministic, and a second download to reach the real message. Instead:

- **The list shows the window**: messages dated on or after the account's window date, all of them downloaded whole (body and attachments, the server's exact bytes, plain pimdir `Full`). What you see is what you have.
- **Headers keep syncing beyond the window** in the background, as today, so search covers every stored header (better than Thunderbird, whose search stops at the downloaded set). A search result beyond the window is dimmed and says *Not downloaded*.
- **A footer button moves the window back**: *Load since 1 September*, with an info line under it (*1,240 messages · about 85 MB*) and a secondary *Choose a date*.
- Nothing is asked at onboarding: the first window is the first chunk.

Flow after connecting an account:

1. The newest 50 headers of every mailbox, inbox first (unchanged).
2. The bodies of the window, newest first: the window starts at the first chunk's inbox floor, so this is about the inbox's 50 plus whatever the other mailboxes hold since that date.
3. The rest of the headers, in the background (unchanged header fill).

### B.2 The window date

- **Per account**, stored as app state beside the account (as `MailScope` months and `MailOffline` are), not in pimdir: it is presentation and download policy, not store truth. A class `MailWindow` with `since(context, accountId)` and `set`.
- **Initial value**, at the end of the account's first mail sync: the inbox's first-chunk floor, the oldest `Date` among its newest 50 (`MailStore.coverage(inbox).since` after `firstMail`). Gmail: the account floor (`MailEngine.accountFloor`). A mailbox with no inbox role: the newest floor among the account's mailboxes. An empty account: today, first of the month.
- **Only moves back by the user**: the footer button, the date picker, or *Download this mailbox* (per mailbox, below). New mail lands above it by definition.
- **Bounded by the period** (`MailScope`, the account's 1, 3, 6, 12, 24 months or all): the window is never older than the period's floor, since no header exists below it. A picked date beyond the period widens the period to the smallest value covering it (or all) and says so in the confirmation. Narrowing the period below the window's date raises the window to the period's floor (`collectBefore` deletes headers and bodies below it, as today).
- **Undated messages** (empty `sort_key`) fall below any window, as they fall below any floor today; they show when the window is all mail.
- **Migration** of existing installs (testers only, the app is unreleased): an account on `BACKGROUND` or `WHOLE` takes the period's floor as its window (its bodies are mostly held); an account on `ON_OPEN` takes its inbox's first-chunk floor recomputed from stored headers (the newest 50 inbox rows), so no surprise download.

### B.3 What the list shows

The merged list spans accounts and mailboxes. Its floor:

**list floor = max(window date of every shown account, coverage floor of every shown collection)**

- The window part keeps the list continuous by date across accounts: with account A's window at 15 October and B's at 3 June, the list stops at 15 October for both, rather than showing B's August while hiding A's. B's older rows show when B is filtered alone, or once A's window moves.
- The coverage part (`MailStore.floorOf`, today's floor) keeps a mailbox whose headers are not listed down to the window from showing a gap. It is what the list uses today.
- A mailbox kept whole (`MailOffline.whole`) has no window: its own floor is its coverage floor. Filtered alone, it lists everything.
- The outbox stays on top, outside the window, as today (MailList.java:296-304).

Everything that counts follows the list floor:

- The meta line (*1,240 messages, 12 unread*), the day headers (`countByDay`), select-all (`currentQuery`) and the chips (unread, attachments): already floor-limited through `listed = wanted.reaching(floor)` (MailList.java:293-307).
- **The bottom-nav badge** (`badge = store.unread(everything)`, MailList.java:308) counts every stored header today. It becomes the unread count within each enabled account's window, all mailboxes the filter shows; otherwise it would count years of unread mail the fill listed.
- Search ignores the floor, as today (MailList.java:293). Rows below the floor are dimmed and labelled *Not downloaded*; opening one downloads it on open (MessageView.load), storing it outside the window, which retention tolerates (B.9).

**Store statements.** `count` and `countByDay` with a floor wrap the canonical statements and apply the floor outside (`MailStore.reaching`, :1112), which reads every stored row of the shown collections on every reload: with 100,000 stored headers that is the common path. Add a `:since` parameter to the canonical mail statements in pimdir/queries (list_mail_page_filtered, count_mail, count_mail_by_day, count_unread, search_mail unchanged), applied inside on the `sort_key` index, and a new `sum_mail`: count, summed `size`, and the count of rows of unknown size, over collections, chips and a `[since, until)` range. A pimdir STORAGE §14 change, then an io-pimdir patch release, pinned by the app. The Java wrappers go.

### B.4 The footer

Below the last row of the window, replacing today's *more* row (`MailList.more`, `older`, `stallOf`, `retryOlder`, `MainActivity.widenMail`, strings `mail_more_*`, MailOlderTest):

- **Button**: *Load since 1 September*.
- **Info line** under it, small: *1,240 messages · about 85 MB*. From `sum_mail` over the shown collections between the next date and the list floor. Unknown sizes (Graph before A3, an IMAP server without `RFC822.SIZE`) are left out of the sum and the line says *about* only when every size is known, else *size unknown for 40*. On a metered network a large total adds *on Wi-Fi* (B.6).
- **Secondary**: *Choose a date*, a date picker with an *All my mail* entry, showing the same info line for the picked date before confirming.

The next date:

- The first of the month at or before the list floor: a floor of 15 October offers 1 October; a floor of 1 October offers 1 September.
- Empty months are skipped: the next date is the first of the newest earlier month holding at least one stored header, so one tap never loads nothing. Counted on stored headers only.
- Headers not listed that far yet (the fill lags, or the period stops it): the next month is offered anyway, counted on what is stored, and the info line says *older headers still syncing*.
- No stored header below the floor, and the period at all mail: the button goes; *Choose a date* stays only while the period is bounded.
- Reuses `MailScope.since(months, today)`'s first-of-month arithmetic.

What a tap does:

1. Sets the window of every shown account to the date (never later than its current window: `min`).
2. Lists the headers down to it where a shown collection's coverage floor is above it: a band round to a date, `MailEngine.list(collection, since)` (private today, MailEngine.java:250), the date-based primitive `offlineSyncImmutable(..., since, ...)` already takes. No Rust change.
3. Replans the bodies (B.5): the newly windowed rows download newest first.
4. Reloads: the list grows at once with the rows already listed, dimmed until their body lands.

Edge cases:

- **Offline**: the button stays and works (the window moves, the rows show dimmed); the info line says the download waits for the network.
- **A chip or search active**: the footer follows the chips (counts with them); hidden under a search, which is not windowed.
- **Empty window** (zero rows, older mail exists): the footer shows in place of the empty state (`MailList.render`, :356, today shows the empty state only when `!layout.limited`).
- **Role chip** (*Inbox*): the footer moves the windows of the accounts the chip shows.
- **Several accounts, different windows**: the button's date is computed from the list floor (the newest window), and the tap sets every shown account to `min(window, date)`, so after it every shown account is at least that far back.

### B.5 What downloads

The body planner (`planBodies`, MainActivity.java:1990-2021; `MailBodies`) changes from per-policy to per-window:

- **Which rows**: every enabled account's rows dated on or after its window, in every mailbox **except junk and trash**, plus every row of a mailbox kept whole. Newest first, the shown mailboxes first (as today). The account's mailboxes the filter hides still download: the window is per account, not per filter.
- **Linking**: a Gmail message under several labels is fetched once; the engine's upgrade links the held body (`MailEngine.download`, mail.md:547).
- **Pending creates** are skipped, as today.
- **Bodies on open** stay (MessageView.load), whatever the window.
- `bodiesLeft` and the drawer pill keep counting what is left, now the window's.
- The download glyph (A1) covers it.

### B.6 Networks

- **Wi-Fi**: everything in the window.
- **Metered**: bodies up to a size cap (256 KB) within the window, so a user who leaves home with a fresh install can still read their recent mail; larger ones wait for Wi-Fi, their row dimmed with *Downloads on Wi-Fi*. A row of unknown size counts as large on a metered network unless its attachment mark is 0.
- The per-account *metered* switch (`MailOffline.metered`, AccountSettings.java:259-271) goes: the cap replaces it.
- The header fill keeps its gate (foreground, unmetered, `fillAllowed`); headers are cheap but unbounded, and the window does not depend on them being complete.
- The cap is a guess: measure typical text sizes on the test accounts before release.

### B.7 Settings

- **Removed**: the offline policy row (`MailOffline.Policy` `ON_OPEN`/`BACKGROUND`/`WHOLE`, `R.array.mail_offline_policies`) and the metered switch. The window replaces *background*; *whole* is the picker's *All my mail* plus the period at all.
- **Kept**: the period (how far back headers are kept, and so how far search reaches and how far the window can go), with the B.2 interplay.
- **Kept**: *Download this mailbox* / *Stop* on the filter page (FilterPage.java:132-198): a mailbox kept whole is listed past the period and downloaded entirely, whatever the window. The delete confirmation's advice to download the trash from the filter page (MessageView.confirmDelete, strings.xml:283-285) stays true.
- **Added**: the account settings show the window date (*Mail on this phone since 1 September*), with the same picker as the footer. Moving it later (towards today) frees bodies: B.9.

### B.8 The edge made visible

- A row in the window whose body is not stored yet (still downloading, or waiting for Wi-Fi) is dimmed; the row's binding reads `object_hash` and `level` already (`bodyRows`).
- A search result beyond the window: dimmed, *Not downloaded*.
- Opening a message whose body is not stored, offline: *This message is not on your phone yet. It will download when you are back online.*, never a blank page.

### B.9 Retention

- **The window only grows by default.** Moving it later from settings frees the bodies below the new date, headers kept (search still covers them).
- pimdir has no statement dropping an item's body back to `Meta`: `collect_before` deletes whole items (headers included), `purge_item` likewise. Needed: an owner statement releasing an item's object (`object_hash` to null, `level` to `Meta`, the binding's `base_object` released for an immutable kind, where it is the body itself), skipping items holding a local edit, a pending create or a conflict, then the collector. A pimdir change (STORAGE §11, SYNC's level rules: a `Meta` row whose body was released is not a claim to revisit) with vectors.
- Until it lands, the settings picker only moves the window back; moving it later is the second step of B.
- Bodies opened beyond the window (from search) stay until the next release moves them out with the rest below the window.
- *Free space* on Deleted items keeps its meaning (retained rows only).

### B.10 Notifications

`BackgroundJob.check` diffs the unread inbox before and after a pass (`unreadInbox`, :185-204, over all stored inbox headers). The background run never fills, but an in-app fill landing between two snapshots would list older unread headers and notify them. Notify only messages dated on or after the account's window, and newer than the newest already-notified date. Applies to the active `background-check` change's "New mail notifies" requirement and its "Widening the bound" scenario.

### B.11 Changes by file

- New: `MailWindow` (state, initial value, migration), the footer layout (`item_mail_window`), strings (*Load since %s*, the info line plurals, *Choose a date*, *All my mail*, *Not downloaded*, *Downloads on Wi-Fi*, *older headers still syncing*, the offline open screen, the settings row).
- `MailList`: floor = max(window, coverage); badge windowed; footer replacing the *more* row; empty-window case; dimmed rows; search dimming.
- `MailStore`: `query` takes the window; statements through the new `:since` parameters and `sum_mail`; the `reaching` wrappers go.
- `MainActivity`: `planBodies` per window; `widenMail` goes; `firstMail` sets the initial window; the footer's tap (window, band listing, replan).
- `MailEngine`: `list(collection, since)` reachable for the band round to a date.
- `MailBodies`: rows carry `size` and `attachment` (already in `bodyRows`' columns 13 and 14); role exclusion; metered cap.
- `MailOffline`: policy and metered removed, `whole` kept (or moved into `MailWindow`).
- `AccountSettings`: policy row and metered switch removed, window row added.
- `MessageView`: the offline not-downloaded screen.
- `BackgroundJob`: notification cutoff.
- rust/src/client/graph_mail.rs: A3.
- pimdir/queries and io-pimdir: `:since`, `sum_mail`, then the release statement (B.9).

Tests to rewrite or drop: MailOlderTest (dropped with the *more* row); MailFillTest `aScrollWidensEveryShownMailboxHoldingTheLimitingFloor` (scroll widening goes); MailChunksTest `theListReachesDownToTheMostRecentFloorAndWidensTheMailboxHoldingIt`; MailBodiesTest `theStepRaisesOnlyBodiesWithinTheBound`, `aMailboxKeptWholeDownloadsPastTheBound`, `aMeteredNetworkDefersIt`; MailStoreTest `theListIsSizedPlacedAndPagedByTheStore`, `aNarrowedBoundCollectsWhatFallsBelowIt`. New: `MailWindowTest` (initial value per backend shape, migration, period interplay, next date with empty months, the merged floor), body planning (roles, window, whole, metered cap), badge windowing, notification cutoff.

Spec requirements modified (cairn/spec/mail.md): the intro; *The mail list narrows what it shows*; *The mail list selects*; *An opened message is stored and read back*; *A mailbox is stored whole*; *An account bounds its mail*; *The mail list loads lazily*; *A mailbox is listed a chunk at a time* (scenario *Widening*); *A Graph mailbox keeps one unfiltered delta link* (scenario *Widening relists nothing*); *The merged list reaches down to its mailboxes' floor*; *Older mail fills in behind*; *An account's mailboxes sync side by side*; *An account chooses which bodies it keeps offline* (removed); *A mailbox can be downloaded whole*; *Bodies download behind the list*; *The drawer shows bodies left to download*. cairn/spec/offline-store.md: *A list opens on a large title*; *An empty list says so below its header*. Active changes touched: `account-settings-page` (*Picking a sync period*), `background-check` and `onboarding-options-ask-on-switch` (*New mail notifies*).

### B.12 Steps

1. pimdir: `:since` on the mail statements, `sum_mail`, vectors; io-pimdir patch release. (A3 in parallel.)
2. App: `MailWindow`, list floor, badge, footer with button, info line and picker, band round to a date, body planner per window, metered cap, settings cleanup, dimmed rows, offline screen, notification cutoff. One cairn change, `mail-download-window`.
3. pimdir: releasing a body to `Meta`, vectors; io-pimdir release.
4. App: moving the window later from settings frees bodies.

## Part C. Files and references

At the end of Part C: a Files tab with folders and every attachment, and items linked across mail, contacts, calendar and files, automatically and by hand. The Files tab and the linking interface ship functional; the user redesigns both afterwards.

With whole messages downloaded (Part B), mail no longer needs a `Text` level, and the file domain stands on its own merits: attachments deduplicated and saved to folders, file remotes later, and cross-domain links for search.

### C.1 References between items

**What.** One generic table in pimdir STORAGE linking any item to any other: message to attachment file, invitation mail to event, contact to the mail they sent, event to contact, anything a user links by hand. The ground for notmuch-like search: following several hops in one query (*attachments sent by the people attending tomorrow's meeting*: event, attendees, contacts, mails, files) is joins rather than address matching at each step, and a contact with several addresses is one relation.

**Rules.**

- **Facts, not derivations.** A reference is recorded when it makes sense and never recomputed. It lives in STORAGE, not in the rebuildable SEARCH index: a contact changing address keeps every link to the mail sent from the old one, which no rebuild from current content could restore. (A retired-addresses table was considered and dropped.)
- **When automatic references are recorded**: a new item, linked to what it matches (new mail to the contacts its addresses name, an invitation to its event by UID, a message to its attachment files); a new contact, or an address added to one, linked to existing mail from that address, so a contact added today has its history; an address removed changes nothing; merged contacts hold both sets; a deleted item takes its references with it.
- **Users link and unlink anything to anything.** An unlink is a plain delete; an automatic rule matching again later recreates the reference, because it is true. No suppression state. To keep a link from returning, remove its cause.
- **Origin** is recorded (`auto`, `user`): the UI can say why a link exists, and a mistake can be undone in bulk (an address recycled to someone else: remove the automatic references from that address, user ones untouched).
- **Local to the store.** Providers know nothing of references. On another device, automatic ones are recorded again by the same rules; user ones travel only with the store itself (backup, migration). How user references could travel is open.
- SEARCH reads the table for cross-domain queries (`from:person:<id>`, `attendee-of:<event>`); it computes nothing itself.

**First step: the table alone.**

- `references`: `from_kind`, `from_link_id`, `to_kind`, `to_link_id`, `role`, `origin` (`auto`, `user`), `created_at`; unique on `(from_kind, from_link_id, to_kind, to_link_id, role)`.
- Endpoints are `(kind, link_id)`, not `(collection, link_id)`: pimdir keys an item per collection (`items` keyed `(collection, link_id)`), so one message under three labels is three rows sharing a `link_id`, and a reference must survive a move. The kind is in the key because a vCard UID and an event UID can coincide.
- Not a foreign key, for the same reason: deleting one collection's row must not drop references another row still holds. A reference goes with the last live row of either endpoint: a trigger or the collector's sweep, to decide when writing.
- `role`: an open vocabulary; pimdir names `attachment`, `invitation`, `sender`, `related`; applications add `x-` ones, as capabilities do.
- Canonical statements: `add_reference`, `remove_reference`, `references_from`, `references_to`.
- Nothing mandatory: no automatic rule required; writers MAY record references, readers MAY ignore them.
- Vectors: insert; duplicate refused; unknown `x-` role accepted; a reference surviving one row's deletion and going with the last.

### C.2 The file kind

**Model.**

- **Blob**: bytes named by hash, in the existing objects/ store, deduplicated.
- **File**: an identity surviving edits and renames, pointing at its current blob: a pimdir item, `link_id` the identity, `object_hash` the current revision. "A file is a blob with a location" holds only while it never changes: an edit makes a new blob, and without an identity a rename becomes a delete plus a create, losing the conflict base and turning a WebDAV `MOVE` into a re-upload.
- **Folder**: a collection, a database row as a mailbox is; nothing mirrors a filesystem tree. A file in a folder is the item's row there, its name a placement property. Renaming a folder is one row; moving a file one placement; the same file in two folders two rows sharing a `link_id` and a blob, as Gmail labels are.
- **One kind**, `file` (its media type to pick when writing the spec). A file's own media type, size and name are summary properties in a `file_summary` table under Annex A, not kinds.

**Revisions and conflicts.**

- A new revision is a new blob; `object_hash` moves to it.
- The parent check is pimdir's existing one: each binding's `base_revision` (ETag, modseq, `changeKey`, JMAP state) and `base_object`, a push conditioned on the base (`If-Match` and equivalents), a mismatch a conflict. Nothing more, in files or elsewhere.
- Within one store the engine is the only writer; the parent matters across stores (two phones writing one Nextcloud folder), where without it the second push silently overwrites the first, the loss pimdir draft-02 fixed for items (vector 33).
- Conflicts resolved by hand: keep both, or pick one. No local history beyond the base: the remote keeps history where it has one.
- Read-only blobs (as in the Nix store) were considered and rejected: pimdir is cross-platform, and Windows and FAT/exFAT cannot honour it. The guarantee stays the hash, and pimdir never edits a blob.

### C.3 Attachments as files

- With whole messages stored (Part B), an attachment's bytes are already inside its message's blob. Copying them into a file blob for every message would double the storage.
- So an attachment is a file item **without a blob** by default: its name, media type and size in `file_summary`, and an `attachment` reference from its message carrying the MIME part id. The reader lists a message's attachments from its references, and opens one from the message's blob when held, or by fetching that part alone (IMAP `BODY.PEEK[<section>]`, Gmail `attachments.get`, Graph `/attachments/{id}`, JMAP blob download) when the message is not downloaded.
- The file gets its own blob only when it leaves its message: *Save to folder* (a placement in a folder collection), sharing, or a file remote. Deduplication then applies: the same PDF sent three times saved once is one blob.
- Recorded when a message's body is stored (download or open), by walking its MIME parts: the same moment the attachment mark is restated today (`markAttachment`).
- A file with no folder placement and no reference left (its message deleted) is collected.

### C.4 The Files tab

A fourth tab in the bottom bar, the same list design as the other three (large title, meta line, chips, filter page, extended add button). First version functional; the user redesigns it later.

- **Folders**: collections of the `file` kind, with their files. With no remote, a local folder tree on the phone; remotes (C.7) plug into the same collections later, as accounts plug into mail.
- **Attachments view**: every attachment across mail, from the `attachment` references, filterable by media type and sender; blob-less ones (C.3) open from their message's blob or by fetching their part.
- **Actions**: open (Android intent on a content URI), share, save to a folder, delete, and *Show the message it came from* through the file's reference.
- **Phone storage**: import and export through Android's document picker (`ACTION_OPEN_DOCUMENT`, `ACTION_CREATE_DOCUMENT`), not a mirror.
- Drawer and filter page list the file collections like the other domains; the sync strip and filter work unchanged once a remote exists.

### C.5 Linking in the interface

The references of C.1 made visible and editable. First version functional; the user redesigns it later.

- **A Linked section** on every item page (message, contact, event, file): its references in both directions, grouped by kind, each opening the other item.
- **Why**: each automatic link says its role in words (*sent by*, *attachment*, *invitation*); a user link says *linked by you*.
- **Link to**: from any item, a picker searching across the four domains, adding a `user` reference with role `related`.
- **Unlink**: a plain delete (C.1); an automatic link may come back while its rule still matches, and the section can say so.

### C.6 Then

- Automatic references (C.1 rules): sender, invitation, attachment, the contact backfill.
- SEARCH: cross-domain queries over references.

### C.7 Later

- File remotes: WebDAV/Nextcloud and Drive into the file kind, folders as collections, ETags or revision ids as `base_revision`; an Android document provider or a real-directory export as views, as the phone contacts mirror is.

### C.8 Steps

1. pimdir: `references` table, statements, vectors; io-pimdir.
2. pimdir: `file` kind, `file_summary`; io-pimdir.
3. App and io-pimdir: attachments as blob-less files referenced by their message, recorded when a body is stored; the reader's attachment rows from references; *Save to folder*.
4. App: the Files tab (C.4).
5. Automatic references (C.1 rules); SEARCH cross-domain queries.
6. App: linking in the interface (C.5).
7. File remotes (C.7).

## Later, independent

- **Preview**: a ~200-character snippet in `mail_summary`, free at listing time on Gmail (`snippet`), Graph (`bodyPreview`) and JMAP (`preview`), none on IMAP without a partial fetch. Follows the attachment mark's precedent against Annex A's agreement rule (the server's value until the body is read, then a fixed derivation). A list nicety, not offline reading.
- How user references travel between devices.

## Open questions

- The metered size cap (256 KB is a guess): measure on the test accounts.
- Whether *Download this mailbox* stays separate or becomes a per-mailbox window.
- When a reference goes: a trigger on the last live row, or the collector.
- The `file` kind's media type.
- Whether hidden mailboxes of a shown account should download (B.5 says yes, the window being per account).
