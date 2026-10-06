# Pimalaya Android plan: a contacts app into a three-domain suite

Expanding what was cardamum-android (contacts only) into the Pimalaya Android app: mail,
contacts and calendar in one app, on one pimdir store, with one merged view and one
conflict model.

The app was unreleased, so there was no install base, no store listing and no purchase
history to preserve: this was a refactor, not a migration.

Status: in progress. This document is the plan of record.

## 1. Fixed decisions (not open for redesign)

**Architecture is cardamum-android's, unchanged.** Java plus XML views, the existing
module split (`android/app`, `android/client`, `rust/`), the engine-hosting per-account
actor, the JNI boundary, the secret and config model. No Kotlin, no Compose, no
Material 3 rewrite. The goal is the smallest, fastest binary possible and the existing
architecture already delivers that; adding a second UI toolkit would work against both.

**himalaya-android is inspiration, not a code source.** Read it for protocol handling,
QRESYNC spine shape, composer flow and attachment handling, then write the mail domain
fresh in pimalaya's idiom against the io-* crates directly. Do not port its Kotlin
`client` module or its storage layer.

**Storage is pimdir, via io-pimdir.** `CardStore` is replaced rather than generalised.
This unifies the on-disk format with the desktop app and the CLIs, gets the sort key,
range queries and unified reads from one place, and removes a bespoke schema from the
maintenance surface. Being unreleased is exactly why this is the moment to do it.

**calendula-android and himalaya-android-m3 become donors.** Their onboarding and
protocol work is read and reimplemented here; both repos are archived afterwards.

## 1b. The demo slice (deliberate deviation from the phase order)

The phases in §3 onward are the correct build order for the product. They are **not** the
right order for getting something on a device fast, because the two highest-value
architectural items (the pimdir migration, the `recur` feature) produce nothing visible
and consume the most time. This section is the tactical slice that inverts that, so a
testable APK exists before the architecture work starts.

**Deliberate deferrals for the demo, each with the reason:**

| Deferred | Why it is safe to defer |
|---|---|
| pimdir migration (P1-3) | `CardStore` already works; a `kind` column is enough for three domains. The migration is invisible to a tester and risks the whole night. |
| `recur` feature (P0-9) | Rendering each event once at its `DTSTART` is wrong for recurring events and fine for a demo. This removes the only hard blocker between now and events on screen. |
| Mail write path | Read-only proves the merged view; compose and send add no information about the shell. |
| Graph, Gmail, JMAP | One backend per domain proves the seam. |
| Day, week, month views | The agenda list reuses the existing list component and proves the range query. |

**The ordering rule: every step ends with a green build and an installable APK.** However
far the run gets, what exists is coherent and testable rather than half-applied.

### Progress, 2026-08-08

Steps 0 through 6 are done and green. Step 7 could not run: no device is attached and
this repo has no `runDebug` task.

**Steps 5 and 6 were swapped, deliberately.** The plan put mail before calendar. That is
backwards for this codebase: `rust/Cargo.toml` already carries `io-webdav` and
`io-pim-discovery`, and the whole DAV walk, the JNI transport and the account model are
in place, so CalDAV is the CardDAV path aimed at a different collection type. Mail has no
`io-imap` at all. Calendar therefore cost one client module and one list; mail is still a
protocol stack, a store shape and a UI.

**Step 4 was met differently than written.** It called for a `kind` discriminator on
`CardStore`. Calendar data landed in its own `EventStore` (events.db) instead: the
contacts schema carries a merge model (base bodies, conflict revisions, dirty and deleted
flags, a write-time index) that read-only events use none of, and both schemas are due to
be replaced by pimdir at P1-3. Widening the schema that is about to go, to hold rows that
need none of its machinery, would have bought nothing. Mail should get the same treatment
unless it turns out to want the merge model.

What the calendar slice actually contains:

```
rust/src/client/caldav.rs      the RFC 4791 walk and the two reports
rust/src/calendar.rs           VEVENT -> occurrences in a civil window, 5 tests
rust/src/ffi/calendar.rs       listCalendars, listEvents, expandEvent
android/client/.../Calendar.java, Event.java, Occurrence.java
android/app/.../EventStore.java    calendars + raw objects, its own database
android/app/.../CalendarList.java  the merged agenda, day headers, filter-aware
```

Known gaps in it: no incremental sync (a refresh relists each calendar, ctag and
sync-token unused), no event detail screen, no day/week/month framing (the agenda only),
and `RDATE`/`EXDATE`/`RECURRENCE-ID` still uncomposed, so an excepted occurrence renders.

What the mail slice contains:

```
rust/src/client/imap.rs        the session, LIST, EXAMINE and the envelope FETCH
rust/src/ffi/mail.rs           syncMail, one call per account
android/client/.../Message.java, Transport.java (imap/imaps schemes)
android/app/.../MailStore.java     envelope spines, its own database
android/app/.../MailDate.java      RFC 5322 date to sort key, 5 tests
android/app/.../MailList.java      the merged list, newest first, filter-aware
```

Two things about it worth carrying forward:

- **The IMAP endpoint is discovered, not configured.** These accounts were onboarded for
  CardDAV and carry no mail server, so `syncMail` runs the same io-pim-discovery provider
  search the wizard uses, takes the `imap` service config, and builds the URL from it.
  Implicit TLS only: there is no STARTTLS step in this client, so a `starttls` config is
  skipped with a log rather than attempted in the clear. A proper mail account type in
  onboarding is what should replace this.
- **The ordering is the store's, not the protocol's.** IMAP returns messages in sequence
  order per mailbox; a merged list spans accounts, so `MailDate` parses the RFC 5322
  header into an epoch stamp at write time and the list is one `ORDER BY stamp DESC` scan.
  Sorting the raw header text would be alphabetical, not chronological.

Known gaps: AUTHENTICATE PLAIN only (no XOAUTH2, so a bearer-token account cannot fetch
mail), no incremental sync (a refresh relists the newest 50 per mailbox), no message view
(the spine is stored, no bodies), and no write path.

### Step 0. Baseline

Establish and record the exact build command in the nix devshell, confirm the current app
builds and installs green before anything is touched. Nothing proceeds until this passes.

### Step 1. Strip billing

Remove `org/pimalaya/billing/**`, the `googleImplementation` billing dependency,
the support and entitlement entry points, and the flavour split if billing was its only
reason to exist. Note billing is `com.android.billingclient`, not androidx. Build green.

