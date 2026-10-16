package org.pimalaya.sync;

import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirEngine;
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

    private final List<Consumer<Boolean>> syncObservers = new ArrayList<>();

    /** Whether the activity is in the foreground: the fill runs only then. */
    private volatile boolean foreground;

    /** Whether a step of the background fill is queued or running. */
    private volatile boolean filling;

    /**
     * The fill's and the body step's session pools by account; io thread only.
     */
    private final Map<String, WorkerPool<MailSession>> fillPools = new HashMap<>();

    /** Whether the fill is done or failed for the loop under way; io thread. */
    private boolean fillStopped;

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
        if (syncing && !value) {
            pimdir.locks().releaseSync();
        }
        syncing = value;
        for (Consumer<Boolean> observer : syncObservers) {
            observer.accept(value);
        }
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
            host.showError(outcome.failure, R.string.sync_failed);
        }
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
                                    host.showError(pass.failure, R.string.sync_failed);
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
                                    host.showError(failure, R.string.sync_failed);
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
                                    host.showError(other, R.string.sync_failed);
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
            }
        }
        host.postAlive(() -> screens.land(landingOf(account)));
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
        List<AccountEntry> owing = new ArrayList<>();
        for (String email : FirstSync.owing(host, domain)) {
            for (AccountEntry account : passes.accountsFor(domain)) {
                // NOTE: an account that is off keeps owing it.
                if (account.email.equals(email) && AccountActivation.enabled(host, email)) {
                    owing.add(account);
                }
            }
        }
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
                                    host.showError(outcome, R.string.sync_failed);
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
                                    host.showError(outcome, R.string.sync_failed);
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
                                    host.showError(outcome, R.string.sync_failed);
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
     * Starts the background fill when allowed and not running.
     *
     * <p>An io task per {@link MailEngine#FILL_CHUNK} chunk, bodies first
     * ({@link MailBodies}). Stops off the foreground or Wi-Fi, on a sync or
     * failure, resuming from the store's floors; Gmail is not filled.
     */
    public void fillMail() {
        replanBodies = true;
        if (filling || !foreground || isSyncing() || !wifi()) {
            return;
        }
        filling = true;
        host.io.execute(this::fillStep);
    }

    /** Has the body step plan again at its next step. */
    public void replanBodies() {
        replanBodies = true;
    }

    /** One step of the body step or the fill (io thread), the next queued. */
    private void fillStep() {
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

        MailFill.Step step = null;
        if (body != MailBodies.Step.AGAIN) {
            step = fillStopped ? MailFill.Step.DONE : MailFill.step(fillHost());
            Log.d("pimalaya", "mail fill: " + step);
            if (step == MailFill.Step.DONE || step == MailFill.Step.FAILED) {
                fillStopped = true;
            }
            // NOTE: a chunk listed above a window brings bodies to plan.
            if (step == MailFill.Step.AGAIN && bodies.drained()) {
                replanBodies = true;
            }
        }

        if (step == MailFill.Step.AGAIN || body == MailBodies.Step.AGAIN) {
            // NOTE: the glyph once a step worked, never for a loop that
            // found nothing to do.
            host.postAlive(() -> screens.dock().filling(true));
            host.io.execute(this::fillStep);
            return;
        }
        closeFillPools();
        bodies = null;
        fillStopped = false;
        filling = false;
        bodiesTally.reset();
        host.postAlive(() -> screens.dock().filling(false));
        if (!bodiesLeft.isEmpty()) {
            bodiesLeft.clear();
            host.postAlive(screens::bodiesChanged);
        }
    }

    /** The fill step's reach: what it may do now, the mailboxes, the pools. */
    private MailFill.Host fillHost() {
        return new MailFill.Host() {
            @Override
            public boolean allowed() {
                return fillAllowed();
            }

            @Override
            public List<MailStore.Edge> edges() {
                // NOTE: an account that is off syncs nothing, the
                // background fill included, nor does a mailbox the filter
                // unticks; one stepped through account-wide (Gmail) holds
                // what its steps brought.
                MergedFilter filter = MergedFilter.of(host, PimDomain.MAIL);
                List<MailStore.Edge> edges = mail.edges();
                edges.removeIf(
                        edge ->
                                edge.accountWide
                                        || !filter.chosen(edge.collection)
                                        || !AccountActivation.enabled(
                                                host, edge.accountEmail));
                return edges;
            }

            @Override
            public int room(String accountEmail) {
                AccountEntry account = mailAccount(accountEmail);
                return account == null
                        ? 1
                        : account.server(PimDomain.MAIL).info().sessions();
            }

            @Override
            public void widen(MailStore.Edge edge) throws Exception {
                widen(List.of(edge));
            }

            @Override
            public void widen(List<MailStore.Edge> edges) throws Exception {
                Exception failure =
                        listAll(
                                edges,
                                (engine, collection) ->
                                        engine.widen(collection, MailEngine.FILL_CHUNK),
                                "mail fill stopped: ");
                if (failure != null) {
                    throw failure;
                }
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
     * Downloads some of an account's bodies on its fill pool, blocking.
     *
     * <p>Rows split across the pool's sessions; answers those not landed. A
     * failure closes the pool, so a retry runs on fresh sessions (renewing a
     * token); every job failing throws.
     */
    private List<MailBodies.Row> downloadBodies(String accountEmail, List<MailBodies.Row> rows)
            throws Exception {
        AccountEntry account = mailAccount(accountEmail);
        if (account == null) {
            return List.of();
        }
        WorkerPool<MailSession> pool = fillPool(account);

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
            fillPools.remove(account.email);
            pool.close();
        }
        if (!keys.isEmpty() && outcome.failures.size() == keys.size()) {
            throw outcome.failure();
        }
        return failed;
    }

    /**
     * Runs {@code step} over mailboxes on the io thread, every account at once.
     *
     * <p>Each account's on its kept {@link WorkerPool}, in the order given.
     * Answers the first failure, each logged under {@code failed}; a failing
     * mailbox leaves the others going.
     */
    private Exception listAll(List<MailStore.Edge> edges, MailboxStep step, String failed) {
        Map<String, List<String>> byAccount = new java.util.LinkedHashMap<>();
        for (MailStore.Edge edge : edges) {
            byAccount.computeIfAbsent(edge.accountEmail, email -> new ArrayList<>())
                    .add(edge.collection);
        }

        List<java.util.concurrent.Callable<WorkerPool.Outcome>> runs = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byAccount.entrySet()) {
            AccountEntry account = mailAccount(entry.getKey());
            if (account == null) {
                continue;
            }
            WorkerPool<MailSession> held = fillPool(account);
            String accountId = passes.accountIdOf(account.email);
            runs.add(
                    () ->
                            held.run(
                                    "mail pass " + account.email,
                                    entry.getValue(),
                                    (session, collection, remote) -> {
                                        MailEngine engine =
                                                new MailEngine(pimdir, clients, session, accountId);
                                        try {
                                            step.run(engine, collection);
                                        } finally {
                                            remote.addAndGet(engine.remoteSoFar());
                                        }
                                    }));
        }

        Exception first = null;
        for (WorkerPool.Outcome outcome : WorkerPool.together(runs)) {
            for (WorkerPool.Failure failure : outcome.failures) {
                Log.w("pimalaya", failed + failure.key, failure.error);
            }
            if (first == null) {
                first = outcome.failure();
            }
        }
        return first;
    }

    /** What a run does to one mailbox: a pass, a widening by a chunk. */
    private interface MailboxStep {
        void run(MailEngine engine, String collection) throws Exception;
    }

    /** An account's pool for the fill and the body step; on the io thread. */
    private WorkerPool<MailSession> fillPool(AccountEntry account) {
        WorkerPool<MailSession> pool = fillPools.get(account.email);
        if (pool == null) {
            pool =
                    new WorkerPool<>(
                            account.server(PimDomain.MAIL).info().sessions(),
                            null,
                            () -> passes.openMail(account),
                            MailSession::join);
            fillPools.put(account.email, pool);
        }
        return pool;
    }

    /** Closes the connections the fill and body step kept; io thread. */
    private void closeFillPools() {
        for (WorkerPool<MailSession> pool : fillPools.values()) {
            pool.close();
        }
        fillPools.clear();
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
                                    host.showError(outcome, R.string.sync_failed);
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
