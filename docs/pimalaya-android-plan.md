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
[features] recur = []      default-off, dependency-free, independent of `parser`
src/recur.rs               IcalRecurRule, its part enums, IcalRecurDateTime, the parser
src/recur/civil.rs         proleptic Gregorian arithmetic (Hinnant), private
src/recur/expand.rs        IcalRecurExpand, the lazy Iterator
tests/rfc5545_recur.rs     the 42 worked examples of RFC 5545 3.8.5.3, transcribed
```

All seven frequencies, every `BY` part with its expand-or-limit behaviour per frequency
(including the two notes governing `BYDAY` ordinal scope), `BYSETPOS`, `WKST`, and the
RFC 7529 `RSCALE`/`SKIP` parts decoded (a non-Gregorian scale is refused by expansion
rather than mis-expanded). 92 tests green, clippy and rustfmt clean.

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
  device, on current libs, on pimdir, with no bespoke store left.
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
- **Feature creep into ical-rs.** `recur` must stay default-off, dependency-free and
  tzdata-free. If it starts wanting chrono or a bundled tzdb, that is the signal it
  should have been a crate after all; check this at review, not at release.
- **Affinity false positives.** Grouping on phone and name equality will occasionally
  propose merging two different people. Ignore already persists; keep merge always
  confirmed and reversible before push.
- **Binary size.** The stated goal is smallest and fastest. Every new dependency
  (temporal crate, tzdata, HTML rendering for mail bodies) needs a size budget check, not
  just a correctness check.