### Step 2. Shell rework (the visible deliverable)

Remove the drawer navigation and the `androidx.drawerlayout` dependency. The top app bar
gains three domain icons (mail, contacts, calendar) switching one list screen. Account
and collection management moves to an overflow entry, since it still has to live
somewhere. Build, install, eyeball.

### Step 3. Two-axis filter, proven on contacts

Contacts already have real data, so the merged view is proven there first. A filter sheet
with independent, composable account and collection checkboxes; rows carry an account
badge and collection tags. Cross-account UID duplicates now render as separate rows with
an affinity marker (reversal 2 in §2), rather than collapsing.

### Step 4. Store generalisation, minimum viable

Add a `kind` discriminator to `CardStore` (`message/rfc822`, `text/vcard`,
`text/calendar`). No rename, no schema redesign, no pimdir yet.

### Step 5. Mail, read-only, IMAP only

`rust/src/mail/` with an IMAP-only client: envelope spine, `message/rfc822` meta v1, sync
into the store. List rows plus a message view (mail-parser, plain body first). No
composer, no send, no other backends.

### Step 6. Calendar, read-only, CalDAV only

`rust/src/calendar/` over ical-rs, parsing only. Sync into the store, agenda list, plus
the range query of §6.3 so day/week/month framing already works.

**Recurrence needs no app-side code.** This step originally budgeted a disposable
150-250 line demo expander covering a subset, with anything outside it rendering its
first occurrence plus a marker. P0-9 landed first, so that is deleted rather than
written: turn on ical-rs's `recur` feature and expand with the real thing. The demo gets
full RFC 5545 instead of a subset, and the app carries no throwaway module.

```rust
let rule = IcalRecurRule::parse(raw)?;
IcalRecurExpand::new(rule, dtstart)
    .skip_while(|occurrence| *occurrence < window_start)
    .take_while(|occurrence| *occurrence < window_end)
```

Occurrences are civil date-times, and the views compare them against a civil window, so
no offset resolution is needed to render and the DST and tzdata problem does not arise
tonight. What is still missing from ical-rs is `RDATE`/`EXDATE`/`RECURRENCE-ID`
composition, so an event carrying an exception date renders that occurrence anyway. That
is a narrower and more honest gap than the subset marker would have been, and it is
P0-9's remainder rather than app work.

### Step 7. Build, install, launch

`:app:runDebug` on the connected device, per the repo convention.

**Confidence, honestly:** steps 0 to 4 should land. Step 5 is plausible. Step 6 is a
stretch. The ordering is chosen so that stopping anywhere still leaves an installable app
with a coherent story.

## 2. What this changes strategically

Two prior decisions are reversed. Both are deliberate and recorded here rather than
discovered later.

**Reversal 1: the cross-domain app.** The mobile strategy was three focused domain apps
with the OS PIM stores as the integration bus, explicitly *not* a cross-domain monolith.
This plan builds the monolith, because:

- The unified cross-domain view (one list of mail, contacts and events, filterable by
  account and collection) is only expressible inside one app, and it is the capability
  no competitor can copy without a replica engine. It is the product's proof.
- One polished app is achievable by a small team; three are not.
- The paid services (push, our OAuth, store packaging) convert once per user, not once
  per domain.

What is lost: per-domain F-Droid listings and the ability to say "just a contacts app".
Mitigation is filtering, not separate binaries: the app opens on whichever domains have
accounts configured.

**Reversal 2: cross-account collapse.** `docs/merged-view.md` stage 3 states that cards
sharing a vCard UID "are one contact, always". That becomes: replicas in *different*
accounts are displayed separately with an affinity marker, and merging is a user
gesture. Within an account, collections remain tags on one row.

Rationale: silent cross-account collapse is delightful when right and infuriating when
wrong, it hides that the same person or message exists in two accounts (which matters
when choosing an identity to act from), and it does not generalise to mail at all. The
affinity machinery already exists as the duplicate remover; this promotes it from a
cleanup tool to the default interaction.

## 3. Phase 0: foundations

Three upstream pieces gate the app work; all can run in parallel.

### 3.1 pimdir spec (repo: pimdir)

Draft v1 is edited in place with stores recreated rather than migrated, so these are
cheap now and become migrations once the spec leaves draft.

**P0-1. `text/calendar` meta convention (§13).** Currently undefined; §13 says the other
kinds "define their own `v: 1` convention the same way when they are first written".

```json
{
  "v": 1,
  "uid": "...",                    // string, required, the iCalendar UID
  "summary": "Team standup",       // string, required (may be empty)
  "start": "2026-08-10T09:00:00Z", // string, optional, RFC 3339 (or a date, all-day)
  "end": "2026-08-10T09:30:00Z",   // string, optional
  "all_day": false,                // bool, optional
  "tzid": "Europe/Paris",          // string, optional, DTSTART's TZID
  "recurring": true,               // bool, optional, RRULE or RDATE present
  "until": "2027-01-01T00:00:00Z", // string, optional; absent + recurring = infinite
  "location": "...",               // string, optional
  "status": "CONFIRMED",           // string, optional
  "size": 812                      // integer, optional
}
```

`tzid` and `until` are load-bearing for the range query (§6.3), not decoration. Like
vCard, a calendar object is **mutable content**: the same UID is edited under a changing
ETag, so `revision` moves while `link_id` does not, and `flags` is a known-empty `'[]'`.
State that explicitly, as the vCard section does.

**P0-2. Sort key and indexes.** The store has no orderable column: `meta` is opaque JSON
and the only orderings are `ORDER BY link_id` and `ORDER BY seq`. This blocks
date-ordered mail, calendar range views, the unified listing and search, all at once.

- Add a kind-agnostic sort key to `items`, with per-kind semantics in §13: `DATE` for
  `message/rfc822`, `DTSTART` for `text/calendar`, a normalised display name for
  `text/vcard`.
- Index `(collection, sort_key, link_id)` for per-collection keyset paging and
  `(sort_key, link_id)` store-wide for the unified list.
- Evaluate SQLite **generated columns** over `json_extract(meta, '$....')`, STORED and
  indexed: writers already populate `meta`, so no consumer changes are needed. Check the
  version floor: the schema requires 3.37 for STRICT tables, generated columns landed in
  3.31, JSON functions became unconditional in 3.38.

**P0-3. Calendar range keys.** Index `(collection, start)` and `(collection, until)` so
the two-arm range query in §6.3 is a covered scan. `until` is NULL for both
non-recurring events and infinitely recurring ones; the query distinguishes them via the
`recurring` flag.

