# Phase 4 design: logic out of the UI (java-domain-layout)

Paths are relative to `android/app/src/main/java/org/pimalaya/` unless they start with `test/` (= `android/app/src/test/java/org/pimalaya/`) or `e2e/` (= `android/app/src/e2eTest/java/org/pimalaya/`). Line numbers are of today's tree (after phase 3), re-located by name. MainActivity is 3161 lines, OnboardingFlow 3406, 553 `@Test` methods today.

## 0. Challenges to the plan's phase 4 list (summary)

1. Most of OnboardingFlow's "planning" (ranking, resourceOf, sameSignIn/audience, endpointUrl/submissionUrl/asUser) is onboarding knowledge Rust's `account` module owns ("holds onboarding (discovery, OAuth)", rust/src/lib.rs). Phase 4 moves it out of the rendering class verbatim and pins what can be pinned; it does NOT grow a Java setup service. The whole of it is a phase 8 candidate (E1).
2. BodyPlan is no longer in the UI: phase 3 moved planBodies/windowedRows/owed into `sync/SyncCoordinator.java` 846-963. Moving it to `mail` still pays (store/prefs policy belongs to mail, and it becomes testable), but it is a sync-side step, not UI.
3. "Sections used by every card/row builder": several builders are deliberately different designs (FilterPage.row 260 tick/indent, AdvancedEditor.propertyRow 124 15/12sp monospace, SyncDock). Forcing them onto `Sections.row` changes pixels. Only identical-output builders move (OnboardingFlow's card rows into a shared `ui` row kit); the rest stay with a recorded reason. AccountSettings.switchRow (705) and LinkedSection.row (143) already use Sections.
4. "One dp/resolveColor" is nearly done: Ui 28/38 is the one implementation, Host 48/89, ContactForm 926/930 and Sections 181 forward to it. RecipientField 255 rounds (`Math.round`) where Ui truncates: unifying it changes widths by up to 1px, so it stays (recorded).
5. "One list adapter base for the four BaseAdapters": three (ContactsList 257, CalendarList 598, FilesList 581) are the same CardSections shape; MailList 954 is a paged layout with a footer (3 view types) and DeletedItemsPage 463 a "show more" list. Base for the three; the other two stay (recorded).
6. MailStore split and PimdirStorage split: deferred (section A9).
7. Screens reach siblings through MainActivity fields without naming their types (e.g. DeletedItemsPage `host.mailList.reload()`), which LayeringTest (textual, imports and qualified names) cannot see. Decoupling makes those edges visible; ALLOWED grows for the upper pages only (B3), never upward.

## A. Per area

Conventions for every area: screens keep rendering, dialogs and threading; extracted classes are package-private unless another package calls them; moved bodies verbatim unless a line says otherwise.

### A1. ui groundwork (shared by every later step)

Current: chrome and navigation live on MainActivity and screens call them through the concrete type: `show`/`showBack` 2569/2574, `openOverlay` 2610, `closeOverlay` 2621, `applyChrome` 2931, `hideKeyboard` 3008, `setFabEnabled` 876, `setAuthLoading` 822, `gateSave` 653, `showDomainTitle` 2209, `updateBarTitle` 2201, `setListBusy` 2342, `setSending` 2349, `showMailBadge` 2335, `reloadHome` 1045; executor `interactive` 184; static `item` 2385; `syncPill` 1147-1198; `bookLabel` 1861; panel constants 122-135; auth steps 147-162; request codes 167-172; the collection picker `target` 1785-1822.

Target, package `ui`:

```java
public abstract class Host extends Activity {            // existing; additions only
    public final ExecutorService interactive = Executors.newSingleThreadExecutor(); // moved from MainActivity 184
    public abstract void show(int panel);
    public abstract void showBack(int panel);
    public abstract void openOverlay(int panel);
    public abstract void closeOverlay(int panel);
    public abstract void applyChrome(int panel);
    public abstract void hideKeyboard();
    public abstract void setFabEnabled(int id, boolean enabled);
    public abstract void setAuthLoading(int buttonId, int progressId, boolean loading);
    public abstract void gateSave(boolean ready, int icon, int description);
    public abstract void showDomainTitle(int panel);
    public abstract void updateBarTitle(String title);
    public abstract void setListBusy(boolean busy);
    public abstract void setSending(boolean sending);
    public abstract void showMailBadge(int unread);
    public abstract void reloadHome();
    public <T> void background(Executor on, Background.Work<T> work, Consumer<T> done, Consumer<Exception> failed); // A1b
}
public final class Panels { public static final int CONTACTS = 0, CONTACT = 1, ADVANCED = 2, SOURCE = 3, MAIL = 4,
    CALENDAR = 5, COMPOSE = 6, MESSAGE = 7, EVENT_VIEW = 8, FILES = 9, ACCOUNT = 21, FILTER = 22, DELETED = 23; }
    // PANEL_AUTH 20 and PANEL_ROOT stay private in MainActivity
public final class Ui { ... public static void item(PopupMenu menu, int label, Runnable action);   // MainActivity 2385 verbatim
                        public CharSequence twoLine(String name, String email); }                    // bookLabel 1861 verbatim
public final class SyncPill { public static View of(Host host, boolean enabled, long stamp, Integer left); } // 1147-1198 verbatim
public final class Picker {   // target() 1785-1822 minus DefaultCollection, generic
    public static <T> void choose(Host host, int title, int none, T direct, List<T> offered, int preselected,
            Function<T, CharSequence> label, Consumer<T> open); }
public interface Linked<E> {  // what an item page asks of the Linked card (link.LinkedSection implements it)
    void section(Sections sections, E self, Runnable refresh);
    void dialog(E self, String title, Runnable closed); }
```

MainActivity keeps its bodies and gains `@Override`; during the transition `MainActivity.PANEL_X = Panels.X` aliases keep unconverted screens compiling, removed in the last decoupling step. `STEP_*` move to `setup.OnboardingFlow` (public, used by MainActivity and OauthFlow). `REQUEST_FILE_IMPORT`/`EXPORT` move to `file.FilesList.REQUEST_IMPORT` (5) and `file.FileActions.REQUEST_EXPORT` (6); `REQUEST_IMPORT`/`EXPORT` (2, 3) to `contact.VcfTransfer`; values unchanged, MainActivity.onActivityResult 1676-1690 dispatches on them.

`Picker.choose` keeps `picked[0] >= 0` (1816) and the preselected index; callers do `DefaultCollection.choice(...)` and pass `choice.direct`, `choice.offered`, `choice.preselected`.

A1b. Background-work helper:

```java
public final class Background {
    @FunctionalInterface public interface Work<T> { T run() throws Exception; }
    /** Runs work on the executor; done or failed run on the main thread through postAlive, dropped once destroyed. */
    static <T> void run(Host host, Executor on, Work<T> work, Consumer<T> done, Consumer<Exception> failed);
}
```
Host.background delegates. The executor is always passed (host.io, host.interactive, MailList.reads): the helper never owns one (java-010), so the io serial order (phase 3 B4) is unchanged. Logging stays at the call site (inside `work` or `failed`), so log lines keep their wording.

Tests: `test/ui/BackgroundTest` (Robolectric, a minimal Host subclass): done receives the work's value on the main looper after the executor ran; failed receives the thrown exception and done does not run; nothing runs when the activity is destroyed before the post drains. No test for Panels/Picker/SyncPill/twoLine (moves).

### A2. account/setup: SetupPlan, AccountCommit, guarded posts

Current (setup/OnboardingFlow.java): model types SetupOption 413-493, SubmitOption 503-564; pure decisions standardOption 1055, standardRank 1070, proprietary 1080, offers 1363, authKind 1386, firstOffered 1398, sends 1417, sharedPassword 1527 (reads setups), optionsFor 1559-1609, addProviderOptions 1625, fromProvider 1677, resourceOf 1715, authRank 1738, planAuthSteps 1805-1833, sameSignIn 1836, addScopes 2484, sameAuthorizationServer 2496, audienceOf 2508, submissionOptions 3257, URL helpers submissionUrl 3319, asUser 3340, endpointUrl 3356, hostOf 3388. Commit: commitConnections 2952-3032 (assembly 2955-2974), commitBooks 3160-3176, finishOnboarding 3187-3246 (store.connect loop 3207-3232).

Target:

```java
// setup/SetupOption.java, setup/SubmitOption.java: the two classes moved out verbatim, package-private
final class SetupPlan {   // package-private, no Android
    static SetupOption standardOption(PimDomain domain, List<SetupOption> options, String email);
    static int standardRank(PimDomain domain, SetupOption option, String email);
    static boolean proprietary(SetupOption option, String email);
    static int authRank(AuthMethod.Type type);
    static boolean offers(SetupOption selected, SubmitOption option);      // was (DomainSetup, SubmitOption)
    static SubmitOption firstOffered(SetupOption selected, List<SubmitOption> options);
    static boolean sameSignIn(SetupOption left, SetupOption right);
    static boolean sameAuthorizationServer(AuthMethod left, AuthMethod right);
    static String audienceOf(SetupOption option);
    static void addScopes(Set<String> scopes, String scope);
    static String resourceOf(ServiceConfig config, AuthMethod method);
    static boolean fromProvider(ServiceConfig config);
    /** planAuthSteps' grouping: the picked options in domain order, pooled by sameSignIn. */
    static List<List<PimDomain>> signIns(Map<PimDomain, SetupOption> picked);
}
// account/Endpoints.java gains (public, moved verbatim): submissionUrl(ServiceConfig), asUser(String, String),
// endpointUrl(ServiceConfig, String); OnboardingFlow.hostOf and Endpoints.host stay distinct (hostOf falls back to the url).
final class AccountCommit {   // setup, package-private; store work only, main thread as today
    record Connection(PimDomain domain, String baseUrl, String submitUrl, AccountCredential credential) {}
    static AccountEntry assemble(String email, List<Connection> connections);          // 2955-2974 (asUser on empty login)
    static AccountEntry persist(SecureStore store, AccountEntry connected);           // 3204-3232 connect + regrant merge
    static void books(CardStore cards, PimdirCollections collections, String email,
            List<Addressbook> books, Set<String> subscribed, boolean phone);           // 3161-3172
}
```
OnboardingFlow keeps DomainSetup and AuthStep (they hold views), the labels (protocolName 1752, optionLabel 1775, authName 1784 read resources), optionsFor and submissionOptions (they mix labels in; they call SetupPlan for the rest), and every network call. AuthStep is built from `SetupPlan.signIns` (same pooling, same order). E2eTarget 269/280/296-297 repointed to `Endpoints`.

Guarded posts: `host.main.post` at 396, 2303, 2692, 3009, 3025 and OauthFlow 279, 291, 439, 483, 497, 646, 667, 680 become `host.postAlive` (step 2, deliberate change, A11). The ad-hoc probe pools (2279, 2669: `newFixedThreadPool(n + 1)`) stay: parallel fail-collecting probes, not WorkerPool's keyed sessions; recorded.

Tests:
- `test/setup/SetupPlanTest` (pure JVM): two OAuth options with one authorization endpoint and one audience pool into one sign-in, a different issuer does not; manual entry never pools; password options pool on type, server and login only; `addScopes` splits on whitespace and keeps first-seen order without duplicates; `offers`: a Graph submission row is offered only beside Graph reading, an OAuth SMTP row is not offered beside a manual (password) reading, the none row always is; `firstOffered` skips none and manual rows; `standardRank`: a Google API option beats a ranked CardDAV one for the same auth, then OAuth beats bearer beats password. Built only from what :client lets :app construct today (`AuthMethod.oauthCodeGrant`, `AuthMethod.Type`, `Account`, null methods for manual).
- Not testable until phase 5 (ServiceConfig and password AuthMethod have no public constructor; adding one for a test is forbidden): optionsFor, resourceOf, endpointUrl, submissionUrl. `asUser` is strings only: a case in `test/account/EndpointsTest`. endpointUrl/submissionUrl stay covered by the e2e harness.
- `test/setup/AccountCommitTest` (Robolectric, FakeAndroidKeyStore): `assemble` puts asUser on a mail base and submit URL when the credential's login is empty and leaves other domains alone; submission absent when the connection carries none; `persist` onto an existing account keeps the domains it covered; a grant whose scope covers an earlier domain of the same client moves that domain onto it (one credential left); `books` subscribes the ticked books only and turns the phone flag on only when asked.

### A3. account deletion cascade

Current: AccountSettings.deleteAccount 893-953 (store, contacts, cards, mail, events, FirstSync, SenderName, BackgroundCheck, PhoneCalendars, filters through `host.forgetViews` = MainActivity 2529-2545, then reloads, phone purge on io 940-949, reconcileCalendars 952).

Target, package `setup` (it spans every domain; setup may reach them all):

```java
final class AccountRemoval {
    AccountRemoval(Context context, SyncPasses.Parts parts, PimdirContacts contacts);
    /** Removes an account's data and app state, main thread as today; answers its book URLs for the phone purge. */
    List<String> remove(String email);    // 896-930 + forgetViews 2529-2545 verbatim, same order
}
```
AccountSettings keeps: the sync-busy check 873, the dialog, `accounts.removeIf` 931 (the activity's list), reloads 932-934, the io phone purge 939-949 and `reconcileCalendars` 952. Order inside `remove` is preserved exactly (forgetViews and PhoneCalendars.forget before `store.remove`, as the NOTE at 908 requires).

Test: `test/setup/AccountRemovalTest` (Robolectric, real PimdirDb): after `remove`, SecureStore lacks the account; its contacts sit in the on-device book; its mail and events are gone; FirstSync, SenderName and BackgroundCheck answer as for an unknown account; the mail filter no longer hides its mailboxes; its default collection is forgotten; activation is back on; the answer lists its book URLs.

### A4. mail

Current: MessageCompose.send 214-293 builds the draft JSON on the main thread (229-245), applies the answered rule (247-256: no parent on Graph or Gmail), queues on io, stages ANSWERED on the parent after the post (283-291). MessageView reply 467-508, forward 515-533, prefixed 536, addressesOf 543, plain 554 (android.text.Html), quoted 564, when 573. SyncCoordinator planBodies 846-903, windowedRows 910-924, owed 933-963.

Target, package `mail`:

```java
final class MailReply {   // pure, no Android
    record People(String to, String cc) {}
    static People replyAll(String accountEmail, String fromAddress, String toHeader, String ccHeader); // 468-491
    static String prefixed(String prefix, String subject);
    static List<String> addressesOf(String header);
    static String quoted(String text);
    static String inReplyTo(List<String> thread); static String references(List<String> thread);       // 494-498
}
public final class MailSubmit { ...
    public record Draft(String from, String fromName, String to, String cc, String bcc, String subject,
            String body, String inReplyTo, String references) {}
    public void queue(Draft draft);                       // builds today's JSON (keys unchanged) with now() and messageId()
    /** Whether a reply marks its parent answered: Graph and Gmail keep no marker (phase 8). */
    static boolean marksAnswered(Account server);
}
final class BodyPlan {    // planBodies/windowedRows/owed moved from SyncCoordinator
    record Plan(MailBodies.Run run, Map<String, Integer> left, int planned) {}
    static Plan of(Context context, MailStore mail, List<AccountEntry> accounts, MailState state);
}
```
MessageCompose reads the fields into a Draft on the main thread (as the NOTE at 226 requires), `MailSubmit.queue(draft)` runs on io. `plain` stays in MessageView (Html). SyncCoordinator keeps `bodiesLeft`, the tally and the posts: `fillStep` calls `BodyPlan.of(...)`, then fills `bodiesLeft` from `plan.left` (remove when 0), `bodiesTally.expect(plan.planned)`, `postAlive(bodiesChanged)` (895-901), same order.

Tests:
- `test/mail/MailReplyTest` (pure): reply-all drops the account's own address in any case, a message the account sent answers its recipients, a cc address already in To is not repeated; `prefixed` keeps one "Re:" whatever its case; `addressesOf` drops display names and separators; `quoted` marks every line, a quoted line gaining ">" with no space; thread headers: last id is In-Reply-To, all ids space-joined are References.
- `test/mail/MailSubmitTest` extension (Robolectric, real bridge, as MailStoreTest): a reply Draft queued lands in the outbox with In-Reply-To and References in its composed source; `marksAnswered` false for Graph and Gmail bases, true for IMAP and JMAP.
- `test/mail/BodyPlanTest` (Robolectric, real MailStore and prefs): an account switched off plans nothing; junk and trash plan nothing and their owed keys are settled; mailboxes the filter shows come before the chosen-but-hidden ones; a mailbox kept whole plans every body; an owed body below the frontier is planned last; an owed key whose message is bodied is settled.

### A5. contact: ContactEditor

Current, MainActivity: openGroup 1582, openConflict 1598-1665, importContacts 1668, exportContacts 1694, visibleGroups 1707, addContact 1717-1750, booksOutside 1825 (dead), bookLabels 1841, createCopy 1879, mergeReplicas 1884-1914, performMerge 1921, mergeDirect 1935, openMergeForm 1953, openNewContact 1961, openMerged 1979-2029, closeContact 2054, openAdvanced 2065 (dead), manageBooks 2081-2127, saveContact 2135, newVcard 2144, cardIndex 2153, contactGroupOf 1542, fields `edit` 292, `form` 202, `pool` 278, `contacts` 193, `advancedEditor`/`writer`/`vcfTransfer` 260-266, updateSaveEnabled 643.

Target, package `contact`:

```java
public record ContactParts(PimdirDb pimdir, CardStore cards, PimdirContacts contacts, ContactPool pool,
        PimalayaClient client, List<AccountEntry> accounts) {}   // accounts: the activity's live list (as ContactPool today, MainActivity 311)
public final class ContactEditor {
    public ContactEditor(Host host, ContactParts parts, ContactForm form, Runnable reloadList);
    public EditSession edit();                 // the current session, replaced on every open/close
    public void openGroup(Group group);
    public void addContact(MergedFilter shown);
    public void mergeReplicas(List<Entry> replicas);
    public boolean openMerged(List<Entry> replicas);
    public void manageBooks(); public void close(); public void save(); public void openAdvanced();
    public void applySource(); public void closeAdvanced();     // AdvancedEditor's, for the screen table
    public void addField(); public boolean conflictResolved(); public String title(); public boolean placeable();
    CharSequence[] bookLabels(List<BookEntry> books); void createCopy(...); JSONObject cardIndex(String vcard);
}
final class ContactMerge {   // pure, no Android
    static List<Entry> candidates(List<Entry> replicas);                        // first replica per book, 1890-1895
    static boolean identical(List<Entry> replicas);                             // one hash, 1922-1931
    static List<Entry> doomed(List<Entry> replicas, Entry survivor, Function<Entry, String> ref); // 1938-1946
    static List<String> documents(List<Entry> replicas);                        // one per hash, 1989-1995
    static boolean[] checked(List<BookEntry> books, List<Entry> replicas, Map<String, Boolean> pending); // 2086-2104
    static Map<String, Boolean> pending(List<BookEntry> books, boolean[] checked);  // 2115-2123, local = !any
}
```
`VcfTransfer` gains `importContacts()`/`exportContacts()` (1668-1704) and `REQUEST_IMPORT`/`REQUEST_EXPORT`. `CardStore.subscribedBooks(PimdirCollections)` takes SyncPasses.collectionsOf's CONTACTS branch (SyncPasses 1017-1027 moved; SyncPasses delegates) so contact computes its books without sync. `DefaultCollection.shown(Context, String kind, List<Stored>)` (pimdir) takes MainActivity.shownDefaults 2551-2566 minus the collectionsOf call. MainActivity keeps `form.setOnRender`/`setTrailing` wiring (341-351), the trailing lambda reading `editor.edit()` at render. Dead `booksOutside`, `openAdvanced`, `hostOf` 3022 are deleted (recorded).

Tests: `test/contact/ContactMergeTest` (pure JVM, Entry built in-package): candidates keep the first replica of each book in order; `identical` true only for one hash; `doomed` skips the survivor's card and a card shared by two replicas once; `documents` one per distinct hash in order; `checked` prefers a pending state over membership; `pending` puts the on-device book on exactly when nothing is ticked. openConflict's auto-resolve stays a device test (it is phase 3 C's Rust triage candidate).

### A6. calendar: EventWrites, composeEvent, openEvent

Current: EventView.save 1413-1436 (decision: all vs scope prompt), save(scope) 1486-1575 (staging branches 1512-1556), scoped 1582, delete 1653-~1720 (branches 1683-1708). MainActivity.openEvent 1551-1580, composeEvent 1757-1778.

Target, package `calendar`:

```java
final class EventWrites {   // the staging branches, io thread
    EventWrites(PimalayaClient client, CalendarStager stager);
    record Save(String scope, String edited, String uid, EventStore.StoredEvent target,
            EventStore.StoredCalendar calendar, boolean isNew, boolean rewritten,
            EventStore.StoredConflict settling, Map<Integer, String> picks) {}
    void save(Save save) throws Exception;              // 1512-1556 verbatim
    void delete(String scope, String edit, EventStore.StoredEvent target, EventStore.StoredCalendar calendar,
            EventStore.StoredConflict settling, Map<Integer, String> picks) throws Exception;  // 1683-1708
}
// EventView gains: public void openStored(String collection, String linkId, Runnable gone)  (MainActivity 1551-1580)
//                  public void composeNew()  (composeEvent 1757-1778, dialog through ui.Picker)
public record CalendarParts(PimdirDb pimdir, PimalayaClient client, EventStore events) {}
```
The decision before the prompt (1414-1435: unreadable, read-only, unsettled, creating/non-series/unchanged resolution) stays in EventView (it reads view state). `new CalendarStager(pimdir, client)` per call, as `MainActivity.calendarStager()` 1429 does.

Test: `test/calendar/EventWritesTest` (Robolectric, real store and bridge, CalendarDavWriteTest's fixture): "following" on a series stages the new series' add then the master's edit, in that order; a new entry stages an add under its id; a conflict resolution with nothing edited stages the resolved source unchanged on the conflicted binding; deleting one occurrence stages an edit, deleting all removes the binding (the conflicted one when settling).

### A7. filters

Current, MainActivity: `filters` 211, filterOf 2405, filesFilter 2410, filesRoster 2421-2436, applyFilter 2447-2477, filterChanged 2479, filterRoster 2497-2523, forgetViews 2529 (goes to A3), shownDefaults 2551 (goes to A5), openFilter 2396 (stays: navigation).

`filterOf`/`filesFilter` vanish: MainActivity's map caches `MergedFilter.of`, which already answers the process-shared instance (account/MergedFilter 107-121), so `MergedFilter.of(host, domain)` / `MergedFilter.files(host)` at the call sites is identity-preserving.

Target, package `filter`:

```java
public final class Filters {   // non-UI owner of the rosters and the apply rule
    public Filters(Context context, SyncPasses passes, SyncCoordinator sync, MailStore mail, FileStore files,
            List<AccountEntry> accounts);   // > 4: the composition root's one call; or a FilterParts record
    List<FilterPage.Account> roster(PimDomain domain);        // 2497-2523
    List<FilterPage.Account> filesRoster();                   // 2421-2436
    void apply(PimDomain domain, MergedFilter kept, MergedFilter ticks, Runnable changed); // 2447-2477
    static Map<String, List<String>> taken(List<FilterPage.Account> roster,
            BiPredicate<String, String> kept, BiPredicate<String, String> ticks);         // 2448-2459
}
```
`apply` keeps the order: taken, `kept.apply(ticks)`, `sync.replanBodies()`, `changed.run()` (= MainActivity.filterChanged, which stays: it reloads the four lists), then the domain switch. Test: `test/filter/FiltersTest` (pure): only collections ticked now and not kept before are taken, grouped by account in roster order; nothing for the Files tab (null domain).

### A8. ui consolidation

- Adapter base: `ui.SectionsAdapter<T> extends BaseAdapter` over `CardSections<T>`: getCount/getItem/getItemId/getViewTypeCount(2)/getItemViewType/header view; abstract `rowView(T row, int position, View recycled, ViewGroup parent)`; `isEnabled` overridable with default `!isHeader` while ContactsList overrides it back to `true` (today it does not override: headers enabled, 257-300) and FilesList keeps its folder-header rule (608). MailList and DeletedItemsPage keep theirs.
- Rows: OnboardingFlow's domainRow 967-1034, tickRow 926, rowDivider 1037 and the result/book cards move verbatim to `ui.CardRows` (static builders over Host); MainActivity.accountCard 1065 uses `CardRows` only where its metrics are identical, else stays. FilterPage.row, AdvancedEditor.propertyRow, SyncDock stay (distinct designs).
- dp: delete the ContactForm 926/930 and Sections 181 forwards in favour of `ui.dp`/`ui.resolveColor` (pure); RecipientField stays.
- Dates: add `Dates.time(Context, long)` (MailList 949, CalendarList 478 verbatim), `Dates.dateTime` (DeletedItemsPage 452-459 verbatim), `Dates.dayMonth` (Birthdays 109 verbatim), `Dates.weekday/dayOfMonth/month/year` (CalendarList 373, 380, 448, 459 verbatim). DeletedItemsPage.stamp 355 is a store format: to `pimdir.DeletedItems` beside the retention it stamps. MailSubmit.now (RFC 5322) and PimdirSummary/MailDate formats stay (wire). No new tests (moves; DatesTest stays green).
- Background helper adoption: the ~45 screen sites whose shape is "work on an executor, then everything on the main thread" (MessageView 7, FilesList 7, FileActions 5, DeletedItemsPage 3, OnboardingFlow 3, AccountSettings 3, MailList 3, EventView 3, OauthFlow 2, VcfTransfer 2, WindowPicker, MessageCompose, LinkedSection, DuplicateReview, ContactsList, Birthdays, CalendarList 1 each). Sites that post from inside the work several times (FileActions.save 253-258, OauthFlow's loopback thread 646-680, MailList page reads with generation checks) keep their shape with postAlive; SyncCoordinator keeps its own.

### A9. MailStore and PimdirStorage splits: deferred

MailStore (2150) mixes outbox/pending rows and synced pages, and ~10 prefs files (INBOX_ONLY, TRASH, ACCOUNT_WIDE, ALL_MAIL, MARKING, LINKS_REPAIRED, SENT_ALIAS in MailStore; MailScope, MailWindow x2, SenderName, MailPoison, MailOwed, MailOffline). The split is a store change with no UI in it, touches the hottest paths (MailList pages, fill, bodies), and phase 5 reshapes the same seam (StoredMessage's two row shapes and a Flags value, finding 7); splitting now means doing the boundary twice. Defer to phase 5, recorded in tasks.md, with the prefs owner (`mail.MailPolicy`, file names unchanged) as its first step. PimdirStorage (2104) re-implements io-pimdir storage semantics that phase 8 decides whether to move to Rust (plan section 3): splitting it before that decision is churn on code that may be deleted. Defer to phase 8.

### A10. What stays in MainActivity (end state, ~1900 lines)

Composition root (onCreate builds stores, parts records, the coordinator, then screens, in that order), lifecycle (onResume/onPause/onDestroy/onNewIntent/onSaveInstanceState/onActivityResult/onRequestPermissionsResult, background return 928-974), navigation (flip/show/overlays, the Screen table 2667-2924, onBackPressed 705-739, auth navigation 495-616, goDomain 751), chrome (applyChrome, bars, FAB, setAuthLoading, gateSave), drawer (reloadHome, accountCard, about), permissions (askMirrors/askNotifications), insets 3071-3160, and the shells (B2): `SyncCoordinator.Screens` plus `SetupShell`, `LinkShell`, `filterChanged`, `reload`.

### A11. Deliberate behaviour changes (recorded in tasks.md)

- Unguarded background-to-main posts become `postAlive` (step 2): OnboardingFlow 396, 2303, 2692, 3009, 3025; OauthFlow 279, 291, 439, 483, 497, 646, 667, 680; Birthdays 121; VcfTransfer 87, 143; CalendarList 183; DuplicateReview 87; MailList 276 (MailState.onWrite is process-wide and outlives the activity), 375, 908, 1259; WindowPicker 88. Effect: an outcome landing after the activity died is dropped instead of raising a dialog on a dead window (BadTokenException) or writing through a dead activity. The one path that loses work: an OAuth redeem finishing after the activity was destroyed no longer persists the account through the dead wizard (OauthFlow 279/646 → onboarding.connect → finishOnboarding); today the io executor is shut down with `shutdownNow` (686-689) and the loopback socket closed (693), so that path mostly failed already; the user restarts the grant. Not changed: main-thread debounces (ListHeader 144, LinkedSection 286, MailList 279 inside the guarded post), `applyChrome`'s `main.post(firstSyncIfOwed)` 2934 and `onBackgroundEnd` 451 (backgroundEnded checks isDestroyed, 965).
- Nothing else changes behaviour; every other step is a move or a signature change.

## B. Host decoupling (the 20 TOLERATED screens)

### B1. Mechanisms

1. Generic shell on `ui.Host` (A1): navigation, chrome, executors, dialogs, toasts, dp/resolveColor, `screen`.
2. Constructor-passed collaborators: per-domain parts records (`ContactParts`, `CalendarParts`, `mail.MailParts(PimdirDb pimdir, PimalayaClient client, MailStore mail, FileStore files, ItemLinks links, SecureStore store)`, and `SyncPasses.Parts` for the upper pages), sibling screens of the same package, `MergedFilter.of` instead of filterOf.
3. Interfaces owned by the lower package, implemented by the object that has the behaviour (phase 2's Owner pattern): `mail.MailSync` implemented by SyncCoordinator; `file.FilesList.Origins` implemented by MessageView; `ui.Linked<ItemLinks.Endpoint>` implemented by LinkedSection; one-method needs as `Runnable` (contact and calendar pulls).
4. Small shells implemented by MainActivity where a screen needs several activity-only actions: `setup.SetupShell`, `link.LinkShell`.

```java
public interface MailSync {   // mail; SyncCoordinator implements (sync -> mail allowed)
    void syncMail(String role); boolean syncHeld(); boolean wifi(); boolean online();
    void loadMore(List<MailList.Step> steps); MailSession openMail(AccountEntry account);
    String accountIdOf(String email); void releaseWindow(List<String> emails, String date, Runnable done);
}
public interface SetupShell { // setup; MainActivity implements
    void showAuth(int step); int authStep();
    void askMirrors(Set<PhoneMirror> wanted, Consumer<Set<PhoneMirror>> done);
    void askNotifications(Consumer<Boolean> done);
    void reload(PimDomain domain); void filterChanged(); }
public interface LinkShell {  // link; MainActivity implements by forwarding to the screens
    void openMessage(MailStore.StoredMessage message); boolean openContact(String linkId);
    void openEvent(String collection, String linkId, Runnable gone); void openFile(String collection, String linkId); }
```
`SyncCoordinator.accountsFor`/`SyncPasses.accountsFor` (606-614) moves down to `SecureStore.accountsFor(PimDomain)` (reads the store fresh, as today); SyncPasses delegates. The construction order becomes: stores, `SyncCoordinator` (its constructor touches no screen, 137-158), screens. The coordinator's `dock` stays a supplier (built later, 414).

### B2. Per screen: MainActivity members used today and their replacement

Generic members handled by Host for all (not repeated below): findViewById/getString/getResources/getLayoutInflater/getDrawable/getTheme/getContentResolver/startActivity(ForResult)/isDestroyed (Activity), io/main/ui/dp/resolveColor/accentContrast/toast/showError/message/postAlive/screen (Host today), show/showBack/openOverlay/closeOverlay/applyChrome/hideKeyboard/setFabEnabled/setAuthLoading/gateSave/showDomainTitle/updateBarTitle/setListBusy/setSending/showMailBadge/reloadHome/interactive (Host after A1), `MainActivity.PANEL_*` → `Panels`, `MainActivity.item` → `Ui.item`.

| screen | other MainActivity members used today | replacement |
|---|---|---|
| contact/AdvancedEditor | edit, form | ContactEditor (ctor), `editor.edit()`, `editor.form()` |
| contact/Birthdays | none beyond Host | ctor type `Host` |
| contact/ContactsList | pool, contacts, accounts, syncContacts, shownDefaults, filterOf, openGroup, mergeReplicas, importContacts, exportContacts, onContactsScreen | ContactParts; `Runnable pull` (MainActivity passes `sync::syncContacts`); `DefaultCollection.shown`; `MergedFilter.of`; ContactEditor; VcfTransfer; `host.screen == Panels.CONTACTS` |
| contact/ContactWriter | edit, form, contacts, cardIndex, accountFor, pool, pimdir, client, createCopy, base, reloadContacts | ContactEditor (edit/form/cardIndex/createCopy), ContactParts (accountFor = loop over `parts.accounts()`), `Runnable reloadList` |
| contact/DuplicateReview | pool, base, reloadContacts, openMerged, mergeReplicas, contacts | ContactParts, ContactEditor, reloadList |
| contact/VcfTransfer | setListBusy, visibleGroups, reloadContacts, contacts, cardIndex, bookLabels, base, accountFor | Host, ContactsList (ctor), ContactParts, ContactEditor |
| calendar/CalendarList | client, eventView, syncCalendars, shownDefaults, filterOf | CalendarParts, EventView (ctor), `Runnable pull`, DefaultCollection.shown, MergedFilter.of |
| calendar/EventView | client, events, calendarStager, calendarList, linked, setAuthLoading, gateSave, updateBarTitle | CalendarParts (`new CalendarStager`), CalendarList (set after construction, same package), `ui.Linked` (ctor) |
| mail/MailList | messageView, wifi, syncMail, syncHeld, loadMore, showMailBadge, filterOf | MessageView (ctor), MailSync, Host, MergedFilter.of; static `labelOf(MainActivity…)` 843 → Host |
| mail/MessageCompose | mail, client, mailStager, accountsFor, messageView, mailList | MailParts (`new MailStager`, `store.accountsFor`), MessageView, MailList |
| mail/MessageView | mail, files, links, client, mailStager, mailEngine, openMail, online, fileActions, compose, mailList, linked, accountsFor, interactive | MailParts, MailSync (openMail, online, accountIdOf → `new MailEngine(pimdir, client, null, sync.accountIdOf(email))` as 1419), FileActions (ctor; mail -> file allowed), MessageCompose/MailList, ui.Linked; static `starOf(MainActivity…)` 644 → Host |
| mail/WindowPicker | mail, releaseWindow, dp, resolveColor | `open(Host host, MailStore mail, MailSync sync, …)`; `label(Host, …)`; Javadoc link 134 to MailSync |
| file/FileActions | files, filesList, REQUEST_FILE_EXPORT | FileStore (ctor), `Runnable changed` (= filesList.reload), `FileActions.REQUEST_EXPORT` |
| file/FilesList | files, fileActions, messageView, linked, filesFilter, REQUEST_FILE_IMPORT | FileStore, FileActions, `FilesList.Origins` (MessageView), `ui.Linked`, MergedFilter.files, `FilesList.REQUEST_IMPORT` |
| setup/AccountSettings | store, base, mail, events, contacts, accounts, accountFor, collectionsOf, forgetViews, reconcileCalendars, showOnPhone, hideFromPhone, phoneSyncedBooks, underSyncLock, isSyncing, bodiesLeft, syncPill, askMirrors, askNotifications, filterChanged, filterOf, reloadContacts, mailList, calendarList | SyncPasses.Parts + PimdirContacts + accounts list (ctor), SyncCoordinator (ctor; setup -> sync allowed), AccountRemoval, SyncPill, SetupShell (ask*, reload, filterChanged), MergedFilter.of, `WindowPicker.open(host, mail, sync, …)` |
| setup/OauthFlow | client, showAuth, STEP_OAUTH | PimalayaClient (ctor), SetupShell, `OnboardingFlow.STEP_OAUTH` |
| setup/OnboardingFlow | client, store, base, pimdir, accounts, accountFor, authStep, showAuth, STEP_*, syncConnected, reconcilePhone, askMirrors, askNotifications | Parts + accounts (ctor), SyncCoordinator, SetupShell, own STEP_* |
| filter/FilterPage | filterRoster, filesRoster, applyFilter, filterOf, filesFilter, filterChanged, fillMail, mail, files | Filters (ctor), MergedFilter.of/files, `Runnable changed`, `sync.fillMail` via Filters |
| trash/DeletedItemsPage | pimdir, client, accounts, collectionsOf, mailStager, calendarStager, mail, reloadContacts, calendarList, mailList, bookLabel | SyncPasses.Parts + SyncPasses (collectionsOf) + accounts (ctor), `new MailStager/CalendarStager/ContactStager`, `Consumer<PimDomain> reload` (= MainActivity.reload), `host.ui.twoLine` |
| link/LinkedSection | links, mail, messageView, openGroup, contactGroupOf, openEvent, filesList | ItemLinks + MailStore (ctor), LinkShell; implements `ui.Linked<ItemLinks.Endpoint>` |

Construction cycles are broken the way onCreate already binds OauthFlow late (333-337): a lambda or a package-private setter for same-package pairs (EventView/CalendarList, MessageView/MailList/MessageCompose, FileActions/FilesList), LinkedSection built before the item pages (it reaches them only through LinkShell, which MainActivity answers lazily). Field-initialized screens (FilterPage 214, DeletedItemsPage 217, AdvancedEditor 260, ContactWriter 263, VcfTransfer 266, AccountSettings 269) are built in onCreate before `setUpScreens` 353.

### B3. LayeringTest end state

- TOLERATED: deleted with its lookup and Javadoc mention; each decoupling step removes the entries it frees, the last one removes the map.
- ALLOWED changes (each downward):
  - `filter`: + `"ui"`, `"sync"`, `"file"` (Host/Sections/Panels; Filters takes SyncPasses/SyncCoordinator; filesRoster reads FileStore). Reason in a NOTE.
  - `trash`: + `"ui"`, `"mail"`, `"calendar"`, `"sync"` (Host; MailStager, MailStore.mailboxLabel; CalendarStager; SyncPasses.collectionsOf).
  - `link`: unchanged (`mail`, `contact`, `ui`, `pimdir`): LinkShell takes ids and a StoredMessage, no calendar or file type.
  - `mail`, `contact`, `calendar`, `file`, `setup`, `sync`, `ui`: unchanged.
- Result: no package references `org.pimalaya` (root) except through `R`/`BuildConfig`.

## C. Behaviour invariants (with evidence)

1. One serial io executor shared by screens and the coordinator (ui/Host 19; phase 3 B4): the helper takes the executor, never creates one; MessageView keeps `interactive` (MainActivity 178-184), MailList keeps `reads`.
2. Work that runs on the main thread today stays there: openConflict's bridge and store calls (1611-1643), mergeDirect's staged deletes (1945), openMerged's bridge calls (2001-2015), AccountRemoval's store cascade (AccountSettings 896-934; only the phone purge 940 is on io), commitBooks (3161-3173), finishOnboarding's store writes (3207-3232). Extracted services are synchronous and called from the same place.
3. Posts: after step 2, every background-to-main hop in screens goes through postAlive; main-thread debounces and 2934/451 keep `main.post` (A11).
4. Dialogs are built on the activity (Host), never the application context; pickers keep preselection and the `picked[0] >= 0` guard (1808-1818); manageBooks writes `pendingBookState` only on OK (2112-2124).
5. Back: onBackPressed order (705-739) unchanged; the Screen table's handlers point to the same behaviour on the new owners (contact: `editor::close`, `editor::save`; advanced: `advancedEditor::close`/`applySource` 2878-2894; composer `compose::close` 2805; event `eventView::save` 2838).
6. Edit session: replaced by a fresh EditSession on every open and on close (1650, 1962, 1981, 2055); the ContactForm trailing (344-351) and the contact chrome (2846-2856) read the current one at render through `editor.edit()`; `mergeSurvivor` armed only after a successful openMerged (1955-1957).
7. Save/restore: only FileActions and FilesList persist (679-683); `restore` after `setUp` (357-361); the first-run `OpenedCopies.clear` only without saved state (364). Wizard and editor state are not saved (rotation resets them, as today: no configChanges in AndroidManifest.xml 41-45).
8. Request codes keep their values (2, 3, 5, 6) and onActivityResult's dispatch (1676-1690); permission answers stay in MainActivity (1249-1278).
9. Filter identity: `MergedFilter.of` is the shared instance (MergedFilter 116-121), so reading it at call sites sees the same ticks MainActivity's cache saw.
10. Account sources: `accountsFor` reads SecureStore fresh (SyncPasses 606-614), `accountFor` reads the activity's list (620-627, refreshed by afterBackgroundRun 938-940): each caller keeps its source.
11. Adapters: ContactsList headers stay enabled (no isEnabled override today), Calendar headers disabled (624), Files folder headers enabled (608); view type counts unchanged.
12. PendingGrantTest builds MainActivity without onCreate (test/setup/PendingGrantTest 137) and constructs OauthFlow itself: OauthFlow's constructor must need only the activity and its field-initialized `client` (175).
13. JSON contract: the compose draft keeps today's keys and values (MessageCompose 231-242) when built from `MailSubmit.Draft`.

## D. Ordered steps (one editing agent at a time; each leaves `:app:assembleDebug :app:testDebugUnitTest` green, test count never lower, LayeringTest green with TOLERATED shrinking, `nm` symbol diff empty)

| # | step | kind | UI-heavy | tests |
|---|---|---|---|---|
| 1 | ui groundwork: Panels (+ aliases), STEP_* to OnboardingFlow, request codes to their owners, Host abstract chrome + `interactive`, Ui.item, Ui.twoLine, SyncPill, Picker, `ui.Linked` (LinkedSection implements) | moves + signatures | no | build |
| 2 | guarded posts (A11 list) | deliberate change | no (device: onboarding, OAuth) | build |
| 3 | `ui.Background` + Host.background (no adoption) | code | no | BackgroundTest |
| 4 | SetupPlan + SetupOption/SubmitOption out of OnboardingFlow; URL helpers to Endpoints; E2eTarget repointed | move | no | SetupPlanTest, EndpointsTest case |
| 5 | AccountCommit (assemble, persist, books) | move | no | AccountCommitTest |
| 6 | AccountRemoval (deleteAccount cascade + forgetViews) | move | no | AccountRemovalTest |
| 7 | `SecureStore.accountsFor`, `CardStore.subscribedBooks`, `DefaultCollection.shown`; SyncPasses/MainActivity delegate | move | no | DefaultCollectionTest case (one default per shown account) |
| 8 | ContactMerge + ContactEditor out of MainActivity (+ dead privates deleted, VcfTransfer import/export) | move + wiring | yes | ContactMergeTest |
| 9 | contact screens off MainActivity (ContactParts, pull, editor); TOLERATED −6 | signatures | yes | build |
| 10 | EventWrites; openStored, composeNew into EventView; calendar screens off MainActivity; TOLERATED −2 | move + signatures | yes | EventWritesTest |
| 11 | MailReply, MailSubmit.Draft + marksAnswered | move | no | MailReplyTest, MailSubmit cases |
| 12 | BodyPlan out of SyncCoordinator | move | no | BodyPlanTest |
| 13 | MailSync (coordinator implements), MailParts; mail screens off MainActivity incl. WindowPicker; coordinator built before screens; TOLERATED −4 | signatures + order | yes | build |
| 14 | file screens off MainActivity (Origins on MessageView, changed callback); TOLERATED −2 | signatures | yes | build |
| 15 | Filters + FilterPage off MainActivity; ALLOWED filter += ui, sync, file; TOLERATED −1 | move + signatures | yes | FiltersTest |
| 16 | setup screens off MainActivity (SetupShell, Parts, coordinator); PendingGrantTest repointed (types only); TOLERATED −3 | signatures | yes | build |
| 17 | trash and link off MainActivity (LinkShell); ALLOWED trash += ui, mail, calendar, sync; TOLERATED map deleted; Panels aliases removed | signatures | yes | build |
| 18 | SectionsAdapter for Contacts/Calendar/Files lists | code | yes | none (views) |
| 19 | Background adoption, one agent per package group (setup; mail + file; contact + calendar + trash + link) | code | yes | build |
| 20 | Dates additions + call sites; DeletedItems.stamp; dp forwards removed; CardRows for OnboardingFlow (+ accountCard where identical) | moves | yes (pixels) | DatesTest |
| 21 | tasks.md: deviations, A9 deferrals, A11 change, phase 8 list (E), device smoke tests owed | docs | | |

Order constraints: 1 before everything; 2 and 3 before 19; 4-6 before 16; 7 before 8-10 and 15; 8 before 9; 11-12 before 13; 13 before 14 (Origins sits on MessageView) and 17 (LinkShell forwards to the decoupled mail screens); 18-20 after 17. Verify moves with the line multiset (scratchpad/lines.sh twin) per file pair.

## E. Phase 8 candidates found

1. Setup planning (SetupPlan as a whole, optionsFor, submissionOptions, Endpoints.endpointUrl/submissionUrl/asUser): Rust's onboarding knows discovery, backends and OAuth; it should answer the connection plan for an address (options ranked, resource, sign-ins pooled, endpoints).
2. Answered marker (`MailSubmit.marksAnswered`: Graph and Gmail keep none) and `PimalayaClient.submitsOverSession` in MessageCompose.open 101: backend capabilities the Rust mail backend knows.
3. Reply and forward composition (MailReply, plain-text quoting, Re:/Fwd:, reply-all addressing): the Rust mail side composes messages already (composeMessage) and could build reply drafts from the stored source.
4. Event writes: `writesOverrides`/`writesExdates`/`writesEvents` per URL (EventView 1434, 1606; composeNew) are backend capabilities; EventWrites' branching (split → add + edit, resolution unchanged → stage as is) could be one Rust "stage edit" operation answering the writes.
5. Contact conflict auto-resolve (openConflict 1633-1647) and merge-by-hash decisions: with phase 3 C's triage loop.
6. Body download policy by role (`MailBodies.downloads`, BodyPlan's junk/trash skip): already listed in phase 3 C.
7. Store stamp formats (DeletedItems retention stamp, PimdirSummary stamps): with PimdirStorage's move decision.

## F. Risks and device smoke tests

Risks:
- Signature-change steps are wide and mechanical: one package per step, no logic edits mixed in, every replaced `host.x` checked against B2; the reviewer diffs member uses per file.
- Construction order (step 13 moves the coordinator before the screens; steps 9-17 move field-initialized screens into onCreate): a null collaborator crashes at launch, not in JVM tests (Robolectric builds MainActivity only in PendingGrantTest, without onCreate). Mitigation: keep the order list in the step brief; device cold launch after each of 9, 13, 16, 17.
- Edit session capture (C6): a lambda capturing an EditSession instead of reading `editor.edit()` shows a stale form after reopen.
- Guarded posts (step 2) can drop an OAuth completion after destruction (A11): recorded, device test of a full OAuth grant and a rotation mid-grant.
- Background adoption can reorder a toast or a spinner reset relative to the work if a site's posts are merged wrongly: adopt only exact-shape sites (A8).
- Adapter base: getView/isEnabled differences change taps on headers; covered by C11 and the smoke test.
- SetupPlan partial coverage until phase 5 (ServiceConfig not constructible from :app): the e2e harness and the onboarding smoke test are the guard.
- Tests must not be weakened: PendingGrantTest and E2eTarget are repointed by types only.

Device smoke tests (debug build), per UI-heavy step:
- 2: onboarding with password; OAuth browser grant (custom scheme and loopback); rotate mid-discovery and mid-grant (no crash, flow restartable).
- 8-9: open a merged contact, edit, save; resolve a conflict (form and auto-resolve toast); merge two contacts (identical: direct; different: form with survivor); manage books dialog (check/uncheck, save, local book when none); advanced editor and source; new contact (default book and picker); import and export vCard; birthdays and duplicates dialogs; back from each panel.
- 10: open an event from the agenda and from a Linked card; edit a series (this, following, all); delete an occurrence and the whole; conflict resolution save; new event with the calendar picker and the read-only toast.
- 13: mail list paging, pull to refresh, Load more, selection mark read/flag/delete; open a message (body, attachments, links badge); reply, reply all, forward (prefill, quote, answered mark after send on IMAP, none on Gmail); compose send offline; window picker from account settings.
- 14: files list open/share/export/save to folder, "show message", import a file; rotation keeps a pending export (saved state).
- 15: filter page for mail (newly ticked mailbox runs its first chunk), contacts, calendar, files; default chip.
- 16: full onboarding (standard and advanced, books step, phone switches, notifications prompt); account settings (switches, books, calendars, sender name, window, servers); delete an account (contacts move to the device book, mail and events gone, phone accounts removed).
- 17: deleted items restore mail, contact, event, free space; Linked card opens message, contact, event, file, and "gone" toast.
- 18: contacts, agenda and files lists scroll, headers, taps (folder header opens), dividers and card shapes.
- 19: every flow above once more for spinners, toasts and error dialogs; leave the app mid-operation (no crash on return).
- 20: dates in mail rows (today/yesterday times), agenda week and month headers, deleted items dates, birthdays sentence; onboarding cards look unchanged.

### Critical files for implementation
- android/app/src/main/java/org/pimalaya/MainActivity.java
- android/app/src/main/java/org/pimalaya/setup/OnboardingFlow.java
- android/app/src/main/java/org/pimalaya/ui/Host.java
- android/app/src/main/java/org/pimalaya/sync/SyncCoordinator.java
- android/app/src/test/java/org/pimalaya/LayeringTest.java
