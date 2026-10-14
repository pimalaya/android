---
cairn: change
id: java-domain-layout
part: phase 3 design
---

# Phase 3 design: engines (java-domain-layout)

Paths are relative to `android/app/src/main/java/org/pimalaya/` unless they start with `test/` (= `android/app/src/test/java/org/pimalaya/`) or `e2e/` (= `android/app/src/e2eTest/java/org/pimalaya/`, compiled in the test source set). Line numbers are of today's tree (after phase 2), verified.

## 0. Challenges to the plan's four goals (summary)

1. Stager vs pass: right, but there are THREE null-selected roles, not two. Besides "stager" and "remote pass" there is a "phone-only pass": a ContactEngine with a context and no account (App 108, SyncService 89, SyncRunner 214, MainActivity 3015/3073) and `CalendarEngine.phonePass` (CalendarEngine 98-102, all nulls). Removing it is what makes goal 4 worth doing (below).
2. WorkerPool: MailPool and CalendarPool are the same thing (same failure collection, ordering, slot/session model, close); they differ in four small points listed in A2, one of them behavioural (lone worker inline). ContactEngine's inline pool (`pushAll`, ContactEngine 785-838) is NOT the same thing: fail-fast parallel map over one round, per-round transports, per-round executor torn down with `shutdownNow`. Folding it in would change what lands on the server in a failing round. Leave it. (ContactPool is a contact-grouping model, not a worker pool.)
3. SyncCoordinator: one class for "MainActivity and BackgroundJob" would carry dock, fill, Wi-Fi and first-sync state the background never has, i.e. a null-selected role again. Two classes in `sync`: `SyncPasses` (SyncRunner + RemotePass merged, headless, blocking; used by BackgroundJob, the e2e harness and the coordinator) and `SyncCoordinator` (the foreground owner out of MainActivity). BackgroundJob's orchestration (`check`, BackgroundJob 97-176) moves to `BackgroundCheck.run`.
4. Phone spoke: the contract is genuine (the same seven methods, same JSON shapes, PhoneRemote 66-316 / CalendarRemote 188-545). The driver code around it is small (about 50 lines per engine) and diverges in conflict settling and error policy. On its own that would not justify a base class; it does once the phone-only role must become its own class anyway (goal 1): one `PhonePass` base replaces two hand-rolled phone halves and the two null-built phone-only engines, and the calendar JVM test (CalendarMirrorTest) then covers the skeleton the untested contacts phone pass also runs.

## A. Goals: current state, target, what moves

### A1. Stager split from pass (no null-selected roles)

Current: one `PimdirEngine` subclass per domain does everything, role picked by null ctor args.
- ContactEngine ctor (ContactEngine 162-175): `(CardStore base, PimdirDb, PimalayaClient, Transport primary, Account account, Context context)`; null account = stager, null context = no phone spoke.
- CalendarEngine ctor (CalendarEngine 76-90): `(PimdirDb, PimalayaClient, Transport, Account, String accountId, String address)`; null transport/account = stager or phone-only.
- MailEngine ctor (MailEngine 108-113): `(PimdirDb, PimalayaClient, MailSession session, String accountId)`; null session = stager.
- PimdirEngine (pimdir/PimdirEngine.java) already holds both halves: storage yields + `serve` (224-299), mutations and flag vocabulary (485-638), and the remote half (Progress 51-101, hydrate 123-135, named 389-475, keepBoundNames 342-366, abstract enumerate/fetch/push 318/478/481, wire helpers 643-715). Its own Javadoc (18-31) describes the split.

Every construction site and its role:

| site | role | target |
|---|---|---|
| App.java:108 (PhoneQueue book consumer) | phone-only pass | ContactPhonePass |
| SyncService.java:89 | phone-only pass | ContactPhonePass |
| sync/SyncRunner.java:214 (`syncPhone`) | phone-only pass | ContactPhonePass |
| sync/SyncRunner.java:300 (`engine`) | server pass (+ its phone passes) | ContactEngine |
| MainActivity.java:3015 (`showOnPhone`, with progress) | phone-only pass | ContactPhonePass |
| MainActivity.java:3073 (`hideFromPhone`) | phone-only pass | ContactPhonePass |
| MainActivity.java:3186 (openConflict auto-resolve) | stager | ContactStager |
| contact/ContactWriter.java:99, 134, 151 | stager | ContactStager |
| contact/PimdirContacts.java:67 (base null) | stager | ContactStager |
| trash/DeletedItemsPage.java:263 | stager | ContactStager |
| calendar/CalendarEngine.java:99 (`phonePass`) | phone-only pass | CalendarPhonePass |
| sync/RemotePass.java:548 | server pass | CalendarEngine |
| MainActivity.java:2014 `calendarEngine(email)`, used by calendar/EventView 392, 1512, 1683, trash/DeletedItemsPage 268 | stager | CalendarStager |
| MainActivity.java:2009 `mailEngine(email)`, used by mail/MessageCompose 263 (MailSubmit), mail/MessageView 227 (MailReader), 622 (stageFlag), 759 (stageDelete), trash/DeletedItemsPage 259 | stager | MailStager |
| MainActivity.java:2526, 2592; sync/RemotePass.java:289, 322, 361, 390, 415 | pass (session) | MailEngine |
| e2e/E2eDevice.java:298 / 413 | stager | CalendarStager / MailStager |
| test/contact/ContactEngineDavCreateTest 141, ContactEngineGoogleTest 112, ContactEngineGraphFetchTest 114 | server pass, context null | ContactEngine (drop the ctx arg) |
| test/contact/ContactEngineServeTest 49, test/DeletedItemsTest 74 | stager | ContactStager |
| test/calendar/CalendarMirrorTest 210 (`syncPhone`) | phone-only pass | CalendarPhonePass |
| test/calendar/CalendarPoolTest 185, CalendarDavWriteTest 117, CalendarTriageTest 56 | server pass | CalendarEngine (unchanged) |
| test/mail/MailMoveTest 429 | stager | CalendarStager |
| test/DeletedItemsTest 228, test/mail/MailMoveTest 171, MailStoreTest 599, MailEngineTest 61, StorePerformanceTest 384 | stager (mutate/serve/held) | MailStager where only storage is used; keep MailEngine where the test also pushes/steps through the same instance |
| test/mail/StorePerformanceTest 392, 433; MailBodiesTest 460 | pass faked at the engine/client (session null) | MailEngine, unchanged (phase 6) |