**P0-4. Filter projections.** `from` and `subject` equivalents indexed, covering most
real mail search without FTS. Full-text search stays out of the canonical schema
(tokenisers are opinionated and language-dependent, and it needs hydrated bodies).
Document it as an optional, rebuildable derived artifact, consistent with the README
already calling the database "a derived index over the authoritative bodies".

### 3.2 io-pimdir

**P0-5.** Implement the schema changes and expose:

- `list_items_sorted(collection, after, limit)` keyset on the sort key.
- `list_items_all(after, limit)` cross-collection, store-wide, for the unified view.
- `list_range_candidates(collection, from, to)` implementing §6.3.

Existing per-collection reads stay; nothing is removed.

**P0-6.** Cross-store reads stay **out** of io-pimdir. One store per account remains the
model (single-owner rule, per-account retention, blast radius), so cross-account merging
is N read-only handles plus a k-way merge in the app layer.

### 3.3 ical-rs and the temporal gap

ical-rs is much further along than "not ready" suggests: 10,012 lines at v0.0.1, with
the byte-faithful CST, codec, per-property modules under `tree/prop/`, per-component
specs (`vevent`, `vtodo`, `vjournal`, `valarm`, `vtimezone`, `vfreebusy`, `vlocation`,
`vresource`, `participant`), plus fuzz targets, benches and examples. It is structurally
parallel to vcard.

Diffing `ical-rs/src/` against `vcard/src/`, three things are missing:

| Missing | vcard equivalent | Blocking? |
|---|---|---|
| `tree/merge.rs` | `vcard/src/tree/merge.rs` | **Yes** |
| jCal codec (RFC 7265) | `jcard.rs`, `jcard` feature | No, defer |
| JSCalendar (RFC 8984) | `jscontact.rs`, `jscontact` feature | No, defer |

**P0-7. Port the three-way merge.** The only true blocker. The contacts conflict model
rests on it: `rust/src/project/merge.rs` calls vcard's `merge(base, local, remote)` and
reads back merged content plus a collision count, with the rules "the local side wins
same-field collisions, every other remote change flows in, an update beats a removal".
The same merge powers merging two documents against an *empty* base for the divergence
form.

Component nesting is the one genuine difference from vCard: an event contains VALARMs
and may carry a VTIMEZONE, so the merge recurses over child components keyed by identity
(`UID` plus `RECURRENCE-ID` for overrides, `ACTION` plus `TRIGGER` for alarms). Specify
that keying before implementing.

**P0-8. Parity polish**: validation surface, error types, doc coverage matched to vcard,
then publish 0.1.0.

**P0-9. The temporal layer, which does not exist anywhere yet.** ical-rs's `value/`
module is 588 lines across twelve types, every one a thin newtype over `Cow<'a, str>`:
`IcalDate`, `IcalDateTime`, `IcalTime`, `IcalDuration`, `IcalPeriod` and `IcalRecur` all
keep the bytes and parse nothing. That is correct for a byte-faithful library and fine
for contacts, which have no temporal semantics. Calendar is where it bites: **there is
no RRULE parser, no occurrence iterator, no date arithmetic and no timezone handling in
the stack today.**

Decision: an **optional `recur` cargo feature in ical-rs**, default-off, following the
crate's existing shape. This is the established Pimalaya pattern rather than a new one:
vcard layers `jcard` (RFC 7095) and `jscontact` (RFC 9555) as optional features over its
byte-faithful core, and ical-rs already gates `parser`, `quoted-printable`, `base64` and
`encoding` the same way. A consumer that only round-trips bytes (tcal, a faithful editor)
pays nothing, and the README's "pulls in nothing beyond what the optional decoders you
enable need" promise holds unchanged. A separate crate would also have had to re-parse
RRULE from the string, putting the parser outside the library that owns the model.

Scope of the feature:

1. Parse `RRULE` into a typed rule: FREQ, INTERVAL, COUNT, UNTIL, WKST and the BY\*
   parts, with the RFC 5545 §3.3.10 order of application.
2. Yield occurrences as a **lazy iterator**, never a materialised collection.
3. Apply `RDATE` additions, `EXDATE` exclusions and `RECURRENCE-ID` overrides.

**The iterator is the API, not a convenience.** A rule with neither COUNT nor UNTIL is
infinite, so an `expand() -> Vec<_>` either never terminates or exhausts memory. Lazy
iteration makes an unbounded rule a normal case rather than an error case, and lets the
caller stop exactly at the window edge:

```rust
// feature = "recur"
let rule = IcalRecur::parse(raw)?;                       // typed rule
for occ in rule.occurrences(dtstart, &tz)                // impl Iterator, lazy
        .take_while(|o| o.start < window_end) { ... }

// composed at the component level: RRULE + RDATE - EXDATE + overrides
for occ in event.series(&overrides, &tz) { ... }         // Item = Occurrence
```

`Occurrence` carries `start`, `end`, its `recurrence_id`, and whether it is an override.
Iteration is forward-only, which matches how RRULE is defined; no `DoubleEndedIterator`.

**The feature must not own timezone data.** Bundling tzdata would blow the "smallest
binary" goal on Android, where the platform already ships it. It turns out the feature
need not resolve offsets at all (see the timezone-free note below), which is a stronger
form of the same discipline the io-\* crates apply to I/O: pure temporal logic in the
crate, resolution at the edge.

What remains is civil date arithmetic (proleptic Gregorian day counts, weekday-of-date,
week-of-year under WKST), a few hundred lines, so ical-rs need not take chrono at all.

Later optimisation, not v1: a `starting_at(from)` fast-forward so a month view in 2030
does not iterate every occurrence since 2020. Simple rules can be jumped analytically by
INTERVAL arithmetic; complex BY\* combinations fall back to iteration.

**Concrete shape.** `value/recur.rs` already records that "structured decoding into typed
rule parts is deferred to a future addition", so this is that addition rather than a
bolt-on. Layout per naming-002 (a module owning types is `foo.rs` beside `foo/`) and
naming-005 (one file per family):

```
src/recur.rs          IcalRecurRule and its part enums, RFC 5545 3.3.10 + RFC 7529
src/recur/civil.rs    proleptic Gregorian arithmetic, private, no deps
src/recur/expand.rs   IcalRecurExpand (the Iterator) and IcalRecurOccurrence
```

