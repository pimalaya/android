package org.pimalaya.sync;

import android.app.AlertDialog;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.pimalaya.R;
import org.pimalaya.account.AccountActivation;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SyncScope;
import org.pimalaya.calendar.CalendarRows;
import org.pimalaya.client.Clients;
import org.pimalaya.client.MailSession;
import org.pimalaya.contact.BookEntry;
import org.pimalaya.contact.ContactEngine;
import org.pimalaya.contact.ContactPhonePass;
import org.pimalaya.contact.UnsentContacts;
import org.pimalaya.mail.BodyPlan;
import org.pimalaya.mail.MailBodies;
import org.pimalaya.mail.MailEngine;
import org.pimalaya.mail.MailFill;
import org.pimalaya.mail.MailList;
import org.pimalaya.mail.MailState;
import org.pimalaya.mail.MailStore;
import org.pimalaya.mail.MailSync;
import org.pimalaya.mail.MailTally;
import org.pimalaya.mail.MailWindow;
import org.pimalaya.phone.Accounts;
import org.pimalaya.pimdir.HeldDeletions;
import org.pimalaya.pimdir.ParkedRounds;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirEngine;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.SyncDock;

/**
 * The app's foreground sync: one pass at a time on {@link Host}'s io thread.
 *
 * <p>Its progress shows on the sync dock; its posts go through {@code
 * postAlive}.
 */
public final class SyncCoordinator implements MailSync {
    /** What only the activity does for a sync. */
    public interface Screens {
        /** The domain whose list is on screen, null for any other screen. */
        PimDomain shown();

        /** The sync dock, built after the coordinator. */
        SyncDock dock();

        /**
         * Keeps the screen on while a pass runs, mail footer redrawn (main).
         */
        void syncing(boolean active);

        /**
         * Lands on a domain's list, a newly connected account's (main thread).
         */
        void land(PimDomain domain);

        /** Reads a domain's list again after a pass (main thread). */
        void reload(PimDomain domain);

        /**
         * Keeps the account cache current after a token refresh (any thread).
         */
        void renewed(AccountEntry updated);

        /** Whether a background run holds the sync. */
        boolean backgroundRunning();

        /** Redraws what shows bodies left to download, drawer pills (main). */
        void bodiesChanged();

        /** When a pass last brought a Contacts-app edit in (any thread). */
        long contactsIngested();

        /** When a pass last brought a calendar-app edit in (any thread). */
        long calendarsIngested();
    }

    private final Host host;

    private final Screens screens;

    private final PimdirDb pimdir;

    private final MailStore mail;

    private final Clients clients;

    /** The dock's model of a pass, which the passes report into. */
    private final SyncProgress progress;

    /** Every remote and phone pass, behind the sync dock. */
    private final SyncPasses passes;

    /**
     * The shared sync state: true while a sync runs.
     *
     * <p>Every element reflecting it (the dock, the drawer's sync row)
     * subscribes through {@link #observe}; {@link #setSyncing} pushes to all at
     * once.
     */
    private volatile boolean syncing;

    /**
     * The domains whose first sync ran since the app opened, whatever it ended
     * with; one connected again owes it anew.
     */
    private final Set<PimDomain> firstRun = EnumSet.noneOf(PimDomain.class);

    private final List<Consumer<Boolean>> syncObservers = new ArrayList<>();

    /** Whether the activity is in the foreground: the fill runs only then. */
    private volatile boolean foreground;