Target, package `pimdir`:

```java
/** The storage half of a driver: load/lookup/write yields and staged mutations. */
public abstract class Stager implements OfflineDriver {
    public final PimdirStorage offline;
    public final Locks locks;
    protected final PimalayaClient client;
    protected Stager(PimdirDb pimdir, PimalayaClient client);
    public final String serve(String yieldJson);          // moved verbatim from PimdirEngine
    /** Any yield but load/lookup/write; a stager answers "Unsupported engine yield". */
    protected String remote(String op, JSONObject yielded, long parsed) throws JSONException;
    protected JSONObject lookup(JSONArray links) throws JSONException;   // hook, as today
    protected void applied(JSONArray effects) throws JSONException;      // hook, as today
    protected void staged(String collection);                            // hook, as today
    public void mutateFlags/mutateRemove/mutateMove/mutateCopy/mutateEdit/mutateAdd(...)  // as today
    public static final String SEEN, ANSWERED, FLAGGED, DELETED;
    public static boolean has(JSONArray, String); public static JSONArray withFlag(JSONArray, String, boolean);
    public static final class Clock { ... }  // with page/returned bookkeeping; PimdirEngine.Clock still resolves (inherited member type)
    protected static String error(String); protected static String error(Exception); protected static Integer status(Exception);
}
public abstract class PimdirEngine extends Stager {   // the remote half, API unchanged
    @Override protected String remote(String op, JSONObject yielded, long parsed);  // enumerate (+named, clock), fetch, push, held; else super
    // Progress, progress, step, advance, tells, domain(), hydrate, downloads, named, keepBoundNames,
    // listingsNamed, abstract enumerate/fetch/push, held, result, stringsOf, isGone, isPreconditionFailure, expiredToken
}
```