Names follow naming-006 with Domain `Ical`: `IcalRecurRule` and `IcalRecurOccurrence` are
pure data so they take no verb (naming-008); `IcalRecurExpand` performs the action, so it
carries one; errors mirror their parent (naming-009), giving `IcalRecurRuleError`. No
crate-root re-export (naming-004): consumers write `ical::recur::IcalRecurExpand`.

**The expander is timezone-free, which resolves the seam question better than either
option first offered.** RFC 5545 defines expansion on the local wall-clock time of
DTSTART: `FREQ=DAILY` on a `TZID=Europe/Paris` start means the same local time every day,
and a `Z` start is just the case where local is UTC. So expansion never needs an offset.
The iterator therefore yields **civil** date-times, and converting an occurrence to an
absolute instant is a separate caller-driven step. Offsets, tzdata and the invalid or
ambiguous local times around DST transitions all move to that conversion boundary, out of
the crate entirely. Open decision 1 in §8 is answered by deleting the question.

**Termination is a correctness requirement, not a detail.** A rule whose limiting parts
can never match (`FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=30`) would search forever for a next
occurrence. The iterator caps consecutive empty periods and ends rather than hanging,
matching what dateutil and rrule.js do. This is tested explicitly.

### P0-9 landed, 2026-08-08

The rule and the expander ship; `RDATE`/`EXDATE`/`RECURRENCE-ID` composition (scope item
3) does not, and stays P0-9's remainder. What exists in ical-rs today:

```
src/recur.rs               IcalRecurRule, its part enums, IcalRecurDateTime, the parser
src/recur/civil.rs         proleptic Gregorian arithmetic (Hinnant), private
src/recur/expand.rs        IcalRecurExpand, the lazy Iterator
tests/rfc5545_recur.rs     the 42 worked examples of RFC 5545 3.8.5.3, transcribed
```

All seven frequencies, every `BY` part with its expand-or-limit behaviour per frequency
(including the two notes governing `BYDAY` ordinal scope), `BYSETPOS`, `WKST`, and the
RFC 7529 `RSCALE`/`SKIP` parts decoded (a non-Gregorian scale is refused by expansion
rather than mis-expanded). 92 tests green, clippy and rustfmt clean.

**It shipped unconditional, not behind a feature.** The plan budgeted `recur` as a
default-off feature so it could never cost the default build anything. It is dependency
free, so released ical-rs 0.1.0 declares no such feature and exports `recur` like any
other module: the app enables nothing and the risk the gate protected against (a
`recur` that starts wanting chrono or a bundled tzdb) has to be watched at review
instead.

Three things worth knowing when the Android side consumes it:

- **`IcalRecurOccurrence` was not needed.** The iterator's item is `IcalRecurDateTime`,
  a civil date-time. Occurrence *end* is `DTSTART + DURATION`, which the caller already
  holds, and `RECURRENCE-ID` belongs to the override composition that has not landed.
  Adding a struct to carry one field the caller supplies would have been ceremony.
- **`UNTIL` is compared civilly.** RFC 5545 requires `UNTIL` in UTC when `DTSTART` is
  zoned, so a caller with a `TZID` start converts the bound into that zone before
  expanding. Documented on the field; left unconverted it is off by the offset.
- **Windowing is `skip_while` + `take_while`.** The `starting_at(from)` fast-forward is
  still the later optimisation it was filed as. Empty periods are skipped whole, so even
  `FREQ=SECONDLY` with a `BYMONTH` gate walks days rather than seconds, but a long-lived
  daily rule still costs one step per occurrence since its `DTSTART`.

## 4. Phase 1: refactor onto pimdir

**P1-1. Repo and identity.** Rename to `pimalaya/android`, package `org.pimalaya`,
applicationId `org.pimalaya.android`. Free to do cleanly since nothing is published.

**P1-2. Dependency refresh.** Every Pimalaya crate to current released versions, drop
path and git patches where possible, mirroring the CLI alignment work
(io-pim-discovery instead of pimconf, io-http 0.3, pimalaya-stream 0.1). Verify in the
nix devshell and via `:app:runDebug` on device.

*Done, 2026-08-08.* Everything is a released crate except io-pimdir and io-replica,
which stay pinned to git: this app needs `sql::ALL` from the first and the placement
sort key from the second, and neither is in a published version yet. vcard-rs moved to
0.2 with the crate, whose renames the tree had already absorbed.

**P1-3. CardStore to pimdir.** Replace the bespoke SQLite store with io-pimdir. The
replica/membership concepts map directly: a replica is an item keyed `(collection,
link_id)`, membership is the set of collections sharing a `seq`, and the staged-edit path
becomes the pimdir action queue. Contacts functionality must be unchanged on device at
the end of this step; nothing else moves until it is.

## 5. Phase 2: the two-axis merged view

Supersedes `docs/merged-view.md` stages 3 and 4 in cross-account behaviour only. The
replica/membership/link model and the "merged is a view, not a storage model" invariant
are unchanged and remain correct.

**P2-1. The row model.** One row per logical item per account, carrying the **account**
(a facet, never merged away), the **collections** within that account rendered as tags,
and an **affinity marker** when another account holds something that looks the same.
Within an account, collections collapse to one row via the shared `seq`, which pimdir
already provides through `items_by_link`.

**P2-2. Filtering.** Two independent, composable filters: by account and by collection.
No filter shows everything. This replaces navigation with filtering, the same pivot
`merged-view.md` already made for addressbooks.

**P2-3. Affinity and manual merge.** Promote the duplicate remover from an app-bar tool
to an inline signal. It already groups on normalised email, phone (trailing digits) and
full-name equality with Ignore, Merge and Link verbs, and dismissals already persist.
Extend it: cross-account UID equality becomes an affinity signal rather than an automatic
collapse; for mail the signal is Message-ID equality.

**P2-4. Conflict resolution is preserved verbatim.** Three-way merge against the staged
base, per-field alternative chips, present-vs-absent as a pickable alternative, conflict
mode showing only disagreeing fields, "Resolve conflict" save. It generalises to calendar
unchanged once P0-7 lands, and does not apply to mail (bodies are immutable; only flags
and membership move, and those merge trivially).

## 6. Phase 3: mail

**P3-1. Rust core.** `rust/src/mail/` mirroring the contacts client shape: a `MailClient`
dispatching over IMAP, Graph, Gmail and JMAP, with `convert` modules normalising each
backend to RFC822 plus `message/rfc822` meta v1. Enumeration is a QRESYNC UID and flag
spine with an opaque checkpoint, envelopes fetched only for unknown handles.

