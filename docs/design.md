# Pimalaya for Android: design

The core idea and the target structure of the app. The current code implements the connection flow, the three list screens (contacts read-write, mail and calendars read-only) and the hub-and-spoke sync engine on io-offline, both spokes included (docs/io-offline-migration.md for the server spoke, docs/phone-sync-plan.md for the phone one).

## Screens

One activity, one ViewFlipper, one panel per screen:

1. **Connection** (email, config, password panels). Shown on first launch and, later, when configuring a new account. The user enters an email; Pimalaya detects the provider family from the domain and proposes matching configurations:
   - Google account: Google Contacts API (io-gpeople) or Google CardDAV over OAuth. Both pending; password CardDAV is not proposed because Google does not accept it.
   - Microsoft account: Microsoft Graph (io-msgraph contacts) over OAuth. Wired end to end behind the same operations as CardDAV (the account's msgraph:// base URL routes them); the Rust bridge projects Graph contacts to and from the vCard document of record, since Graph has no vCard representation. Waits only on the Entra app registration client id.
   - Anyone else: standard CardDAV, resolved via pimconf RFC 6764 discovery (SRV, TXT, .well-known over a DNS-over-TCP resolver), plus JMAP for Contacts (RFC 9610) once io-jmap grows contacts support.

   Picking CardDAV asks for the password, verifies the connection (the addressbook discovery walk doubles as the check) and stores the account AES-GCM-encrypted under an Android Keystore key.

   Straight after a successful connection comes the **addressbook selection** step: the discovered addressbooks are listed with checkboxes and the user picks which ones to sync locally. Each selected addressbook becomes one Android account of Pimalaya's own account type (the DAVx5 pattern; Android has accounts, not addressbooks), created via AccountManager with an authenticator stub. Per-account raw contacts carry the vCard UID and ETag in their sync columns, deleting the account cleanly removes its projected contacts, and the user can toggle visibility per addressbook in any contacts app.

2. **Sync**. Two manual actions matching the two spokes; the in-app Sync (the drawer row) runs both in one pass, and pulling down on the contacts list runs them over the books the filter shows, while Sync local remains as a headless hook:
   - *Sync remote* (store to server, the only moment the app touches the network): per addressbook, the io-offline engine reconciles the store with the remote (docs/io-offline-migration.md): it enumerates the member spine incrementally from the stored checkpoint (RFC 6578 sync-collection, Graph delta, JMAP /changes, People sync tokens; a complete round when the cursor is missing or expired), three-way merges each placement against its base, pushes the local-won changes (creates, If-Match-guarded updates and deletes, membership patches), batch-fetches the bodies the spine misses, and resolves conflicts by merging both sides against the staged base (the local side wins same-field collisions, an update beats a removal) before a second reconcile pushes the resolutions. When the remote is unreachable the screens fall back to the store of the last sync.
   - *Sync local* (store to phone, no network): reconciles the per-addressbook Android accounts and runs the two-way phone engine pass per subscribed book in-process, behind the same modal loader as the remote sync. The registered sync adapter runs the identical pass for the syncs the OS schedules on its own (the system's per-account "sync now", and the upload syncs Android requests after a contacts-app edit). Asks for the contacts permission on first use; until it ran once (no Android accounts yet), the remote sync's own phone passes stay silently off.

   *Sync remote* also runs the phone spoke: each book syncs phone, then server, then phone again, so a contacts-app edit reaches the server in one pass and the server round projects back in the same one.

   Launch is offline first: the contacts screen renders instantly from the store. By default the app never syncs by itself; each addressbook can opt into scheduled background sync (the "Synchronize in background" cadence, set at the end of the connection flow or from the account's settings screen, account-wide or per book behind its Advanced fold), which runs the same three-pass book sync through one WorkManager periodic worker per book, the DAVx5 pattern; a book whose remote switch is off keeps its phone pass and skips the server exchange. A pass that did something posts a notification shaped like the in-app sync toast (pulled, pushed, merged, titled by the book); a pass with nothing to report posts nothing. Contacts in a pending both-sides-edited conflict sit each pass out (the engine parks them untouched, per item, while everything else keeps syncing) and ride the notification as a warning subtitle on every pass until the user resolves them in the app; enabling a cadence also turns on the Android account's content-triggered sync, so contacts-app edits upload into the hub as they happen.

3. **Contacts** (list + editor panels). Lists the cards of every synced addressbook by FN. The editor is a tabbed form (name, contact, address, other, plus a read-only source tab showing the raw vCard) over the same neutral field model the phone projection uses: the form collects the model and the Rust bridge patches it onto the stored vCard through the vcard-rs CST, so every property the form does not manage (PHOTO, CATEGORIES, IMPP, X-*) survives untouched. Saving and deleting are offline first: they only stage the change in the base; the next sync pushes it.

4. **Mail** and **Calendar** (list panels). The same merged view over the two other domains, read-only: every mailbox of every account in one message list, every calendar of every account in one agenda.

### The chrome the three lists share

A bottom bar switches between the three ([Domains](../android/app/src/main/java/org/pimalaya/Domains.java)), the one being shown on a neutral indicator, mail carrying its unread count: it is the app's whole top-level navigation, and it shows on the lists only, a reader or an editor being somewhere one goes back from. Above, one app bar: the burger, which opens the drawer: one card per account, saying what it covers and, on one pill, whether it is deactivated or when it last synced, then the actions, fixed at the bottom. The drawer lists no mailboxes: every list is one merged view the filter narrows, and a drawer opening one collection would be a second, competing way to narrow it; the domain's name, which only appears once the list's own large title has scrolled away, so the name is never shown twice and never lost; then that domain's actions, with no overflow. The merged view's filter is the one action every domain shares, and it wears the accent while it is hiding something, so a narrowed list cannot be mistaken for an empty one. Each list's add button is an extended one, shrinking to its square glyph while the list scrolls down. The app opens on the mail list.

Every list opens on a large title over a count and whatever the domain narrows with (a search field, chips, the week in one card under its month, number and arrows), then groups its rows into rounded cards under a section header ([CardSections](../android/app/src/main/java/org/pimalaya/CardSections.java)): mail by day, contacts by letter, the agenda by day. The header carries the coarse question (which day, which letter), so the row carries the fine one: a message its time, an entry its start. A ListView has no container to round, so a card is its rows' backgrounds put end to end, the first rounding its top and the last its bottom.

The three rows are one row. Each leads with a disc, then a title line and its supporting lines, each trailing mark ending the line it describes: a contact's initial over its name, its phone and its addressbook and account; a sender's initial over the replied mark, the sender, the time and the unread dot, the subject ended by its marks (a star while important, a paperclip while it carries an attachment, under the dot), the mailbox, a hairline parting the rows of a card; a calendar's initial over the summary and its start, the entry's kind and its length, and the calendar and account. The colours are the device theme's, never a fixed palette: the cards and pills sit on the app's neutral surface, the bars on the page.

Dates are told the way a reader would tell them. A day of mail is headed Today, Yesterday, 3 days ago; an entry's page says in 20 minutes, in 3 days, 2 hours ago; an open message says the exact date with how long ago beside it. One unit and never two, and calendar days rather than elapsed hours, so a message from 23:00 last night reads Yesterday and not nine hours ago. The vocabulary lives in one place (Dates) because the three domains show the same column, and two wordings for one idea would read as two apps.

What each disc is keyed by is what identifies the row rather than what is written on it (a contact's whole vCard, a sender's address, a calendar's id), so renaming any of them keeps its colour.

A selection, of contacts or of mail, turns a row's disc into a check, with no checkbox on the row, and the bar takes the count and the actions over it. A contacts section header selects its whole section, starting a selection when none is running.

Each domain's FAB adds to that domain. Contacts opens its editor; mail and calendars open frames, screens that exist and are navigable so that what goes in them is built against wiring that already works.

### What a row opens onto

Tapping a row opens the thing itself, on a screen titled by it with a back arrow onto the list.

A **message** opens on a header card and its body. The card is drawn from the row before anything is fetched (the store already holds the subject, the sender and the date), and the fetch fills in the recipients, the exact date and the attachments, each a badge naming a file and its size. The body is fetched every time, since the merged list stores spines and has nowhere to keep one; IMAP fetches the raw message and the bridge resolves its MIME tree, JMAP asks for the body values in the same call as the headers. HTML wins over the text alternative when both are there, and renders in a web view with scripting off, network loads blocked and images not loaded at all: that sandbox is what makes preferring HTML safe, since a remote image is a read receipt nobody asked permission for.

A **calendar entry** opens on the page a contact opens on, and in the same way: there is no reading screen and no editing screen, there is the page. One scroll of sections, every row tappable into its dialog, the bar's add button offering the properties the entry does not carry yet, the FAB to save. The two share their builder (Sections) rather than looking alike by coincidence, because a contact and an event *are* the same page with different sections; everything worth reading is worth correcting where it is read.

Which sections and which properties follow the component, that being the honest difference between the three: an event runs between two moments, a to-do is due and partly done, a journal entry is written on a day and has neither. Offering a to-do an end would be offering to write a property RFC 5545 does not give it. Three things stay read-only: the attendees (a list that is a negotiation, not a text field), which calendar and account hold the entry, and the occurrence that was tapped, since one date of a repeating entry is computed from a rule and moving it needs an override with its own RECURRENCE-ID. Editing the rule, or anything else, edits the series, which the page says by naming the occurrence separately.

Saving patches the object through the bridge's concrete syntax tree, so every property the page does not manage (VALARMs, X- properties, the attendee list, the parameters on the lines it leaves alone) survives byte for byte; then it PUTs the result guarded by the ETag the object was read with, because a calendar is shared and an unguarded PUT is how one client silently overwrites another's edit. The store is written from the same text the server took, so the agenda shows the edit without waiting for a sync, and a push that fails writes nothing rather than leaving a local copy the next sync would quietly undo. Only CalDAV accepts an edit so far: a JMAP calendar takes CalendarEvent/set in JSCalendar, a conversion that runs only in the read direction today, and that path refuses out loud rather than no-opping.

## Sync engine

Hub and spoke. The vCard store is the hub replica: the document set the app actually edits, full vCard fidelity, offline by construction (SQLite today, a vdir or io-m2dir-style backend tomorrow; the spokes do not care). The phone contacts and the remote are two spokes, and a Sync pass reconciles the hub with each spoke pairwise, reusing io-offline twice with two backends:

- **store to remote**: full fidelity, merged in vCard space with the vcard-rs three-way merge; per-card identity is the ETag plus the base vCard. This is io-offline with the CardDAV backend (or a provider API later). DONE: this spoke runs on the engine today, see docs/io-offline-migration.md.
- **store to phone**: lossy at the boundary, lossless in the hub; the phone is just another io-offline remote whose items are raw-contact rows, whose content revision is the raw contact VERSION, and whose reads and writes go through the Java converter (Mapping both ways, the read-back applied onto the last converged vCard as a field-space patch). Pimalaya owns one raw-contact account per selected addressbook (the DAVx5 pattern) and keeps the card id in SOURCE_ID and the vCard UID in SYNC2. DONE: this spoke runs on the engine too, see docs/phone-sync-plan.md.

Two pairwise syncs beat one tri-directional merge: each merge stays in a single representation space (whole vCards on one spoke, lossy field models on the other), and the spokes fail independently (no network still syncs the phone exactly; no contacts permission still syncs the remote).

Within one Sync pass the phone spoke is local-only and cheap, so it runs twice: ingest phone edits into the store, reconcile the store with the remote, then project the pulled changes back to the phone. One user action, and a phone edit reaches the server in the same pass.

Inside the store, what the user edits is the working copy; each card also keeps one base per spoke, which user edits never overwrite: the working-vs-base diff is the hub's own change on that spoke, the fetched-vs-base diff is the spoke's. The vcard column is the working copy; base_vcard plus etag is the server base on the card row, phone_base plus phone_revision the phone base on the membership row (per book, since each book projects its own raw contact), and the engine's placements are mapped over both axes.

The field-by-field mapping between ContactsContract data kinds and vCard properties, the split of the converters between Java and Rust, and the rules that prevent infinite edit loops (CALLER_IS_SYNCADAPTER, patch-never-regenerate through the vcard-rs CST, field-space diffing) live in [docs/contacts-mapping.md](./contacts-mapping.md).

### First sync

The first sync has no base and the phone may hold contacts that do not exist remotely, so a blind three-way merge cannot dedupe a person present on both sides with two non-identical vCards. Plan:

1. First sync is remote to store to phone only: remote is right, the store is empty, and existing phone contacts are left completely untouched (they live in other raw-contact accounts anyway).
2. Importing pre-existing phone contacts is a separate, explicit, interactive step, offered after the first sync: exact matches on email or phone number are merged silently, ambiguous candidates are reviewed one by one (keep both, merge, skip), and unmatched locals are proposed as new remote contacts.

This keeps the first sync fast and deterministic and confines the slow interactive part to an optional screen, instead of blocking onboarding on a review of the whole phone book.

### Conflicts

Divergent edits on the same contact keep both sides (no silent loss), following the Pimalaya-wide rule; field-level merging becomes possible once the vcard-rs diff lands.

## Out of scope for now

- Multiple accounts (logins); multiple addressbooks per account are in.
- Push sync (WebDAV-Push over UnifiedPush); periodic background sync is in, per addressbook, through WorkManager.
- The JMAP backend; the config screen already reserves its slot. OAuth accounts persist their refresh token and refresh expired access tokens transparently on sync (a 401 triggers one refresh-and-retry per addressbook).