Domain stagers (each `public final`, ctor `(PimdirDb, PimalayaClient)`):
- `contact.ContactStager`: `void edit(String url, String handle, String vcard)` (today ContactEngine.mutateEdit 371-379, via a package-private static `ContactStager.edit(Stager on, ...)` that ContactEngine's conflict settling also calls); `staged` = ContactEngine 386-391 (via a package-private static the pass shares).
- `calendar.CalendarStager`: `staged` = CalendarEngine 254-259. No accountId (only `urlOf`, a push concern, used it).
- `mail.MailStager`: `stageFlag`, `stageDelete`, `Deletion` (MailEngine 1237-1294, `Deletion` stays referenced as `MailStager.Deletion`), `applied` = onWrite (MailEngine 1226-1232), and what MailReader needs: `context()`, `scratch()`, `sources(MailSession on, mailbox, handles, dir)` (MailEngine 406, 452-462, 1206-1223, the body moved to a package-private static both classes call). MailEngine keeps the one-arg `sources` test seam (phase 6).
- `DeletedItems.restore(PimdirEngine, ...)` (pimdir/DeletedItems 281) takes `Stager`.
- MainActivity `mailEngine(email)`/`calendarEngine(email)` become `mailStager()`/`calendarStager()`; the email argument was only an accountId no stager used.

Engines after the split: `ContactEngine(CardStore base, PimdirDb, PimalayaClient, Transport primary, Account account)` (context dropped, the phone pass built from `pimdir.context()` as CalendarEngine 89 already does), `CalendarEngine` and `MailEngine` ctors unchanged in shape but never built with null remote args in main code. Do NOT add `requireNonNull(session)` to MailEngine in this phase: tests fake at the engine with a null session (StorePerformanceTest 392/433, MailBodiesTest 460); phase 6 removes those seams.

Side effect: the intra-package cycle CardStore -> PimdirContacts -> ContactEngine -> CardStore (phase2-design C "left for later") is gone: ContactStager needs no CardStore.

### A2. WorkerPool

Current: mail/MailPool.java (351 lines), calendar/CalendarPool.java (236), both used only by sync/RemotePass (313, 536) and MainActivity (fill pools 304, 2502-2632).

| aspect | MailPool | CalendarPool | target |
|---|---|---|---|
| sizing | `sizeOf(Account)`: Graph 4, JMAP 4, IMAP 3, Gmail 2 (78-89) | `SIZE = 3` (34) | caller passes size; `MailEngine.sessionsOf(Account)` and `CalendarEngine.WORKERS = 3` keep the numbers in their domains |
| executor | static cached daemon, "pimalaya-mail" (69-75) | same, "pimalaya-calendar" (37-43) | one static cached daemon executor, "pimalaya-worker" (a process resource, like PimdirDb; java-010 note in tasks.md) |
| lone worker | always submitted (215) | runs on the calling thread when one worker (173-179) | inline when one worker, for both (behaviour change for mail, see below) |
| per-item driver | pool builds `engines.on(session)` per mailbox and adds `remoteSoFar` in finally (235-242) | step adds its own remote (55, RemotePass 559) | step gets `AtomicLong remote` (calendar shape); mail callers build the engine inside the step, same construction count |
| landed callback | pool calls `landed(done, total)` after each mailbox, failed or not (243-246) | caller counts in the step's finally (RemotePass 563-568) | caller counts (calendar shape); same timing (after each key, failed or not) |
| run numbering | `join(session, n)` / `join(session, 0)` around a worker's loop (229, 249, 311-315) | none | optional `Joiner<S>`; mail passes `MailSession::join`, calendar null |
| failures | ordered by key, unopened slot error for untaken keys, worker `get` failure into unopened (253-267) | identical (181-195) | identical |
| sessions | slot 0 borrowed, others lazily opened outside the lock, kept across runs, closed on `close` except borrowed (166-173, 318-350) | identical | identical |
| one run at a time | `synchronized run` (188) | same (130) | same |
| cancellation | none | none | none |
| store lock | never held by the pool; engines take `locks.store` per yield (asserted MailPoolTest 194, CalendarPoolTest 125/215) | same | same |
| `together` | static, one run inline else submitted (279-301) | none | kept on WorkerPool |
| log | "mail pass label: N mailboxes on..." (270) | "calendar pass ..." (198) | caller passes the full label; outcome says "N keys" (debug log wording only) |

```java
package org.pimalaya.sync;
public final class WorkerPool<S extends AutoCloseable> implements AutoCloseable {
    public interface Opener<S> { S open() throws Exception; }
    public interface Step<S> { void run(S session, String key, AtomicLong remote) throws Exception; }
    public interface Joiner<S> { void join(S session, long run); }
    public static final class Failure { public final String key; public final Exception error; }
    public static final class Outcome { public final List<Failure> failures; public Exception failure(); }
    public WorkerPool(int size, S first, Opener<S> opener, Joiner<S> joiner);
    public int size();
    public synchronized Outcome run(String label, List<String> keys, Step<S> step);
    public static List<Outcome> together(List<Callable<Outcome>> runs);
    @Override public void close();
}
```

Package `sync`: after A3 every user is in `sync` (SyncPasses, SyncCoordinator). MailPoolTest and CalendarPoolTest stay in their packages and import `sync.WorkerPool` (tests are outside LayeringTest), because they drive package-private engine members (CalendarPoolTest 185 `inner.enumerate`).

The one behavioural difference (lone worker inline, adopted from calendar): a mail pool run with one key or size 1 now runs on the caller's thread (Host.io for fill/bodies, the pass thread for passes) instead of a pool thread the caller blocks on. Effects: the same thread waits either way; an interrupt of the caller (`io.shutdownNow`, MainActivity 769) now reaches the work itself rather than turning into an "unopened" failure while the worker carries on. Decided (orchestrator, 2026-10-14): lone worker inline for both, recorded in tasks.md.

### A3. SyncPasses and SyncCoordinator

Current, re-located by name (finding 2's ranges are stale):
- MainActivity fields 295-330 (`syncing`, `foreground`, `filling`, `fillPools`, `fillStopped`, `bodies`, `replanBodies`, `bodiesLeft`, `bodiesTally` 325, `syncObservers`); onCreate wiring 380-411; lifecycle hooks 973-1023 (onResume: phoneReturn 986, wifi watch 994-998, fillMail 1000; onPause 1004-1022); background return 1027-1073.
- Dock progress model 1405-1843: `syncObserver` 1405, `startSync` 1444, `setSyncing` 1453, `showSyncDock` 1469, `renderSyncDock` 1495, `syncRows` 1537, `syncLog` 1584, pass state 1593-1644, `resetSyncPlan` 1647, `planSync` 1669, `planSteps` 1704, `weightOf` 1732, `syncShow` 1743, `syncAccount` 1757, `syncStep` 1773, `syncAdvance` 1791, `syncCollections` 1814; `isSyncing`/`syncHeld`/`observeSync` 1845-1865.
- Entry points: `syncRemote` 1872, `reportSync` 1901, `syncMail` 1923, `syncCalendars` 1963, forwards 1987-2015, `syncContacts` 2863, `syncAll` 2891.
- First sync: `syncConnected` 2030, `domainOf` 2040, `firstSyncIfOwed` 2058, `firstMail` 2099, `firstContacts` 2143, `firstCalendars` 2167; `firstOfMailboxes` 4031-4092 (`applyFilter` 3993 stays until phase 4 and calls it).
- Fill and bodies: `fillMail` 2219, `fillStep` 2229, `fillHost` 2270, `planBodies` 2331, `windowedRows` 2395, `owed` 2418, `bodyHost` 2451, `bodiesAllowed` 2484, `downloadBodies` 2494-2547, `mailAccount` 2556, `listAll` 2572, `fillPool` 2610, `closeFillPools` 2624, `fillAllowed` 2635.
- Network: `online` 2640, `wifi` 2651/2656, `owedOnWifi` 2665, `waitForWifi` 2671, `unmetered` 2687, `wifiWatch` 2694, `wifiBack` 2717.
- Load more / release: `loadMore` 2737, `releaseWindow` 2799, `underSyncLock` 2834, `landingPanel` 2850.
- Phone mirror triggers: `phoneSyncedBooks` 2930, `reconcilePhone` 2940, `reconcileCalendars` 2957, `phoneReturn` 2976, `showOnPhone` 3009, `phoneStep` 3041, `phoneDock` 3048, `hideFromPhone` 3069.
- Passes: sync/SyncRunner.java (432) and sync/RemotePass.java (614); RemotePass takes a SyncRunner for token renewal (73, 223, 494). Two progress interfaces that differ by one method (SyncRunner.Observer 45-54, RemotePass.Progress 46-52).
- Background: BackgroundJob.check 97-176, `scope` 182-199, `arrived` 209-223 (tested by test/BackgroundCheckTest 113-122).

Target, package `sync`:

```java
/** Every remote and phone pass, headless and blocking: SyncRunner + RemotePass. */
public final class SyncPasses {
    /** What the passes read and write; phase 5's "services record", born here. */
    public record Parts(PimdirDb pimdir, CardStore cards, SecureStore accounts,
            MailStore mail, EventStore events, PimalayaClient client) {}
    public interface Progress extends PimdirEngine.Progress {
        void account(String email);
        void collections(PimDomain domain, int done, int total);
        default void accountRefreshed(AccountEntry updated) {}
    }
    public static final Progress HEADLESS;
    public SyncPasses(Context context, Parts parts, Progress progress);
    // names kept from SyncRunner/RemotePass, so call sites and e2e change type only
    public Outcome syncRemote(); public Outcome syncRemote(SyncScope scope);
    public Exception syncPhone(ContactEngine.Report report); public boolean syncPhoneCalendars();
    public List<BookEntry> phoneSyncedBooks();
    public MailPass mailPass(SyncScope scope, boolean listing);
    public Exception calendarPass(SyncScope scope); public Exception fetchCalendars(AccountEntry, SyncScope);
    public Exception firstOf(AccountEntry, MailSession, String collection);
    public Exception loadMore(AccountEntry, MailSession, String only);
    public Exception stepMail(AccountEntry, MailSession, String only, boolean first);
    public MailSession openMail(AccountEntry account);
    public List<AccountEntry> accountsFor(PimDomain domain); public String accountIdOf(String email);
    public List<PimdirCollections.Stored> collectionsOf(PimDomain domain);   // MainActivity 4105, used by planSync and screens
    public static final class Outcome { ... }  public static final class MailPass { ... }   // as today
}
```

`BackgroundCheck` gains the run (BackgroundJob 97-199 moved, `arrived` with it):

```java
public static Map<String, List<MailStore.StoredMessage>> run(Context context, SyncPasses passes, BooleanSupplier stopped);
static List<MailStore.StoredMessage> arrived(Map<...> before, Map<...> now, Map<String, String> floors);
```
BackgroundJob keeps `onStartJob`/`onStopJob`, the AppState flags and `takeSync` (BackgroundJob 76-95), builds `new SyncPasses(context, parts, SyncPasses.HEADLESS)` and calls `notify` per account with what `run` answered.

```java
/** The app's foreground sync: one pass at a time, the dock, first syncs, the fill. */
public final class SyncCoordinator {
    /** What only the activity does for a sync; implemented by MainActivity (layering, as phase 2's Owners). */
    public interface Screens {
        PimDomain shown();                    // the list on screen, null elsewhere (firstSyncIfOwed)
        SyncDock dock();                      // built in onCreate after the coordinator
        void syncing(boolean active);         // keep-screen-on flag + mailList.redrawFooter (1469-1476)
        void reload(PimDomain domain);        // reloadContacts / mailList / calendarList
        void renewed(AccountEntry updated);   // accounts cache (1426-1433)
        void bodiesChanged();                 // refreshDrawer (2549)
        void land(PimDomain domain);          // goDomain(landingPanel) (2036)
    }
    public SyncCoordinator(Host host, SyncPasses.Parts parts, Screens screens);
    public SyncPasses passes();
    // lifecycle, main thread
    public void resumed(); public void paused();          // foreground, wifi watch, fillMail; phoneReturn
    public void backgroundShown();                        // showBackgroundDock 1051
    // state
    public boolean isSyncing(); public boolean syncHeld(); public Integer bodiesLeft(String email);
    public boolean wifi(); public boolean online();
    // pulls, main thread
    public void syncAll(); public void syncRemote(); public void syncContacts();
    public void syncMail(String role); public void syncCalendars();
    // first syncs
    public void syncConnected(AccountEntry account); public void firstSyncIfOwed(PimDomain panel);
    public void firstOfMailboxes(Map<String, List<String>> taken);
    // the mail window
    public void fillMail(); public void replanBodies();
    public void loadMore(List<MailList.Step> steps);
    public void releaseWindow(List<String> emails, String date, Runnable done);
    public void underSyncLock(int failed, Runnable write);
    // the phone mirror
    public void reconcilePhone(); public void reconcileCalendars();
    public void showOnPhone(String url); public void hideFromPhone(String url);
}
```
Package-private helpers inside `sync`: `SyncProgress` (the dock model, 1593-1843 + `renderSyncDock`/`syncRows`/`syncLog`; implements `SyncPasses.Progress`; owns the `passState` monitor), `MailFill.Host`/`MailBodies.Host` implementations (2270-2483) as private members of the coordinator, the fill pools as `Map<String, WorkerPool<MailSession>>`.

Threading (java-010): the coordinator owns no executor. It runs work on `host.io` (ui.Host 19, owned and shut down by the activity, MainActivity 769) exactly where MainActivity does today, so passes, fill chunks and the screens' own io tasks keep one serial order. It posts with `host.postAlive` where today's code does and `host.main.post` where today's code does (1431, 1486, 1590, 1767 and the other `main.post(this::renderSyncDock)`, 2704); switching those to the guarded path is a separate one-line step, recorded as a deliberate change. `WorkerPool`'s executor is the process's (static). `interactive` (MainActivity 195) stays the activity's. PhoneQueue's executor stays App's (App 103). BackgroundJob keeps its own thread (BackgroundJob 55-66).

What stays in MainActivity: building the coordinator (where 380-411 is), `Screens` implementation, the lifecycle calls (`resumed`/`paused`), `afterBackgroundRun` (1027-1045: notifications, account cache, list reloads; reads `isSyncing()`), `backgroundEnded` (1063-1073, calls `firstSyncIfOwed(shown)`), drawer pills (`syncPill` 1246 rendering, data from `bodiesLeft`), `applyFilter` (phase 4), and one-line forwards for the screens' `host.x` calls (MailList, ContactsList, CalendarList, FilterPage, WindowPicker, MessageView, MessageCompose, AccountSettings, OnboardingFlow) until phase 4 hands screens the coordinator.

LayeringTest: one ALLOWED change, `sync` gains `"ui"`. Reason: the coordinator drives the sync dock widget (`ui.SyncDock`) and posts through `ui.Host`; `ui` depends on nothing, so the edge points down. `MailList.Step` is reached through `sync -> mail`, already allowed.

### A4. Phone spoke contract

Current duplication (verified side by side):
- `syncPhone`: ContactEngine 244-284 vs CalendarEngine 204-236. Same: available, collection lock, quiet path (`changed`, `offline.pending`, `count == memberCount`), STAGE_PHONE, `offlineSync`, `hydrate`, settle, re-exchange + hydrate. Different: probes take the book URL in PhoneRemote (66, 76, 98) and the spoke id in CalendarRemote (188, 199, 225); settling (contacts: `loadConflicts`, STAGE_RESOLVE, always resolve by `Cards.mergeCardChanges` + `phone.read` + `setConflictRevision`, always re-exchange, 271-280, 341-361; calendar: `triage`, re-exchange only if one settled, 229-232, 165-191); error policy (calendar wraps every phone pass in `phoneSide` swallowing RuntimeException, 145-151; contacts propagate, and SyncRunner.syncPhone 212-222 stops at the first failing book).
- Yield dispatch: ContactEngine 421-422, 541-542, 724-732 vs CalendarEngine 289-290, 334-336, 379-385 (fetch through `keepBoundNames`, push with STAGE_PROJECT step + `advance` per item). Contacts also tallies phone pushes (731).
- `downloads` (ContactEngine 295-298, CalendarEngine 244-247), `staged` phone no-op (391, 256), `applied` (contacts tally 393-402; calendar `ingested` 261-267).

Target, package `pimdir` (no gate change: phone collections are already a pimdir notion, PimdirStorage 61-116; putting it in `phone` would need a new `phone -> pimdir` edge against phase 2's sibling rule):

```java
/** A phone provider as a pass's remote, keyed by the spoke collection id. */
public interface PhoneSpoke {
    boolean available(String spoke);
    boolean changed(String spoke);
    int count(String spoke);
    JSONObject enumerate(String spoke) throws JSONException;
    JSONObject fetch(String spoke, JSONArray handles) throws JSONException;
    JSONObject push(String spoke, JSONArray changes, IntConsumer pushed) throws JSONException;
}
/** One collection's phone pass: the store reconciled with its spoke. */
public abstract class PhonePass extends PimdirEngine {
    protected PhonePass(PimdirDb pimdir, PimalayaClient client, PhoneSpoke spoke, String kind);
    /** Runs the pass under the hub's collection lock; answers whether it wrote into the store. */
    public boolean sync(String hub) throws JSONException;
    /** Settles the spoke's conflicts; answers whether to exchange again. */
    protected abstract boolean settle(String spoke) throws JSONException;
    protected void pushed(String spoke, JSONArray changes, JSONArray results) throws JSONException {}
    // final enumerate/fetch/push -> spoke (fetch through keepBoundNames; push steps STAGE_PROJECT and advances);
    // downloads -> false; staged -> nothing; applied -> ingested |= isPhoneCollection(effect.collection)
}
```
- `contact.ContactPhonePass extends PhonePass` (public final): ctor `(PimdirDb, PimalayaClient)`, builds PhoneRemote from `pimdir.context()`; `boolean sync(String url, ContactEngine.Report report)`; `settle` = ContactEngine 271-280 + `resolvePhoneConflict` 341-361 (needs `PhoneRemote.read`, kept off the interface); `applied`/`pushed` tally into the report (ContactEngine 393-402, 1071-1098, via a shared static on Report).
- `calendar.CalendarPhonePass extends PhonePass` (public final): `settle` = `triage(spoke) > 0`; `public static boolean pass(PimdirDb, String collection)` = CalendarEngine 98-102 + `phoneSide` 145-151 (the swallow stays). `triage` becomes a package-private static over (`Stager`-typed engine, client, progress) shared with CalendarEngine; CalendarEngine keeps `int triage(String)` delegating (CalendarTriageTest).
- PhoneRemote and CalendarRemote `implements PhoneSpoke`; PhoneRemote's probes take the spoke and derive the URL with `CardStore.serverUrl` (as its `enumerate` already does, 124).
- ContactEngine and CalendarEngine lose every `isPhoneCollection` branch and hold their phone pass: `syncBook` = `phone.sync(url, report)`, server, `phone.sync(url, report)` under the book lock (186-200); `CalendarEngine.sync` = `phoneSide`, server, triage, `phoneSide` (122-142). Each copies its `progress` onto its phone pass before running. CalendarRows.phonePass (154) forwards to `CalendarPhonePass.pass`, which removes the CalendarRows <-> CalendarEngine cycle (phase2-design C).

## B. Behaviour invariants and how the target keeps them

1. Lock order: sync semaphore (`Locks.takeSync`, tryAcquire) -> collection lock (`Locks.collection(kind, id)`, reentrant) -> store monitor (`locks.store`). Evidence: startSync 1444-1451 and BackgroundJob 80 before any pass; syncBook 187-199 then syncPhone 249-283 re-entering the same lock; CalendarEngine 123-141 then 210-235; serve holds the store monitor only around storage yields (PimdirEngine 238-261, 354, 401). Kept: PhonePass.sync takes `locks.collection(kind, hub)` with the same key (contacts: book URL, CONTACT; calendar: collection id, CALENDAR), on the same thread, so reentrancy holds; `serve` moves verbatim into Stager.
2. The store monitor is never held across a network call (MailPoolTest 194, CalendarPoolTest 125/215 assert it). Kept: WorkerPool takes no lock; serve unchanged.
3. Phone passes never take the sync semaphore (PhoneQueue 18-23 doc, showOnPhone 3005-3006, phoneReturn 2971-2974). Kept: ContactPhonePass/CalendarPhonePass take only the collection lock.
4. Foreground serial order on Host.io: passes, first syncs, fill chunks (one chunk per task, 2253) and the screens' io tasks share one thread, so a pull waits at most one chunk (2210-2216). Kept: the coordinator posts to `host.io`, owns no executor.
5. Fill and bodies never take the sync lock; they check `syncing`/`foreground`/`wifi()` each step (`fillAllowed` 2635, `bodiesAllowed` 2484). Kept verbatim.
6. Callback timing: `syncAccount(null)` before `startSync` (1878); `resetSyncPlan` then `setSyncing(true)` (1448-1450); `planSync` on io before the pass (1885); `progress.account(null)` at a pass's start (RemotePass 108, 243); `collections(domain, 0, n)` before the first lands (311, 530); STAGE_SERVER before the listing round trip (286, 500); on completion, main thread: `setSyncing(false)` (releasing the semaphore, 1453-1456) then reloads then the error dialog then `waitForWifi`/`fillMail` (1888-1891, 1940-1948, 2913-2924); `showSyncDock(false)` posts `firstSyncIfOwed(screen)` (1486). Kept: the coordinator keeps each body verbatim; `Screens.reload` stands where the list reloads were, in the same order.
7. Error reporting: one account failing never blocks the others, first failure reported (SyncRunner 193-199, RemotePass 130-145, 253-256); 401 renews once and retries (SyncRunner 183-191, Session.call 382-392, openMail 212-225); a refused mailbox is skipped unless all are (RemotePass 327-342); `syncAll` shows one dialog, contacts first (2919-2922); per-collection pool failures logged, the first reported (571-574). Kept: SyncPasses is the two classes merged with bodies unchanged.
8. Phone error policy: calendar phone failures are swallowed per pass (CalendarEngine 145-151), contacts' propagate and SyncRunner.syncPhone stops at the first failing book (212-222), SyncService counts an IO exception (101-104). Kept: the swallow lives in CalendarEngine.sync and CalendarPhonePass.pass; ContactPhonePass.sync throws.
9. Ingest signals: SyncService marks `contactsIngested` when the report's local sets are non-empty (96-99); CalendarSyncService marks `calendarsIngested` when the pass wrote a phone-collection effect (91-93 via CalendarEngine 261-267); phoneReturn compares both stamps (2976-3004). Kept: Report and PhonePass.ingested compute the same predicates.
10. Hooks: a staged write to a book or calendar queues its phone pass, a phone-collection write does not (ContactEngine 386-391, CalendarEngine 254-259); move/copy also notify the target (PimdirEngine 535, 548); every mail write, staged or synced, fires `onWrite` (MailEngine 1226-1232); a stager tallies nothing (`tally` null outside syncs, ContactEngine 72-77, 395). Kept: domain stagers and engines share the hook bodies through package statics; engines still stage their own conflict resolutions through `this`, so pass-time tallies are unchanged (do not route an engine's mutations through a separate stager).
11. No "downloading" step over a phone spoke (ContactEngine 295-298, CalendarEngine 244-247). Kept: PhonePass.downloads is false.
12. Progress during a pass: the phone passes of a book/calendar report through the same observer as its server pass, under the same domain (SyncRunner 299-304; RemotePass 555). Kept: each engine copies `progress` onto its phone pass.
13. Calendar pool progress: counts only while one calendar runs (`alone`, RemotePass 588-602). Kept verbatim in SyncPasses.
14. Fill pools: kept between fill steps, closed when the loop ends (2256) or after a failed body download (2540-2543); one run at a time per pool. Kept: WorkerPool keeps `synchronized run` and lazy slots.
15. Cancellation: there is none inside passes; `io.shutdownNow` at destroy (769) interrupts; BackgroundJob checks `stopped` between domains (132, 168); `pushAll` aborts its round on the first failure (836). Kept; `stopped` becomes a BooleanSupplier into `BackgroundCheck.run`.
16. Two network predicates: the foreground's `wifi()` requires an active network and not metered (2656-2662); the background's `listing` only checks not metered (BackgroundJob 155-157). Kept apart (do not unify in this phase; note it).
17. `MergedFilter.of(context, domain)` returns the App-registered shared instance (account/MergedFilter 107-119), the same objects MainActivity caches in `filters` (226, 3953): the coordinator may call `MergedFilter.of` directly with no behaviour change.

## C. Phase 8 candidates found (logic Rust has or should decide)

- Backend concurrency: `MailPool.sizeOf` Graph/JMAP/IMAP/Gmail (mail/MailPool 78-89) and `CalendarPool.SIZE` (34): the Rust Backend knows the backend.
- Mail backend branching in orchestration: `RemotePass.loadMore` (380-385) and `stepMail` (457-463) on `isGoogle`, `MailBodies.downloads` role policy as the switch.
- Refusal rule over a status Java re-reads: `MailEngine.refused` (298) and RemotePass 327-342.
- Conflict triage loops: ContactEngine 218-233 + 341-361 and CalendarEngine 165-191 load conflicts, call a Rust merge per row, stage the result: one Rust offline op could settle a collection.
- Sync report re-tallied in Java from write effects (ContactEngine.Report 119-155, `tallyPushes` 1071-1098) while `offlineSync` already returns a SyncReportJson that Java only logs (215, 268).
- Identity naming per SYNC §4 in Java: PimdirEngine `named` (389-475, NAMING_BATCH 369) and `keepBoundNames` (342-366).
- Write read-back when a DAV server answers no ETag: ContactEngine `landed` (1245-1256), CalendarEngine `landed` (517-532).
- Per-backend fetch/push caches and batching (ContactEngine `deltaCards`/`accountDeltas`/`graphCards` 87-109, `fetchGoogle`/`fetchGraph` 589-670, `pushGoogle`/`pushRound` 848-1049): already in plan section 3.

## D. Ordered steps (one editing agent at a time; each leaves `:app:assembleDebug :app:testDebugUnitTest` green, test count never lower, LayeringTest green, `nm` symbol diff empty)

| # | step | kind | covered by | new test worth adding |
|---|---|---|---|---|
| 1 | `pimdir.Stager` out of PimdirEngine: serve, Clock, storage hooks, mutations, flag vocabulary, error helpers; PimdirEngine extends it and overrides `remote(op, ...)` with the enumerate/fetch/push/held arms | member move, multiset modulo the split switch | PimdirEngineTest, ContactEngineServeTest ("teleport" unsupported op), every engine test | none (pure move) |
| 2 | `contact.ContactStager`; repoint ContactWriter 99/134/151, PimdirContacts 67, MainActivity 3186, DeletedItemsPage 263; `DeletedItems.restore(Stager, ...)`; ContactEngineServeTest 49, DeletedItemsTest 74 | code | PimdirContactsTest, DeletedItemsTest, ContactEngineServeTest | none |
| 3 | `calendar.CalendarStager`; `calendarStager()` on MainActivity; EventView 392/1512/1683, DeletedItemsPage 268, E2eDevice 298, MailMoveTest 429 | code | CalendarDavWriteTest, MailMoveTest, DeletedItemsTest | none |
| 4 | `mail.MailStager` (stageFlag, stageDelete, Deletion, onWrite hook, MailReader's needs); `mailStager()`; MessageView, MessageCompose/MailSubmit, MailReader, DeletedItemsPage 259, E2eDevice 413; repoint tests that only stage; keep MailEngine where a test also pushes or steps | code | MailEngineTest, MailMoveTest, MailStoreTest, DeletedItemsTest, BodyLossRacesTest | none |
| 5 | `pimdir.PhoneSpoke`; PhoneRemote and CalendarRemote implement it; PhoneRemote probes take the spoke | code | CalendarMirrorTest | none |
| 6 | `pimdir.PhonePass` + `calendar.CalendarPhonePass`; CalendarEngine delegates its phone side; `CalendarRows.phonePass` forwards; CalendarMirrorTest 210 repointed | code | CalendarMirrorTest, CalendarTriageTest, CalendarPoolTest | none (CalendarMirrorTest exercises the shared skeleton) |
| 7 | `contact.ContactPhonePass`; ContactEngine delegates, loses the context arg and the phone branches; App 108, SyncService 89, SyncRunner 214, MainActivity 3015/3073 | code | ContactEngine*Test (phone pass skipped there as today: no Android account) | none possible without a contacts provider fake; device smoke test |
| 8 | `sync.WorkerPool`; `MailEngine.sessionsOf`, `CalendarEngine.WORKERS`; RemotePass and MainActivity fill repointed; MailPool/CalendarPool deleted; pool tests repointed | code (one recorded behaviour change: lone worker inline) | MailPoolTest, CalendarPoolTest | sync/WorkerPoolTest: a lone worker runs on the caller's thread; the joiner sees the run number then 0; an unopened slot's error lands on the keys no worker took |
| 9 | `sync.SyncPasses` = SyncRunner + RemotePass (one Progress, `Parts` record, `collectionsOf`); MainActivity, BackgroundJob, E2eDevice repointed | merge (bodies verbatim) | e2e compile, every pass test | none |
| 10 | `BackgroundCheck.run` (BackgroundJob.check + scope + arrived); BackgroundJob keeps JobService, flags, notify; BackgroundCheckTest repointed | move | BackgroundCheckTest | none |
| 11 | `sync.SyncProgress`: the dock model out of MainActivity (1405-1843 minus `isSyncing`); `sync -> ui` in LayeringTest with its reason | move + code | SyncStepsTest | sync/SyncProgressTest (Robolectric for strings): the bar never moves back across sections; a count overtaken by a worker is dropped; rows list passed/running/next in plan order; a missed section weighs one and grows the whole |
| 12 | `SyncCoordinator` skeleton: Screens, syncing/startSync/setSyncing/observe, the five pulls, `underSyncLock`, `isSyncing`/`syncHeld`; MainActivity forwards | move | build + LayeringTest | none |
| 13 | first syncs into the coordinator: syncConnected, firstSyncIfOwed(domain), firstMail/Contacts/Calendars, firstOfMailboxes | move | FirstSyncTest | FirstSyncTest case for the owing filter if extracted as a pure `FirstSync.due(owing, accounts, enabled)`: an account that is off keeps owing |
| 14 | fill, bodies, fill pools, Wi-Fi watch, loadMore, releaseWindow, `resumed`/`paused`, `bodiesLeft` into the coordinator | move | MailFillTest, MailBodiesTest | none (planBodies is phase 4's BodyPlan) |
| 15 | phone triggers into the coordinator: reconcilePhone/Calendars, phoneReturn, showOnPhone/hideFromPhone/phoneDock | move | build | none |
| 16 | optional, separate: the coordinator's `main.post` uses become `postAlive` (java-010) | deliberate change | build | none |
| 17 | tasks.md: deviations (two sync classes, three roles, pushAll kept, pool inline change, `sync -> ui`, first record), phase 8 list from C; device smoke test owed | docs | | |

Order constraints: 1 before 2-7; 5 before 6-7; 8 before 14; 9 before 10-15; 11 before 12; 12 before 13-15. Steps 1-7 and 8 are independent of each other.

## E. Risks and device smoke tests

Risks:
- Step 1 touches `serve`, the hottest path; split it so the switch arms move byte for byte and verify with the line multiset.
- Server engines stop serving phone-collection yields (step 6-7). This relies on a sync of collection C yielding remote ops for C only (today's code dispatches on the yield's collection, ContactEngine 421/541/724). Make the server engines throw on a phone collection, so a wrong assumption fails loudly in tests rather than writing to a server.
- The contacts phone pass has no JVM test; steps 5 and 7 change it (probe arguments, Report and progress wiring). The device test is the only coverage.
- Report tallying now spans two driver instances (the engine and its phone pass) sharing one Report; a forgotten `progress` copy hides the phone lines in the dock.
- MailEngine tests that use one null-session instance both to stage and to push (MailEngineTest) need both classes; repoint, never weaken; ask the user if a test genuinely needs one instance for both.
- WorkerPool lone-worker inline changes the mail interrupt path (A2).
- Coordinator moves are large: keep each step a move, keep `main.post` vs `postAlive` exactly until step 16, keep the Wi-Fi callback's register/unregister pairing (994-998, 1007-1015).
- `Parts` is the first record in the app: D8 desugars it for minSdk 26; assembleDebug proves it.
- e2e sources compile in the unit-test source set (app build.gradle.kts 143): every rename must repoint E2eDevice 92-107, 152-214 and the e2e tests' `phone.remote.openMail`.

Device smoke tests (debug build, after step 15; also a quick one after step 7):
- Drawer sync over all domains: dock lines, per-account rows, bar never going back, one error dialog with a bad account.
- Pull to refresh in mail (filter and a role chip), contacts, calendar; on mobile data the mail pull sends the outbox and the dock says it waits for Wi-Fi, then runs when Wi-Fi returns.
- Connect an account: lands on mail, first mail sync, then contacts and calendar tabs run their first sync when opened; ticking a never-listed mailbox in the filter runs its first chunk.
- Background fill and body download after a first sync: glyph, drawer pills counting down, stops on leaving the app, resumes on return; Load more and window release (WindowPicker).
- OAuth token expiry renews for mail, contacts, calendar (and the drawer's account cache).
- Background check with the app closed: notification of new mail; refused while the app syncs and the reverse; account settings "sync busy".
- Phone mirror: contact edit reaches Contacts within a second; a Contacts-app edit comes back on return; turning a book's phone switch on shows the projection on the dock, off removes the account; same both ways for calendars; time zone change reprojects.
- Stagers: flag, delete, move a message; send a message; edit, split and delete an event; edit, merge and auto-resolve a contact conflict; restore mail, contact and event from deleted items.

### Critical files for implementation
- android/app/src/main/java/org/pimalaya/pimdir/PimdirEngine.java
- android/app/src/main/java/org/pimalaya/contact/ContactEngine.java
- android/app/src/main/java/org/pimalaya/calendar/CalendarEngine.java
- android/app/src/main/java/org/pimalaya/sync/RemotePass.java (+ SyncRunner.java)
- android/app/src/main/java/org/pimalaya/MainActivity.java