**P3-2. Sync.** Mail joins the per-account WorkManager background sync. Spine first,
bodies by policy, using pimdir's `level` ladder (0 probed, 1 meta, 2 full): meta for all,
full on open, with a per-account "keep bodies" toggle for offline reading.

**P3-3. UI.** Message list (sorted by the P0-2 sort key), message view (mail-parser,
plain and HTML bodies, attachments opened via FileProvider), composer with contacts
autocomplete reading the app's own contacts.

**P3-4. Send.** io-smtp, Graph `sendMail`, Gmail send. Queue locally through the action
queue so sends survive restarts.

**P3-5. Flags.** Document the semantic difference rather than hiding it: flags are per
folder on generic IMAP and per message on Gmail, JMAP and Graph. Present a derived state
(unseen if unseen anywhere, flagged if flagged anywhere), write to the selected
collection's placement.

## 7. Phase 4: calendar

**P4-1. Rust core.** `rust/src/calendar/` over ical-rs plus the temporal crate,
dispatching CalDAV (io-webdav rfc4791), Graph and Google. calendula-android's RFC 6764
discovery through io-pim-discovery is the onboarding reference.

**P4-2. Views.** Four, over one query layer:

| View | Query | Notes |
|---|---|---|
| Agenda | Sorted list forward from now, keyset paged | Reuses the mail and contacts list component |
| Day | Range query, one day | Hour grid, overlapping events side by side |
| Week | Range query, seven days | Hardest layout: column per day, shared hour ruler |
| Month | Range query, one month | Density dots per day, tap through to day |

Agenda ships first: it reuses the existing list component and proves the range query.

**P4-3. Range queries with recurrence.** See §6.3 below. This is a small, closed problem.

**P4-4. Recurrence semantics.** The real cost, and it is P0-9 plus edit-scope UX, not
query strategy. Edit scope ("this occurrence / this and following / all") maps to:
writing a `RECURRENCE-ID` override; splitting the rule (`UNTIL` on the original plus a
new event); or editing in place. All three must round-trip through ical-rs's faithful
editing without rewriting untouched bytes.

**P4-5. Conflicts.** Identical to contacts once P0-7 lands. Calendar-specific field model
additions: start, end, all-day, location, description, attendees, alarms, recurrence rule.

**P4-6. Timezones.** Render in the device timezone, store the original, never rewrite it
on a faithful edit. Make this an explicit test, since it is the classic source of
wrong-time bugs.

### 6.3 The range query, in full

A recurring event is stored **once**, so an index over its stored `DTSTART` cannot answer
"what happens in the week of 2027-03-01": the stored start is the *first* occurrence.
That is the entire mechanical problem, and it is solved by indexing one extra column
rather than by materialising anything.

Index `start` (first occurrence) and `until` (the rule's UNTIL, the last RDATE, or NULL
for infinite). A range query is then two arms:

```sql
-- non-recurring: ordinary range scan
SELECT ... WHERE recurring = 0 AND start BETWEEN :from AND :to
UNION ALL
-- recurring candidates: rules that could possibly touch the window
SELECT ... WHERE recurring = 1 AND start <= :to AND (until IS NULL OR until >= :from)
```

The second arm returns a handful of rows (a user has tens of recurring events, not
thousands), so the app expands only those in memory, in each event's own timezone,
applies EXDATE and RECURRENCE-ID, and filters to the window. No materialisation, no
horizon, no index churn on edit, correct for infinite rules.

"Expand" means exactly this: turning one stored rule into its concrete occurrence
datetimes. `FREQ=WEEKLY;BYDAY=MO,WE,FR` is one row and roughly 150 occurrences a year;
expansion computes them on demand for the window being drawn.

So the query side is a non-decision, and I was wrong to file it as one. What is genuinely
expensive is P0-9: RFC 5545 §3.3.10 has real depth (BYDAY with ordinals like `-1SU`,
BYSETPOS, WKST changing weekly expansion, BYMONTHDAY clamping on short months, COUNT
versus UNTIL, and a defined order of application across the BY\* parts), and the
timezone interaction is subtle: an event at 09:00 Europe/Paris recurs at 09:00 *local*,
which is a different UTC instant either side of a DST transition, so expansion must run
in the event's timezone and never in UTC. Public RRULE test corpora exist (python-dateutil
and rrule.js both ship extensive vectors); use one rather than inventing tests.

## 8. Open decisions

1. ~~**Offset-resolver seam** (P0-9). A trait the caller implements, or a plain closure
   `Fn(&str, NaiveDateTime) -> Offset`.~~ **Resolved by deleting the question**, and the
   shipped expander takes neither: RFC 5545 defines expansion on local wall-clock time,
   so no offset is ever needed. Occurrences are civil, and conversion to an instant is a
   caller-side step at the rendering boundary.
2. **Fate of the donor repos** (§1). Archive immediately after their content lands, or
   keep briefly for reference.
3. **Whether mail participates in affinity at all** (P2-3). Message-ID equality across
   accounts is real but rarer than contact duplication, and merging is meaningless for
   immutable bodies. It may be a display hint with no merge verb.

## 9. Milestones

Each is releasable and has an acceptance test.

- **M0 foundations.** P0-1 to P0-9. Acceptance: a pimdir store holds a calendar
  collection with meta v1; sorted and range queries return correct pages; ical-rs
  round-trips a three-way merge with vcard's rules; ical-rs's `recur` feature passes a
  public RRULE corpus including DST-crossing cases, terminates on an infinite rule under
  `take_while`, and adds nothing to the default-feature build.
- **M1 refactor.** P1-1 to P1-3. Acceptance: current contacts functionality unchanged on
  device, on current libs, on pimdir, with no bespoke store left. *Built (§13); the
  device half of the acceptance has not run.*
- **M2 two-axis view.** P2-1 to P2-4. Acceptance: contacts across two accounts show as
  separate rows with affinity; both filters work; manual merge and conflict resolution
  behave as before.
- **M3 mail read.** P3-1 to P3-3. Acceptance: two accounts (one IMAP, one Graph) list and
  open mail offline after a sync, in the unified view alongside contacts.
- **M4 mail write.** P3-4, P3-5. Acceptance: send, flag and move work offline-queued and
  reconcile on reconnect.