    /** Whether the body step's loop is queued or running, on the io thread. */
    private final java.util.concurrent.atomic.AtomicBoolean bodying =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Whether the fill's listing loop is queued or running. */
    private final java.util.concurrent.atomic.AtomicBoolean listing =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** The fill's listing loop runs here, beside the body step's io thread. */
    private final java.util.concurrent.ExecutorService fillThread =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    task -> {
                        Thread thread = new Thread(task, "pimalaya-fill");
                        thread.setDaemon(true);
                        return thread;
                    });

    /**
     * The body step's session pools by account; io thread only. One session
     * while the fill lists, the account's whole pool once it is done.
     */
    private final Map<String, WorkerPool<MailSession>> bodyPools = new HashMap<>();

    /** The fill's listing pools by account, every session but the body's. */
    private final Map<String, WorkerPool<MailSession>> listingPools =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The body step's plan for the loop under way, else null; io thread. */
    private MailBodies.Run bodies;

    /** Whether the body step replans at its next step (a pass, a setting). */
    private volatile boolean replanBodies;

    /** Bodies left to download by account while the body step runs (pills). */
    private final Map<String, Integer> bodiesLeft = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The body step's count over the loop, all accounts summed.
     *
     * <p>The sync dock's bar while older mail downloads.
     */
    private final MailTally bodiesTally;

    /** The Contacts-app edit last reloaded after ({@code SyncService}'s). */
    private volatile long reloadedIngest;

    /** The calendar-app edit last reloaded after. */
    private volatile long reloadedCalendarIngest;

    /** Opens the sign-in again of an account, by address; the activity's. */
    public Consumer<String> signInAgain = email -> {};

    /** The coordinator over {@code parts}, drawing through {@code screens}. */
    public SyncCoordinator(Host host, SyncPasses.Parts parts, Screens screens) {
        this.host = host;
        this.screens = screens;
        this.pimdir = parts.pimdir();
        this.mail = parts.mail();
        this.clients = parts.clients();
        progress =
                new SyncProgress(
                        host,
                        parts.cards(),
                        this::passes,
                        screens::dock,
                        () -> syncing,
                        screens::renewed);
        passes = new SyncPasses(host, parts, progress);
        reloadedIngest = screens.contactsIngested();
        reloadedCalendarIngest = screens.calendarsIngested();
        bodiesTally =
                new MailTally(
                        (landed, planned) ->
                                host.postAlive(() -> screens.dock().filled(landed, planned)));
    }

    /** Every remote and phone pass. */
    public SyncPasses passes() {
        return passes;
    }

    /** Opens one account's mail connection ({@link SyncPasses#openMail}). */
    @Override
    public MailSession openMail(AccountEntry account) {
        return passes.openMail(account);
    }

    /** Each domain's filter, the shared one the list shows. */
    private MergedFilter filterOf(PimDomain domain) {
        return MergedFilter.of(host, domain);
    }

    /**
     * Starts a pass: takes the process's sync lock, false while one holds it.
     *
     * <p>A background run holding it says so on its own dock.
     */
    private boolean startSync() {
        if (!pimdir.locks().takeSync()) {
            return false;
        }
        progress.resetSyncPlan();
        setSyncing(true);
        return true;
    }

    /** Pushes the sync flag to every subscriber; ending frees the lock. */
    private void setSyncing(boolean value) {
        boolean ended = syncing && !value;
        if (ended) {
            pimdir.locks().releaseSync();
        }
        syncing = value;
        for (Consumer<Boolean> observer : syncObservers) {
            observer.accept(value);
        }
        if (ended) {
            offerParked();
            offerHeld();
            offerUnsent();
        }
    }

    /**
     * Says which contact changes the pass could not send, per contact and book
     * (UnsentContacts), once, and again when they change; the contact's row
     * marker shows it again.
     */
    private void offerUnsent() {
        List<UnsentContacts.Row> rows = UnsentContacts.rows(pimdir, host);
        Set<String> keys = new HashSet<>(UnsentContacts.keys(rows));
        if (keys.equals(unsentOffered)) {
            return;
        }
        unsentOffered = keys;
        if (!keys.isEmpty()) {
            UnsentContacts.show(host, pimdir, rows);
        }
    }

    /** The waiting changes last shown, so a set shown as it is is not again. */
    private Set<String> unsentOffered = Set.of();

    /**
     * Asks about the rounds a pass parked rather than closed (pimdir SYNC §5):
     * a listing naming nothing, or most of a collection gone. Their items stay
     * until the user removes them too, which runs the drawer's sync with
     * those collections accepting the absences; keeping them leaves the
     * rounds parked, asked about again only when what they find changes.
     */
    private void offerParked() {
        List<ParkedRounds.Parked> parked = new ArrayList<>(pimdir.parked().unoffered());
        // NOTE: a phone book listing nothing is a permission withdrawn as
        // often as a book emptied, and accepting it would delete every card
        // from the server: it stays parked, never offered.
        parked.removeIf(round -> PimdirStorage.isPhoneCollection(round.collection()));
        if (parked.isEmpty()) {
            return;
        }
        Map<String, PimdirCollections.Stored> stored = storedCollections();
        StringBuilder rows = new StringBuilder(host.getString(R.string.sync_parked_body));
        List<String> engines = new ArrayList<>();
        for (ParkedRounds.Parked round : parked) {
            engines.add(round.collection());
            PimdirCollections.Stored one =
                    stored.get(PimdirStorage.collectionOf(round.collection()));
            String name = one == null ? round.collection() : one.name;
            String account = one == null ? "" : one.accountEmail;
            rows.append("\n\n")
                    .append(host.getString(
                            R.string.sync_parked_row,
                            name,
                            account,
                            round.absent(),
                            round.bound()));
        }
        new AlertDialog.Builder(host)
                .setTitle(R.string.sync_parked_title)
                .setMessage(rows.toString())
                .setPositiveButton(
                        R.string.sync_parked_remove,
                        (dialog, which) -> {
                            pimdir.parked().accept(engines);
                            syncAll();
                        })
                .setNegativeButton(R.string.sync_parked_keep, null)
                .show();
    }

    /** Asks about the deletions phone passes held back. */
    private void offerHeld() {
        List<HeldDeletions.Held> held = pimdir.held().unoffered();
        if (held.isEmpty()) {
            return;
        }
        Map<String, PimdirCollections.Stored> stored = storedCollections();
        StringBuilder rows = new StringBuilder(host.getString(R.string.sync_held_body));
        List<String> engines = new ArrayList<>();
        for (HeldDeletions.Held round : held) {
            engines.add(round.collection());
            PimdirCollections.Stored one =
                    stored.get(PimdirStorage.collectionOf(round.collection()));
            rows.append("\n\n")
                    .append(host.getString(
                            R.string.sync_parked_row,
                            one == null ? round.collection() : one.name,
                            one == null ? "" : one.accountEmail,
                            round.handles().size(),
                            round.members()));
        }
        new AlertDialog.Builder(host)
                .setTitle(R.string.sync_held_title)
                .setMessage(rows.toString())
                .setPositiveButton(
                        R.string.sync_held_delete,
                        (dialog, which) -> {
                            pimdir.held().release(engines);
                            syncAll();
                        })
                .setNegativeButton(
                        R.string.sync_held_restore,
                        (dialog, which) -> {
                            pimdir.held().restore(engines);
                            syncAll();
                        })
                .setNeutralButton(R.string.sync_held_later, null)
                .show();
    }

    /** Every stored collection, by id. */
    private Map<String, PimdirCollections.Stored> storedCollections() {
        Map<String, PimdirCollections.Stored> stored = new HashMap<>();
        PimdirCollections collections = new PimdirCollections(pimdir, host);
        for (String kind :
                new String[] {PimdirSummary.MAIL, PimdirSummary.CONTACT, PimdirSummary.CALENDAR}) {
            for (PimdirCollections.Stored one : collections.listAll(kind)) {
                stored.put(one.id, one);
            }
        }
        return stored;
    }

    /** Whether a sync pass is running. */
    public boolean isSyncing() {
        return syncing;
    }

    /**
     * Whether a pass of the app's or a background run holds the sync.
     *
     * <p>A step asked for now would do nothing.
     */
    @Override
    public boolean syncHeld() {
        return syncing || screens.backgroundRunning();
    }

    /**
     * Subscribes an element to the sync state, handing it the current value.
     */
    public void observe(Consumer<Boolean> observer) {
        syncObservers.add(observer);
        observer.accept(syncing);
    }

    /** The contacts remote pass over every subscribed book, under the dock. */
    public void syncRemote() {
        // NOTE: one pass at a time; the lists stay usable while it runs,
        // so a pull or the drawer can ask for another meanwhile.
        if (syncing) {
            return;
        }
        progress.syncAccount(null);
        if (!startSync()) {
            return;
        }

        host.io.execute(
                () -> {
                    progress.planSync(PimDomain.CONTACTS, SyncScope.all(host), null);
                    SyncPasses.Outcome outcome = passes.syncRemote();
                    host.postAlive(
                            () -> {
                                setSyncing(false);
                                screens.reload(PimDomain.CONTACTS);
                                reportSync(outcome);
                            });
                });
    }

    /**
     * Surfaces a failed sync in an error dialog.
     *
     * <p>A pass that went through says nothing: the lists show what it brought,
     * and a conflict marks its own row.
     */
    private void reportSync(SyncPasses.Outcome outcome) {
        if (outcome.failure != null) {
            // NOTE: the last sync's store keeps failing addressbooks usable.
            report(outcome.failure);
        }
    }

    /** A failed pass said: the accounts refused for their sign-in by name. */
    private void report(Exception failure) {
        if (failure == null) {
            return;
        }
        List<String> refused = SignInRefusals.accounts(host);
        if (!SignInRefusals.refusal(failure) || refused.isEmpty()) {
            host.showError(failure, R.string.sync_failed);
            return;
        }
        Collections.sort(refused);
        new AlertDialog.Builder(host)
                .setTitle(R.string.sign_in_refused_title)
                .setMessage(
                        host.getResources()
                                .getQuantityString(
                                        R.plurals.sign_in_refused_report,
                                        refused.size(),
                                        String.join(", ", refused)))
                .setPositiveButton(
                        R.string.sign_in_again,
                        (dialog, which) -> signInAgain.accept(refused.get(0)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * The mail list's pull: the accounts and mailboxes the filter shows.
     *
     * <p>On the endpoint each account was connected with. Under a role chip
     * ({@code role}, null for none) only that role's mailboxes of the shown
     * accounts, ticked or not ({@code MailList.narrowed}).
     */
    @Override
    public void syncMail(String role) {
        if (syncing) {
            return;
        }
        progress.syncAccount(null);
        if (!startSync()) {
            return;
        }
        // NOTE: on a metered network the outbox still goes out, and the
        // listing waits for Wi-Fi.
        boolean listing = wifi();
        MergedFilter filter = filterOf(PimDomain.MAIL);
        host.io.execute(
                () -> {
                    SyncScope scope = MailList.narrowed(filter, role, mail.roles());
                    progress.planSync(PimDomain.MAIL, scope, null);
                    SyncPasses.MailPass pass = passes.mailPass(scope, listing);
                    host.postAlive(
                            () -> {
                                setSyncing(false);
                                screens.reload(PimDomain.MAIL);
                                if (pass.failure != null) {
                                    report(pass.failure);
                                }
                                if (!listing) {
                                    waitForWifi(() -> syncMail(role));
                                }
                                fillMail();
                            });
                });
    }

    /**
     * The agenda's pull: the calendars the filter shows, and their events.
     *
     * <p>Whole-collection: incremental listing (ctag, sync-token) is not wired,
     * so a refresh relists, one report round trip per calendar.
     */
    public void syncCalendars() {
        if (syncing) {
            return;
        }
        progress.syncAccount(null);
        if (!startSync()) {
            return;
        }
        host.io.execute(
                () -> {
                    progress.planSync(PimDomain.CALENDAR, filterOf(PimDomain.CALENDAR), null);
                    Exception failure = passes.calendarPass(filterOf(PimDomain.CALENDAR));
                    host.postAlive(
                            () -> {
                                setSyncing(false);
                                screens.reload(PimDomain.CALENDAR);
                                if (failure != null) {
                                    report(failure);
                                }
                            });
                });
    }

    /**
     * Runs a store-wide write under the process's sync lock (io thread).
     *
     * <p>So a background run never writes the same rows meanwhile. Turned down,
     * saying so, while one holds it; a failure is said in a dialog, under
     * {@code failed} when it names nothing itself.
     */
    public void underSyncLock(int failed, Runnable write) {
        if (!pimdir.locks().takeSync()) {
            host.postAlive(() -> host.toast(host.getString(R.string.sync_busy)));
            return;
        }
        try {
            write.run();
        } catch (RuntimeException error) {
            Log.w("pimalaya", "store write failed", error);
            host.postAlive(() -> host.showError(error, failed));
        } finally {
            pimdir.locks().releaseSync();
        }
    }

    /**
     * The contacts list's pull: the books the filter shows, synced.
     *
     * <p>Each book's phone passes around its server one. The drawer's sync
     * takes every domain.
     */
    public void syncContacts() {
        if (syncing) {
            return;
        }

        progress.syncAccount(null);
        if (!startSync()) {
            return;
        }
        host.io.execute(
                () -> {
                    progress.planSync(PimDomain.CONTACTS, filterOf(PimDomain.CONTACTS), null);
                    SyncPasses.Outcome outcome = passes.syncRemote(filterOf(PimDomain.CONTACTS));
                    host.postAlive(
                            () -> {
                                setSyncing(false);
                                screens.reload(PimDomain.CONTACTS);
                                reportSync(outcome);
                            });
                });
    }

    /**
     * The drawer's sync: every domain in turn, contacts, mail, calendars.
     *
     * <p>Every collection of every account that is on, whatever the filters
     * hide; a list's pull takes its domain within its filter.
     */
    public void syncAll() {
        if (syncing) {
            return;
        }

        progress.syncAccount(null);
        if (!startSync()) {
            return;
        }
        boolean listing = wifi();
        host.io.execute(
                () -> {
                    progress.planSync(PimDomain.CONTACTS, SyncScope.all(host), null);
                    progress.planSync(PimDomain.MAIL, SyncScope.mail(host), null);
                    progress.planSync(PimDomain.CALENDAR, SyncScope.all(host), null);
                    SyncPasses.Outcome outcome = passes.syncRemote();
                    SyncPasses.MailPass sent = passes.mailPass(SyncScope.mail(host), listing);
                    Exception calendars = passes.calendarPass(SyncScope.all(host));
                    Exception other = sent.failure != null ? sent.failure : calendars;
                    host.postAlive(
                            () -> {
                                setSyncing(false);
                                screens.reload(PimDomain.CONTACTS);
                                screens.reload(PimDomain.MAIL);
                                screens.reload(PimDomain.CALENDAR);
                                reportSync(outcome);
                                if (!listing) {
                                    waitForWifi(() -> syncMail(null));
                                }
                                fillMail();
                                // NOTE: one error dialog, the contacts
                                // one first.
                                if (other != null && outcome.failure == null) {
                                    report(other);
                                }
                            });
                });
    }

    /**
     * Shows or hides the sync dock over the lists' bottom navigation.
     *
     * <p>The account, domain and step over one thin bar; the lists stay usable
     * and the screen stays on. A pass starting drops the last one's lines.
     */
    public void showSyncDock(boolean active) {
        screens.syncing(active);
        if (active) {
            progress.started();
            return;
        }
        screens.dock().synced();
        // NOTE: an account connected while this pass ran owes its first
        // sync to the tab it landed on, which the pass kept from starting.
        host.postAlive(() -> firstSyncIfOwed(screens.shown()));
    }

    /** The addresses of some accounts. */
    private static List<String> emailsOf(List<AccountEntry> accounts) {
        List<String> emails = new ArrayList<>();
        for (AccountEntry account : accounts) {
            emails.add(account.email);
        }
        return emails;
    }

    /**
     * A newly connected account: each domain it covers owes its first sync.
     *
     * <p>Lands on the first (mail when covered), whose tab syncs each mailbox's
     * newest chunk, the rest filling behind ({@link #fillMail}); other domains
     * sync when their tab opens ({@link #firstSyncIfOwed}).
     */
    public void syncConnected(AccountEntry account) {
        for (PimDomain domain : PimDomain.values()) {
            if (account.covers(domain)) {
                FirstSync.owe(host, account.email, domain);
                firstRun.remove(domain);
            }
        }
        host.postAlive(() -> screens.land(landingOf(account)));
    }

    /**
     * Whether a domain's tab still waits on its first sync: an account that is
     * on owes it, and none ran since the app opened or the account connected,
     * whatever it ended with. The tab's add button stays away till then.
     */
    public boolean firstSyncAhead(PimDomain domain) {
        return domain != null && !firstRun.contains(domain) && !owingFirst(domain).isEmpty();
    }

    /** The accounts that are on and owe a domain its first sync. */
    private List<AccountEntry> owingFirst(PimDomain domain) {
        List<AccountEntry> owing = new ArrayList<>();
        for (String email : FirstSync.owing(host, domain)) {
            for (AccountEntry account : passes.accountsFor(domain)) {
                // NOTE: an account that is off keeps owing it.
                if (account.email.equals(email) && AccountActivation.enabled(host, email)) {
                    owing.add(account);
                }
            }
        }
        return owing;
    }

    /** The list a freshly connected account lands on: its first domain. */
    private static PimDomain landingOf(AccountEntry account) {
        if (account.covers(PimDomain.MAIL)) {
            return PimDomain.MAIL;
        }
        return account.covers(PimDomain.CONTACTS) ? PimDomain.CONTACTS : PimDomain.CALENDAR;
    }

    /**
     * Runs the first sync a domain's tab owes, that domain alone.
     *
     * <p>Under the dock; nothing when no account owes it, or while a sync runs.
     */
    public void firstSyncIfOwed(PimDomain domain) {
        // NOTE: a background run holds the lock; its end calls back here.
        if (domain == null
                || screens.shown() != domain
                || isSyncing()
                || screens.backgroundRunning()
                || host.isFinishing()) {
            return;
        }
        List<AccountEntry> owing = owingFirst(domain);
        if (owing.isEmpty()) {
            return;
        }

        switch (domain) {
            case MAIL:
                firstMail(owing);
                break;
            case CONTACTS:
                firstContacts(owing);
                break;
            case CALENDAR:
                firstCalendars(owing);
                break;
        }
    }

    /**
     * The mail tab's first sync: a {@link org.pimalaya.mail.MailStep} each.
     *
     * <p>On each owing account's one session, on Wi-Fi only; otherwise the dock
     * says it waits, and it runs once Wi-Fi is back.
     */
    private void firstMail(List<AccountEntry> owing) {
        if (!wifi()) {
            waitForWifi(() -> firstSyncIfOwed(screens.shown()));
            return;
        }
        progress.syncAccount(null);
        if (!startSync()) {
            return;
        }
        firstRun.add(PimDomain.MAIL);
        host.io.execute(
                () -> {
                    progress.planSteps(emailsOf(owing));
                    progress.syncAccount(null);
                    Exception failure = null;
                    for (AccountEntry account : owing) {
                        Exception error;
                        try (MailSession session = passes.openMail(account)) {
                            error = passes.stepMail(account, session, null, true);
                        } catch (Exception opened) {
                            error = opened;
                        }
                        if (error == null) {
                            FirstSync.paid(host, account.email, PimDomain.MAIL);
                        } else if (failure == null) {
                            failure = error;
                        }
                    }
                    Exception outcome = failure;
                    host.postAlive(
                            () -> {
                                setSyncing(false);
                                screens.reload(PimDomain.MAIL);
                                if (outcome != null) {
                                    report(outcome);
                                }
                                fillMail();
                            });
                });
    }

    /**
     * The contacts tab's first sync: the remote pass over every book.
     *
     * <p>The owing accounts' newly subscribed books among them.
     */
    private void firstContacts(List<AccountEntry> owing) {
        progress.syncAccount(null);
        if (!startSync()) {
            return;
        }
        firstRun.add(PimDomain.CONTACTS);
        host.io.execute(
                () -> {
                    progress.planSync(PimDomain.CONTACTS, SyncScope.all(host), null);
                    SyncPasses.Outcome outcome = passes.syncRemote();
                    if (outcome.failure == null) {
                        for (AccountEntry account : owing) {
                            FirstSync.paid(host, account.email, PimDomain.CONTACTS);
                        }
                    }
                    host.postAlive(
                            () -> {
                                setSyncing(false);
                                screens.reload(PimDomain.CONTACTS);
                                reportSync(outcome);
                            });
                });
    }

    /** The calendar tab's first sync: every owing account's calendars. */
    private void firstCalendars(List<AccountEntry> owing) {
        progress.syncAccount(null);
        if (!startSync()) {
            return;
        }
        firstRun.add(PimDomain.CALENDAR);
        host.io.execute(
                () -> {
                    progress.planSync(PimDomain.CALENDAR, SyncScope.all(host), emailsOf(owing));
                    progress.syncAccount(null);
                    Exception failure = null;
                    for (AccountEntry account : owing) {
                        Exception error = passes.fetchCalendars(account, SyncScope.all(host));
                        if (error == null) {
                            FirstSync.paid(host, account.email, PimDomain.CALENDAR);
                        } else if (failure == null) {
                            failure = error;
                        }
                    }
                    Exception outcome = failure;
                    host.postAlive(
                            () -> {
                                setSyncing(false);
                                screens.reload(PimDomain.CALENDAR);
                                if (outcome != null) {
                                    report(outcome);
                                }
                            });
                });
    }

    /**
     * The first step of each never-listed mailbox of {@code taken}.
     *
     * <p>By account, about 50 newest messages each, under the dock, on Wi-Fi
     * only (once back otherwise); their bodies download after.
     */
    public void firstOfMailboxes(Map<String, List<String>> taken) {
        if (!wifi()) {
            waitForWifi(() -> firstOfMailboxes(taken));
            return;
        }
        if (isSyncing()) {
            return;
        }
        progress.syncAccount(null);
        if (!startSync()) {
            return;
        }
        host.io.execute(
                () -> {
                    Map<String, List<String>> owed = new LinkedHashMap<>();
                    for (Map.Entry<String, List<String>> account : taken.entrySet()) {
                        for (String collection : account.getValue()) {
                            MailStore.Coverage coverage = mail.coverage(collection);
                            if (coverage.at == null
                                    && !coverage.filling
                                    && !collection.equals(mail.outbox().outboxOf(account.getKey()))) {
                                owed.computeIfAbsent(account.getKey(), email -> new ArrayList<>())
                                        .add(collection);
                            }
                        }
                    }
                    progress.planSteps(new ArrayList<>(owed.keySet()));
                    Exception failure = null;
                    for (Map.Entry<String, List<String>> asked : owed.entrySet()) {
                        AccountEntry account = mailAccount(asked.getKey());
                        if (account == null
                                || !AccountActivation.enabled(host, asked.getKey())) {
                            continue;
                        }
                        try (MailSession session = passes.openMail(account)) {
                            for (String collection : asked.getValue()) {
                                Exception error = passes.firstOf(account, session, collection);
                                if (failure == null) {
                                    failure = error;
                                }
                            }
                        } catch (Exception opened) {
                            if (failure == null) {
                                failure = opened;
                            }
                        }
                    }
                    Exception outcome = failure;
                    replanBodies = true;
                    host.postAlive(
                            () -> {
                                setSyncing(false);
                                screens.reload(PimDomain.MAIL);
                                if (outcome != null) {
                                    report(outcome);
                                }
                                fillMail();
                            });
                });
    }

    /** The mail account of an address, null when it is gone. */
    private AccountEntry mailAccount(String email) {
        for (AccountEntry candidate : passes.accountsFor(PimDomain.MAIL)) {
            if (candidate.email.equals(email)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Starts the background fill's two loops when allowed and not running.
     *
     * <p>The body step on the io thread ({@link MailBodies}) first, then the
     * listing on its own, each account's jobs taking its next mailbox by
     * {@link MailEngine#FILL_CHUNK} as each is free ({@link MailFill.Queue}):
     * what a first sync or Load more just showed reads whole before any older
     * envelope is listed, and Graph never sees the bodies' batch and three
     * listings at once. A chunk landing bodies to plan has the body step run
     * beside the listing. Both stop off the foreground or Wi-Fi, on a sync or
     * a failure, resuming from the store's floors; Gmail and JMAP are not
     * listed.
     */
    public void fillMail() {
        replanBodies = true;
        if (!fillAllowed()) {
            return;
        }
        startBodies();
    }

    /** Starts the fill's listing loop on its own thread unless it runs. */
    private void startListing() {
        if (fillAllowed() && listing.compareAndSet(false, true)) {
            fillThread.execute(this::listMail);
        }
    }

    /** Starts the body step's loop on the io thread unless it runs. */
    private void startBodies() {
        if (bodying.compareAndSet(false, true)) {
            host.io.execute(this::bodyStep);
        }
    }

    /** Has the body step plan again at its next step. */
    public void replanBodies() {
        replanBodies = true;
    }

    /** One step of the body step (io thread), the next queued. */
    private void bodyStep() {
        if (bodies == null || replanBodies) {
            replanBodies = false;
            BodyPlan.Plan plan =
                    BodyPlan.of(host, mail, passes.accountsFor(PimDomain.MAIL), MailState.of(host));
            for (Map.Entry<String, Integer> left : plan.left().entrySet()) {
                if (left.getValue() == 0) {
                    bodiesLeft.remove(left.getKey());
                } else {
                    bodiesLeft.put(left.getKey(), left.getValue());
                }
            }
            // NOTE: a replan adds to what the loop landed, the bar carrying on.
            bodiesTally.expect(plan.planned());
            host.postAlive(screens::bodiesChanged);
            bodies = plan.run();
        }
        MailBodies.Step body = MailBodies.step(bodies, bodyHost());
        Log.d("pimalaya", "mail bodies: " + body);
        if (body == MailBodies.Step.AGAIN || (replanBodies && fillAllowed())) {
            // NOTE: the glyph once a step worked, never for a loop that
            // found nothing to do.
            if (body == MailBodies.Step.AGAIN) {
                host.postAlive(() -> screens.dock().filling(true));
            }
            host.io.execute(this::bodyStep);
            return;
        }
        closePools(bodyPools);
        bodies = null;
        bodiesTally.reset();
        if (!bodiesLeft.isEmpty()) {
            bodiesLeft.clear();
            host.postAlive(screens::bodiesChanged);
        }
        bodying.set(false);
        // NOTE: a chunk may have landed bodies to plan between the replan
        // read above and the flag going down.
        if (replanBodies && fillAllowed()) {
            startBodies();
            return;
        }
        startListing();
        fillEnded();
    }

    /**
     * The fill's listing loop (its own thread): every account's queue at
     * once, each on its listing pool, until each is done, paused or failed.
     */
    private void listMail() {
        List<java.util.concurrent.Callable<WorkerPool.Outcome>> runs = new ArrayList<>();
        List<MailFill.Queue> queues = new ArrayList<>();
        List<String> emails = new ArrayList<>();
        for (AccountEntry account : passes.accountsFor(PimDomain.MAIL)) {
            MailFill.Queue queue = new MailFill.Queue(fillHost(account.email));
            WorkerPool<MailSession> pool = listingPool(account);
            String accountId = passes.accountIdOf(account.email);
            queues.add(queue);
            emails.add(account.email);
            runs.add(
                    () ->
                            pool.drain(
                                    "mail fill " + account.email,
                                    new WorkerPool.Source() {
                                        @Override
                                        public String next() {
                                            MailStore.Edge edge = queue.take();
                                            if (edge == null) {
                                                return null;
                                            }
                                            return edge.collection;
                                        }

                                        @Override
                                        public void back(String collection, boolean landed) {
                                            queue.done(collection, landed);
                                            // NOTE: a chunk listed above a
                                            // frontier brings bodies to plan.
                                            if (landed) {
                                                replanBodies = true;
                                                if (fillAllowed()) {
                                                    startBodies();
                                                }
                                            }
                                        }
                                    },
                                    (session, collection, remote) -> {
                                        MailEngine engine =
                                                new MailEngine(pimdir, clients, session, accountId);
                                        try {
                                            engine.widen(collection, MailEngine.FILL_CHUNK);
                                        } finally {
                                            remote.addAndGet(engine.remoteSoFar());
                                        }
                                    }));
        }
        List<WorkerPool.Outcome> outcomes = WorkerPool.together(runs);
        for (int index = 0; index < outcomes.size(); index++) {
            for (WorkerPool.Failure failure : outcomes.get(index).failures) {
                Log.w("pimalaya", "mail fill stopped: " + failure.key, failure.error);
            }
            Log.d(
                    "pimalaya",
                    "mail fill " + AccountEntry.redacted(emails.get(index)) + ": "
                            + queues.get(index).outcome());
        }
        closePools(listingPools);
        listing.set(false);
        fillEnded();
    }

    /** Hides the dock's download once neither of the fill's loops runs. */
    private void fillEnded() {
        if (!bodying.get() && !listing.get()) {
            host.postAlive(() -> screens.dock().filling(false));
        }
    }

    /** One account's fill queue's reach: what it may do now, its mailboxes. */
    private MailFill.Host fillHost(String accountEmail) {
        return new MailFill.Host() {
            @Override
            public boolean allowed() {
                return fillAllowed();
            }

            @Override
            public List<MailStore.Edge> edges() {
                // NOTE: an account that is off syncs nothing, the
                // background fill included, nor does a mailbox the filter
                // unticks; one stepped through account-wide (Gmail, JMAP)
                // holds what its steps brought.
                MergedFilter filter = MergedFilter.of(host, PimDomain.MAIL);
                List<MailStore.Edge> edges = mail.edges();
                edges.removeIf(
                        edge ->
                                !edge.accountEmail.equals(accountEmail)
                                        || edge.allMail
                                        || !filter.chosen(edge.collection)
                                        || !AccountActivation.enabled(
                                                host, edge.accountEmail));
                return edges;
            }
        };
    }

    /** The body step's reach: the network rule, the pools, the progress. */
    private MailBodies.Host bodyHost() {
        return new MailBodies.Host() {
            @Override
            public boolean allowed(String accountEmail) {
                return bodiesAllowed(accountEmail);
            }

            @Override
            public List<MailBodies.Row> download(String accountEmail, List<MailBodies.Row> rows)
                    throws Exception {
                return downloadBodies(accountEmail, rows);
            }

            @Override
            public void progress(String accountEmail, int left) {
                // NOTE: what the chunk took, its bodies linked without a
                // file included, in place of the files counted as it ran.
                Integer before = bodiesLeft.get(accountEmail);
                bodiesTally.settle(before == null ? 0 : before - left);
                if (left > 0) {
                    bodiesLeft.put(accountEmail, left);
                } else {
                    bodiesLeft.remove(accountEmail);
                }
                host.postAlive(screens::bodiesChanged);
            }
        };
    }

    /**
     * Whether an account's bodies may download now.
     *
     * <p>The app in the foreground, no other sync, the account on, and Wi-Fi.
     */
    private boolean bodiesAllowed(String accountEmail) {
        return foreground && !isSyncing() && AccountActivation.enabled(host, accountEmail) && wifi();
    }

    /**
     * Downloads some of an account's bodies on its body pool, blocking.
     *
     * <p>Rows split across the pool's sessions ({@link #bodyPool}); answers
     * those not landed. A
     * failure closes the pool, so a retry runs on fresh sessions (renewing a
     * token); every job failing throws.
     */
    private List<MailBodies.Row> downloadBodies(String accountEmail, List<MailBodies.Row> rows)
            throws Exception {
        AccountEntry account = mailAccount(accountEmail);
        if (account == null) {
            return List.of();
        }
        WorkerPool<MailSession> pool = bodyPool(account);

        Map<String, List<MailBodies.Row>> byCollection = new java.util.LinkedHashMap<>();
        for (MailBodies.Row row : rows) {
            byCollection.computeIfAbsent(row.collection, collection -> new ArrayList<>()).add(row);
        }
        List<String> keys = new ArrayList<>();
        Map<String, List<MailBodies.Row>> jobs = new HashMap<>();
        for (List<MailBodies.Row> held : byCollection.values()) {
            int slice = Math.max(1, (held.size() + pool.size() - 1) / pool.size());
            for (int from = 0; from < held.size(); from += slice) {
                String key = Integer.toString(keys.size());
                keys.add(key);
                jobs.put(key, held.subList(from, Math.min(held.size(), from + slice)));
            }
        }

        String accountId = passes.accountIdOf(account.email);
        List<MailBodies.Row> failed = Collections.synchronizedList(new ArrayList<>());
        WorkerPool.Outcome outcome =
                pool.run(
                        "mail pass bodies " + account.email,
                        keys,
                        (session, key, remote) -> {
                            MailEngine engine = new MailEngine(pimdir, clients, session, accountId);
                            engine.tally = bodiesTally;
                            try {
                                List<MailBodies.Row> job = jobs.get(key);
                                failed.addAll(engine.download(job.get(0).collection, job));
                            } finally {
                                remote.addAndGet(engine.remoteSoFar());
                            }
                        });
        for (WorkerPool.Failure failure : outcome.failures) {
            failed.addAll(jobs.get(failure.key));
        }
        if (!failed.isEmpty()) {
            bodyPools.remove(account.email);
            pool.close();
        }
        if (!keys.isEmpty() && outcome.failures.size() == keys.size()) {
            throw outcome.failure();
        }
        return failed;
    }

    /**
     * An account's pool for the body step; on the io thread.
     *
     * <p>One session while the fill lists, the listing jobs holding the
     * others, else the account's whole pool; on Graph always one, whose
     * {@code $batch} already runs its bodies side by side against the four
     * requests a mailbox takes at once (2026-11-05: four sessions of batches
     * were throttled). The other size, when kept, closes first, so the
     * account never holds more sessions than it runs.
     */
    private WorkerPool<MailSession> bodyPool(AccountEntry account) {
        org.pimalaya.client.BackendInfo info = account.server(PimDomain.MAIL).info();
        int size =
                listing.get() || info.protocol() == org.pimalaya.client.Protocol.GRAPH
                        ? 1
                        : info.sessions();
        WorkerPool<MailSession> pool = bodyPools.get(account.email);
        if (pool != null && pool.size() != Math.max(1, size)) {
            bodyPools.remove(account.email);
            pool.close();
            pool = null;
        }
        if (pool == null) {
            pool = new WorkerPool<>(size, null, () -> passes.openMail(account), MailSession::join);
            bodyPools.put(account.email, pool);
        }
        return pool;
    }

    /**
     * An account's pool for the fill's listing jobs: every session of the
     * account's but the one the body step holds, one at least.
     */
    private WorkerPool<MailSession> listingPool(AccountEntry account) {
        int sessions = account.server(PimDomain.MAIL).info().sessions();
        return listingPools.computeIfAbsent(
                account.email,
                email ->
                        new WorkerPool<>(
                                Math.max(1, sessions - 1),
                                null,
                                () -> passes.openMail(account),
                                MailSession::join));
    }

    /** Closes the connections a loop's pools kept, then forgets them. */
    private static void closePools(Map<String, WorkerPool<MailSession>> pools) {
        for (WorkerPool<MailSession> pool : pools.values()) {
            pool.close();
        }
        pools.clear();
    }

    /** Whether the fill may run now: foreground, no other sync, and Wi-Fi. */
    private boolean fillAllowed() {
        return foreground && !isSyncing() && wifi();
    }

    /** Whether a network is there at all. */
    @Override
    public boolean online() {
        android.net.ConnectivityManager connectivity =
                host.getSystemService(android.net.ConnectivityManager.class);
        return connectivity != null && connectivity.getActiveNetwork() != null;
    }

    /**
     * Whether the network is not metered, the one mail passes and fills run on.
     *
     * <p>Opening one message is an explicit act and fetches on any network
     * ({@link #online}).
     */
    @Override
    public boolean wifi() {
        return wifi(host);
    }

    /** Whether the network a context sees is not metered ({@link #wifi()}). */
    public static boolean wifi(android.content.Context context) {
        android.net.ConnectivityManager connectivity =
                context.getSystemService(android.net.ConnectivityManager.class);
        return connectivity != null
                && connectivity.getActiveNetwork() != null
                && !connectivity.isActiveNetworkMetered();
    }

    /**
     * What runs once Wi-Fi is back (a mail sync asked on mobile data), or null.
     */
    private Runnable owedOnWifi;

    /**
     * Says on the dock that the mail sync waits for Wi-Fi.
     *
     * <p>Runs {@code then} once it is back ({@link #wifiBack}).
     */
    void waitForWifi(Runnable then) {
        owedOnWifi = then;
        if (!isSyncing()) {
            SyncDock.Pass wait = new SyncDock.Pass();
            wait.title = host.getString(R.string.sync_line_mail);
            wait.line = host.getString(R.string.sync_line_wifi);
            wait.total = -1;
            wait.download = true;
            screens.dock().pass(wait);
        }
    }

    /**
     * Whether {@link #wifiWatch} last heard an unmetered network.
     *
     * <p>False until it hears one after it is registered.
     */
    private volatile boolean unmetered;

    /**
     * Hears Wi-Fi come back while the app is open, once per network.
     *
     * <p>Never on the capability changes Wi-Fi reports every few seconds while
     * up (signal, bandwidth), each of which would restart the body step and
     * fill.
     */
    private final android.net.ConnectivityManager.NetworkCallback wifiWatch =
            new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(
                        android.net.Network network,
                        android.net.NetworkCapabilities capabilities) {
                    boolean was = unmetered;
                    unmetered =
                            capabilities.hasCapability(
                                    android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                    if (unmetered && !was) {
                        host.postAlive(SyncCoordinator.this::wifiBack);
                    }
                }

                @Override
                public void onLost(android.net.Network network) {
                    unmetered = false;
                }
            };

    /**
     * Runs what waited for Wi-Fi, then the fill and the bodies (main thread).
     */
    private void wifiBack() {
        if (!foreground || !wifi()) {
            return;
        }
        Runnable owed = owedOnWifi;
        owedOnWifi = null;
        if (owed != null) {
            screens.dock().synced();
            owed.run();
        }
        fillMail();
    }

    /**
     * Takes the footer's {@code steps} under the dock, on Wi-Fi only.
     *
     * <p>One after another, each account's on its one session: Gmail over All
     * Mail or its one shown label, others by mailbox. Then the list reloads and
     * the bodies left are planned.
     */
    @Override
    public void loadMore(List<MailList.Step> steps) {
        if (isSyncing() || !wifi()) {
            return;
        }
        // NOTE: while the fill lists the older envelopes, a step the store
        // already holds shows at once and lists nothing; one that would list
        // them itself, on the sessions the fill holds, waits for it instead.
        if (listing.get()) {
            for (MailList.Step step : steps) {
                if (!mail.stepHeld(step.email, step.only)) {
                    host.toast(host.getString(R.string.mail_more_filling));
                    return;
                }
            }
        }
        progress.syncAccount(null);
        if (!startSync()) {
            return;
        }
        progress.fill();
        Map<String, List<String>> byAccount = new LinkedHashMap<>();
        for (MailList.Step step : steps) {
            byAccount.computeIfAbsent(step.email, email -> new ArrayList<>()).add(step.only);
        }
        host.io.execute(
                () -> {
                    progress.planSteps(new ArrayList<>(byAccount.keySet()));
                    Exception failure = null;
                    for (Map.Entry<String, List<String>> asked : byAccount.entrySet()) {
                        String email = asked.getKey();
                        AccountEntry account = mailAccount(email);
                        if (account == null || !AccountActivation.enabled(host, email)) {
                            continue;
                        }
                        try (MailSession session = passes.openMail(account)) {
                            for (String only : asked.getValue()) {
                                Exception error = passes.loadMore(account, session, only);
                                if (failure == null) {
                                    failure = error;
                                }
                            }
                        } catch (Exception opened) {
                            if (failure == null) {
                                failure = opened;
                            }
                        }
                    }
                    Exception outcome = failure;
                    replanBodies = true;
                    host.postAlive(
                            () -> {
                                setSyncing(false);
                                screens.reload(PimDomain.MAIL);
                                if (outcome != null) {
                                    report(outcome);
                                }
                                fillMail();
                            });
                });
    }

    /**
     * Moves the windows of {@code emails} later to {@code date} (io thread).
     *
     * <p>Frees bodies below it, headers kept ({@link MailStore#release}); the
     * body step replans first, a window moving only once released. Under the
     * sync lock; {@code done} runs on the main thread.
     */
    @Override
    public void releaseWindow(List<String> emails, String date, Runnable done) {
        host.io.execute(
                () -> {
                    replanBodies = true;
                    underSyncLock(
                            R.string.mail_window_release_failed,
                            () -> {
                                for (String email : emails) {
                                    int released = mail.release(email, date);
                                    MailWindow.set(host, passes.accountIdOf(email), date);
                                    MailWindow.dropMailboxes(host, passes.accountIdOf(email), date);
                                    Log.d(
                                            "pimalaya",
                                            "mail of " + email + ": " + released
                                                    + " freed");
                                }
                            });
                    host.postAlive(
                            () -> {
                                screens.reload(PimDomain.MAIL);
                                fillMail();
                                if (done != null) {
                                    done.run();
                                }
                            });
                });
    }

    /**
     * The activity in the foreground: fill and body step resume, Wi-Fi heard.
     */
    public void resumed() {
        // NOTE: the background fill stops when the app leaves the
        // foreground and picks up where its floors reached on return.
        foreground = true;
        android.net.ConnectivityManager connectivity =
                host.getSystemService(android.net.ConnectivityManager.class);
        if (connectivity != null) {
            unmetered = false;
            connectivity.registerDefaultNetworkCallback(wifiWatch);
        }
        fillMail();
    }

    /** The activity leaving the foreground: the fill stops, Wi-Fi unheard. */
    public void paused() {
        foreground = false;
        android.net.ConnectivityManager connectivity =
                host.getSystemService(android.net.ConnectivityManager.class);
        if (connectivity != null) {
            try {
                connectivity.unregisterNetworkCallback(wifiWatch);
            } catch (IllegalArgumentException unregistered) {
                // NOTE: never registered, the resume having failed early.
            }
        }
    }

    /** How many bodies an account's window still downloads, or null. */
    public Integer bodiesLeft(String email) {
        return bodiesLeft.get(email);
    }

    /**
     * Brings the books' Android accounts in line with the mirrored books.
     *
     * <p>Creating the missing ones, removing the rest, then the calendars'
     * ({@link #reconcileCalendars}), off main: at startup and after a setup.
     */
    public void reconcilePhone() {
        host.io.execute(
                () -> {
                    try {
                        Accounts.reconcile(host, BookEntry.accounts(passes.phoneSyncedBooks()));
                    } catch (Exception error) {
                        Log.w("pimalaya", "phone accounts failed", error);
                    }
                });
        reconcileCalendars();
    }

    /**
     * Brings the phone's calendars in line with those it shows, off main.
     *
     * <p>With the books, and when a calendar's switch or the account roster
     * changes ({@link CalendarRows}).
     */
    public void reconcileCalendars() {
        host.io.execute(
                () -> {
                    try {
                        CalendarRows.reconcile(host, pimdir);
                    } catch (Exception error) {
                        Log.w("pimalaya", "phone calendars failed", error);
                    }
                });
    }

    /**
     * The app back in the foreground: the phone pass of every mirrored book.
     *
     * <p>And calendar, so a phone edit shows at once; reloads when it or
     * Android's upload sync ({@code SyncService}, {@code CalendarSyncService})
     * brought one in. Silent: the sync lock is not taken.
     */
    public void phoneReturn() {
        host.io.execute(
                () -> {
                    ContactEngine.Report report = new ContactEngine.Report();
                    Exception failure = passes.syncPhone(report);
                    if (failure != null) {
                        Log.w("pimalaya", "phone pass on return failed", failure);
                    }
                    long ingested = screens.contactsIngested();
                    boolean changed =
                            !report.localIn.isEmpty()
                                    || !report.localOut.isEmpty()
                                    || !report.localChanged.isEmpty();
                    if (changed || ingested != reloadedIngest) {
                        reloadedIngest = ingested;
                        host.postAlive(() -> screens.reload(PimDomain.CONTACTS));
                    }

                    boolean events = passes.syncPhoneCalendars();
                    long calendarIngested = screens.calendarsIngested();
                    if (events || calendarIngested != reloadedCalendarIngest) {
                        reloadedCalendarIngest = calendarIngested;
                        host.postAlive(() -> screens.reload(PimDomain.CALENDAR));
                    }
                });
    }

    /**
     * A book's phone switch on, permission granted: its first projection.
     *
     * <p>Its Android account, then the projection, filling the dock unless a
     * sync holds it. The book's own lock is the pass's, not the process's.
     */
    public void showOnPhone(String url) {
        host.io.execute(
                () -> {
                    try {
                        Accounts.reconcile(host, BookEntry.accounts(passes.phoneSyncedBooks()));
                        ContactPhonePass engine = new ContactPhonePass(pimdir, clients);
                        engine.progress =
                                new PimdirEngine.Progress() {
                                    @Override
                                    public void step(PimDomain domain, int stage, int count) {
                                        phoneStep(domain, stage, count, 0);
                                    }

                                    @Override
                                    public void advance(
                                            PimDomain domain, int stage, int done, int total) {
                                        phoneStep(domain, stage, total, done);
                                    }
                                };
                        engine.sync(url, new ContactEngine.Report());
                    } catch (Exception error) {
                        Log.w("pimalaya", "first projection failed for " + url, error);
                    }
                    host.postAlive(() -> phoneDock(null, 0, 0));
                });
    }

    /**
     * Sets the dock from a first projection's stage (any thread).
     *
     * <p>Its bar filled to {@code done} of {@code count}.
     */
    private void phoneStep(PimDomain domain, int stage, int count, int done) {
        String text = host.getString(SyncSteps.lineOf(domain));
        boolean counted = stage == PimdirEngine.Progress.STAGE_PROJECT && done > 0;
        host.postAlive(() -> phoneDock(text, done, counted ? count : 0));
    }

    /** The dock during a first projection, a null line once it ended. */
    private void phoneDock(String text, int done, int total) {
        if (isSyncing() || screens.backgroundRunning()) {
            return;
        }
        if (text == null) {
            screens.dock().synced();
            return;
        }
        SyncDock.Pass pass = new SyncDock.Pass();
        pass.title = text;
        pass.done = done;
        pass.total = total;
        screens.dock().pass(pass);
    }

    /**
     * Books about to leave the phone: their last phone pass, off main.
     *
     * <p>Brings a Contacts-app edit into the store before the books' accounts
     * go, which take their raw contacts along; {@code done} learns on main
     * whether every pass ran.
     */
    public void lastPhonePass(List<String> urls, Consumer<Boolean> done) {
        host.io.execute(
                () -> {
                    boolean ran = true;
                    for (String url : urls) {
                        try {
                            new ContactPhonePass(pimdir, clients)
                                    .sync(url, new ContactEngine.Report());
                        } catch (Exception error) {
                            Log.w("pimalaya", "last phone pass failed for " + url, error);
                            ran = false;
                        }
                    }
                    boolean all = ran;
                    host.postAlive(() -> done.accept(all));
                });
    }

    /**
     * A book's phone switch off: one last phone pass, then its account goes.
     *
     * <p>The pass keeps a Contacts-app edit not yet in; the account takes its
     * raw contacts with it. The contacts stay in the store. A failed pass
     * keeps the account, so the edit waits for the next one.
     */
    public void hideFromPhone(String url) {
        host.io.execute(
                () -> {
                    try {
                        new ContactPhonePass(pimdir, clients)
                                .sync(url, new ContactEngine.Report());
                    } catch (Exception error) {
                        Log.w("pimalaya", "last phone pass failed for " + url + ", account kept", error);
                        return;
                    }
                    Accounts.remove(host, url);
                    host.postAlive(() -> screens.reload(PimDomain.CONTACTS));
                });
    }
}