- **M5 calendar core.** P4-1, agenda and day views, P4-3 to P4-6. Acceptance: CalDAV and
  Graph events list and open; a recurring weekly event crossing a DST boundary shows the
  correct local time on every occurrence; an edit conflict resolves through the contacts
  form.
- **M6 calendar views.** Week and month, edit-scope UX. Acceptance: month view scrolls a
  year without jank; "this and following" splits a rule and round-trips byte-faithfully.
- **M7 release.** Store listing, screenshots, paid-service hooks (push subscription, our
  OAuth), F-Droid and Play builds.

## 10. Risks

- **Scope.** Three domains and four calendar views. Mitigation is milestone gating: M2
  ships before M3 starts.
- **The `recur` feature** is new code with subtle semantics (BY\* interaction order, DST
  transitions, short-month clamping). Time-box it and use a public test corpus rather
  than tests written from the RFC.
- **Feature creep into ical-rs.** `recur` must stay dependency-free and tzdata-free. If
  it starts wanting chrono or a bundled tzdb, that is the signal it should have been a
  crate after all; check this at review, not at release. It shipped unconditional rather
  than behind a default-off feature, so review is the only gate there is.
- **Affinity false positives.** Grouping on phone and name equality will occasionally
  propose merging two different people. Ignore already persists; keep merge always
  confirmed and reversible before push.
- **Binary size.** The stated goal is smallest and fastest. Every new dependency
  (temporal crate, tzdata, HTML rendering for mail bodies) needs a size budget check, not
  just a correctness check.

## 11. P1-3 progress, 2026-08-08 (evening)

The pimdir migration, in the shape that turned out to be right rather than the
one §4 assumed.

**The architecture does not change.** io-replica already runs in Rust while
storage is serviced from Java over a JNI upcall, and that stays. What changes is
the schema underneath, so the migration is narrower than P1-3 reads: the remote
half (`enumerate` / `fetch` / `push`) is untouched entirely.

**io-pimdir is taken without its `client` feature.** Android ships SQLite;
compiling rusqlite in would put a second engine in every ABI of a binary whose
first fixed decision is to be small. The crate contributes `sql` (the canonical
schema and every statement) and `codec`; execution stays on
`android.database.sqlite`. Dependency tree for it: io-replica and serde_json,
nothing else.

**The SQL crosses JNI rather than being transcribed.** `Native.pimdirSql()`
hands over all sixty statements as JSON; `PimdirSql` caches and serves them by
name. Transcribing them would work once and drift silently afterwards, and the
whole reason to depend on the crate is that it is the canonical copy. This
required `sql::ALL` upstream (io-pimdir, landed with its own Cairn change).

Landed:

```
android/client/.../PimdirSql.java   canonical SQL by name, plus schema() splitting
android/app/.../PimdirDb.java       the store: schema, FKs, WAL, objects/ beside it
android/app/.../PimdirBlobs.java    content-addressed bodies, temp -> fsync -> rename
android/app/.../PimdirAccount.java  the stable account id and collection namespacing
android/app/.../PimdirMeta.java     per-kind meta and sort keys (the consumer's half)
android/app/.../PimdirStorage.java  the three storage ops over items/bindings/objects
```

36 tests over the six, all Robolectric so they run the real platform driver.

Four things worth carrying forward:

- **The blob layer computes no hash.** The engine hashes a body on fetch and
  hands the value down; the store files bytes under the name it is given. An
  earlier draft of `PimdirBlobs` implemented FNV-1a-128 in Java, which would
  have written blobs no other reader could find and failed silently rather than
  loudly. (§12 revisits which algorithm that name is under.)
- **The account id is a UUID, not the email.** SPEC.md §9.2 rules out an id the
  user can rename, since it becomes part of every collection id it namespaces,
  and rules out an id containing the namespace separator, since `a` + `b/c` and
  `a/b` + `c` would be indistinguishable. A UUID satisfies both by construction.
- **A write that does not restate the sort key preserves it.** The reference
  write is a replace-all, so blanking the key on every upsert would reset the
  ordering of every item a sync touched. The upsert carries `sort_key` through
  and the `UPDATE` keeps the stored one when the write sends none.
- **`sources.checkpoint` is BLOB and the tables are STRICT**, so a TEXT bind is
  refused rather than coerced. Checkpoints are opaque bytes on both sides.

Not done at that point: the cutover. `OfflineEngine` still routed to
`OfflineStore` over `CardStore`, and `PimdirStorage` was not wired in. §12 and
§13 are that cutover.

## 12. The engine cutover, 2026-08-08 (late)

The sync engine now runs entirely on the pimdir store. `OfflineEngine` holds a
`PimdirStorage` instead of an `OfflineStore`, and `PhoneRemote` reads its patch
base through the same seam. Nothing in the sync path touches the `card` and
`membership` tables any more.

Three findings shaped it, each a defect in what §11 had landed:

- **The store declared a hash it did not use.** `store_meta.hash_algo` said
  `blake3` while every object name in it was SHA-256 hex, from
  `rust/src/store.rs`. SPEC.md §4.3 admits `blake3` or `sha256-128`, and §5
  requires lowercase base32 (RFC 4648, no padding) because the name is also a
  blob path component. Both halves were wrong, and both fail silently: another
  pimdir reader would verify every blob against the wrong algorithm and find
  nothing. The name is now `sha256-128` in base32, computed identically in
  `PimdirHash` and `store.rs::byte_hash`, with the same vectors pinned on both
  sides so a drift fails loudly.
- **Refcounts ignored the merge bases.** `bindings.base_object` is a reference
  like `items.object_hash` (SPEC.md §5, and io-pimdir's own `object_refs`
  counts all three), but the Java seam counted only the current object. A body
  that a base still named was therefore collected the moment the item moved past
  it. The foreign key catches that as a constraint failure, so the symptom is a
  crashing sync rather than a lost base, but only because the schema is strict
  enough to notice; a store without the key would have silently dropped what the
  next three-way merge diffs against.
- **The phone spoke is a source, not a collection.** §11 left `SOURCE` hardcoded
  to `server`, which would have made the phone twin a second collection holding
  a second copy of every card. It is now the `phone` source on the *same* item:
  the engine still names two collections (`phone:<url>` and `<url>`) and the
  storage maps that prefix onto `(collection, source)`. This is what `bindings`
  is for, and it makes cross-spoke propagation free rather than a second write,
  since a phone-won edit leaves the item diverging from the server's base by
  construction.

Also landed:

```
android/app/.../PimdirHash.java         sha256-128 in base32, the object name
android/app/.../PimdirCollections.java  the roster: collections rows per account and kind
```

`PimdirCollections` exists because a collection row has to be there before an
item can reference it, and nothing was creating one. It is fed from the three
places the app already learns an address book roster (onboarding, the sync
self-heal, and the built-in on-device book), and it is what the mail and
calendar rosters will reuse in P4.

`PimdirStorage` grew the driver's own reads beside the three storage yields:
`loadRow`, `loadConflict`, `loadConflicts`, `handlesBelowFull`,
`setConflictRevision`, `setConflictRemote`, plus the two quiet-path guards
(`pending`, `memberCount`) that let a phone pass with nothing to do cost
nothing. The captured remote body of a conflict lands in `items.conflict_object`
as an ordinary refcounted object, released when no source is conflicted over the
item any more.

19 storage tests plus 3 hash tests, all Robolectric.

## 13. P1-3 complete, and P4's store with it, 2026-08-08 (night)

The reads and the writes moved together, because they had to: the engine had
already stopped writing `card` and `membership`, so anything short of the whole
contacts path would have left the screens reading a table nothing filled. Mail
and calendar folded in the same pass, since the shape was proven by then and
their stores were the smaller half of the work.

**There is one store now.** `pimdir.db` plus its `objects/` directory holds
every account and all three domains, discriminated by `collections.kind`;
`events.db` and `mail.db` are deleted on first run and `cards.db` survives with
its card tables dropped, holding only what pimdir has no column for.

```
android/app/.../PimdirItems.java     item writes outside a sync: put, remove, replace
android/app/.../PimdirContacts.java  the contacts semantics on top of them
android/app/.../PimdirCollections.java  the roster, now shared by all three kinds
```

The translation, in the order it caused trouble:

- **Membership is placement.** A card in three books is three `items` rows
  sharing one `link_id` and therefore one `seq`, which is what the
  `membership` table encoded by hand. `stageMembership` is an insert and a
  staged delete, and the round trip that used to need explicit cancelling
  (`added` then `removed`) now cancels by construction.
- **Dirty is derived.** An item whose `object_hash` has moved past its server
  binding's `base_object` *is* a pending push, so the flag and the fact cannot
  disagree. A staged create is an item with no binding at all; a staged delete
  is `deleted = 1` on an item that still has one.
- **A pending create needs an origin.** io-replica sets `ReplicaOrigin` from a
  `Copy` mutation, and a placement written directly carries none, so the push
  adapter would have read "add this contact to a second address book" as a
  second upload on the account-level backends. The storage seam now derives it:
  a placement with no base whose `link_id` is bound in another collection of the
  same source names that collection as its origin. This is the old
  `originUrl` rule, computed from the schema rather than passed in.
- **The summary carries the list row.** `PimdirMeta`'s `text/vcard` blob is the
  SPEC.md §13 convention (`v`, `uid`, `fn`, `emails`, `size`) plus `phone`,
  `info` and `hash`, because the alternative is parsing a vCard per row at
  render time, which is the cost a summary exists to remove. `index` in
  `rust/src/project.rs` gained `emails` so the spec fields can be complete
  rather than truncated to the first address.
- **The calendar subscription switch is gone.** It defaulted to true and nothing
  ever wrote it, so it decided nothing; when a calendar picker exists it belongs
  beside the address books' switches, in the app's own state.

`CardStore` keeps its name and is now what its documentation says: the app's own
state about its books (the three switches, the account and backend id) plus the
merged view's link exceptions. It reads the display fields from `collections`
rather than holding a second copy, and a collection with no switch row takes the
defaults instead of vanishing from a listing.

Deleted: `OfflineStore`, `OfflinePhoneStore`, and the placement-codec half of
`rust/src/store.rs` (`placement`, `phone_placement`, `upsert_plan`,
`phone_upsert_plan`, `phone_drop_plan`) with its five JNI entries and its Java
wrappers. What they encoded, the membership-versus-card decision, is what the
pimdir schema expresses natively. `store.rs` keeps only what no table can be
read for: `push_plan`, `account_snapshot`, `retry_unguarded`. `byte_hash` went
with them, since every hash the app computes is now Java's `PimdirHash`.

105 Robolectric tests and 81 Rust tests green; `:app:assembleDebug` builds.

**Not verified on a device.** Nothing here has run against a real server or a
real Contacts provider, and the acceptance test for M1 is precisely that
contacts behave unchanged on a device. The three-way merge, the phone spoke's
projection and the CardDAV If-Match quirk retry are the places where a green
build proves least.

### The first device run, and what it caught

Every contact rendered as its id. **There are three writers of a contact
summary, and only one of them had been moved to the new convention**: the app's
own `PimdirContacts.save`, but not `OfflineEngine.fetchedItem` (the sync path)
nor `mutateEdit` (the staged edit), both of which still wrote the raw
`Cards.indexCard` output, whose display name is `name` where the convention says
`fn`. Every synced card therefore had an empty `fn`, an empty `uid` and an empty
`hash`, so the list fell back to the id, the A-to-Z ordering was absent, and
UID-based grouping across accounts silently stopped matching. All three now go
through `PimdirMeta.contact`, and the two that write a body also write the sort
key beside it.

The lesson is the one SPEC.md §13 states and this missed anyway: a summary's
shape is a contract between *every* writer of a collection and its readers, so
adding a field to it is not a change to one method. The unit tests did not catch
it because they exercised the writer that was correct.

**A summary is written, never derived**, which makes this worse than a normal
regression: nothing re-fetches a card whose body has not changed, so the bad
summaries would have persisted for the life of the store. `repairSummaries`
rewrites them from the bodies on launch, once, and is a single query over the
summaries when there is nothing to do. This is the case io-pimdir's
`set_sort_key` is documented for, "a store written before its kind had a
convention".

Mail and calendar showing nothing is unrelated and unchanged: neither has a sync
entry point beyond pull-to-refresh on its own tab (`MailList.setUp`,
`CalendarList.setUp`). The calendar round also skips any account that is not
CardDAV, and the mail round needs a password, since the IMAP client
authenticates with PLAIN only, so an OAuth account fetches nothing.

## 14. Connecting an account, per domain, 2026-08-09

The first device run of the folded store made the real gap obvious: mail and
calendar were empty because **there was no way to connect a mail or a calendar
account**. Every stored account was a contacts account, so `syncMail` re-ran
discovery on a contacts address and guessed at both the endpoint and the
credentials, and `syncCalendars` filtered contacts accounts for CardDAV-shaped
URLs and walked an address-book home looking for calendars. Both were the
placeholders §1b's demo slice admitted to; neither could work for an account
whose mail lives somewhere else, or behind a different token.

**One account is one identity; a domain is an axis of it.** An address covers
some of mail, contacts and calendars, each with its own server and credentials,
and the flow runs once per domain inside the one account.

The first cut of this got it backwards, as `1 account = 1 auth = 1 domain`, and
the reasoning was wrong in a way worth keeping: it cited JMAP as the case
*requiring* the split. RFC 8620 is one session resource behind one
authentication, with capability URNs saying which domains each account serves,
so three accounts there are three sessions to the same URL with the same token.
It is the clearest argument against. Splitting also fragments one identity at
the front door: every cross-domain question afterwards has to reassemble it, an
expired token becomes three repairs, and connecting calendars next month means a
second account rather than a step. The split even produced a live bug before it
was reverted, `accountFor(email)` returning whichever domain happened to be
first.

Multi-select is right here, because ticking two domains queues two flows rather
than creating two accounts.

The flow is now: **email, then domain, then the existing config and auth steps**.

- The first step asks for an email and nothing else. "Email, server or URI" made
  the user do the app's job; discovery does it, and manual server entry moved to
  where it belongs, as an option on the next step when nothing was found.
- The second step lists what the address actually offers, one option per domain,
  each naming the protocols behind it. Everything after it is scoped to the
  chosen domain, so the config step proposes only what can serve it.
- The address-book selection stays the contacts domain's step. A mailbox roster
  and a calendar roster are discovered by their own first sync, so those flows
  end at the credentials and land on their own screen.

What had to change underneath:

- **Discovery only ever searched CardDAV and JMAP**, so no mail or calendar
  service could be found however the flow asked. `search_caldav` (RFC 6764) and
  `search_autoconfig` (the ISP URLs, the ISPDB and the mailconf TXT redirect,
  which is where IMAP and SMTP come from) join the parallel sweep, now five
  mechanisms.
- **The provider fast-path no longer skips the sweep.** A Google or Microsoft MX
  match says how to sign in for contacts; only the sweep can say whether the
  same address publishes mail or calendars, which is the whole question the
  domain step asks.
- **An account holds one connection per domain** (`AccountConnection`), and the
  roster stays keyed by the address. Refreshing one domain's token rewrites that
  domain's connection alone, since another may hold a different token from a
  different consent. An account stored before this decodes as a contacts
  connection, which is all the app could connect then, so nothing has to be
  re-onboarded.
- **A credential already entered in the run is offered rather than asked for
  again**, when the next domain is on the same origin and the first was not an
  OAuth grant. Fastmail is then one app password typed once, three ticks and two
  offers accepted; Gmail is still three consents, because Google genuinely wants
  three scopes. The offer is scoped to the run: reusing what the user just typed
  is an offer, reaching into an account connected weeks ago would not be.
- **Manual entry is the domain's discovery result, hand-filled**, feeding the
  same auth step: a host and port for mail, a URL for CardDAV or CalDAV. A second
  wizard would drift out of step with the discovered path within two changes.
- **A TCP endpoint needed a scheme.** IMAP arrives as a host, a port and a
  security mode, and `ServiceConfig.url` is null for it, so the config item
  built a null base URL. Only implicit TLS is offered, because the IMAP client
  has no STARTTLS step and driving a `starttls` endpoint as implicit would
  connect in the clear rather than fail. SMTP is discovered and deliberately not
  offered: this app only reads mail, so the option would connect to nothing.

117 Robolectric tests and 81 Rust tests green; `:app:assembleDebug` builds.
Existing accounts keep working, as accounts covering contacts; connecting mail
or calendars onto one of them is the same flow with those domains ticked, and
merges into the account rather than making a second.

## 15. The write paths, 2026-09-03

M4 (mail write) and the two verbs M5 was missing (calendar create and delete) landed, so all three domains write. What this section records is what moved and what deliberately did not; the behaviour itself now lives in [cairn/spec/](../cairn/spec), which this repository adopted the same night and which is the current truth from here on. This document stays the plan of record for what is *not* built.

**Mail flags and deletion.** The IMAP session keeps the capabilities its authentication already returned, so the write verbs choose rather than guess: `MOVE` (RFC 6851) where the server has it, `COPY` plus a `\Deleted` marker where it does not. `LIST` carries its attributes now, so the trash and the sent mailbox are the ones the server marks (RFC 6154) rather than names matched against a word list per language. Nothing is ever expunged: without `UIDPLUS` an expunge is mailbox-wide and would take every message another client had marked.

The JMAP half is one `Email/set` per verb, over the three keywords RFC 8621 §4.1.1 maps and no fourth: `\Deleted` has no counterpart there, deletion being a move, so an account naming no trash fails rather than being handed a keyword nobody defined.

**Mail composition** is P3-4 with its scope cut to what one night could finish honestly: plain text, no attachments, no reply, no drafts, SMTP only. The message is composed in Rust (folding, RFC 2047 encoded words, quoted-printable) rather than in Java, for the reason every other document in this app is. The connection flow keeps the SMTP endpoint it had been discovering and discarding, which is the `AccountConnection` change: mail is the one domain whose server does not answer both ways.

**What is left of P3, in order of what a user notices**: reply and forward (the composer takes no seed), attachments (an outgoing `multipart/mixed` and an incoming part opened through a FileProvider), drafts (a mailbox the composer does not have), and JMAP submission (`EmailSubmission/set` creates the message as an `Email` first and names it, which is a different shape from handing bytes over; an account with nowhere to submit is not offered as a sender rather than offered and refused).

**Calendar create and delete** are the same push as the edit with a different precondition: a create guarded on the resource not existing, a delete on the ETag. A new entry is built from a bridge function that emits one component with the three properties RFC 5545 requires and nothing else, then parsed back before it is returned. P4-4's edit scope (this occurrence / this and following / all) is untouched and remains the real remaining cost of the domain.

**No longer true** (superseded 2026-09-08 by the io-pimdir engine port): both used to relist on every refresh, the newest 50 per mailbox and every calendar resource. Both now run the engine contacts run on, a mailbox enumerating on its modseq under QRESYNC and a calendar on its sync token.
