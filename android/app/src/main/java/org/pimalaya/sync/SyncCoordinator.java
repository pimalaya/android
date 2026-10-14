package org.pimalaya.sync;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.pimalaya.R;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SyncScope;
import org.pimalaya.mail.MailList;
import org.pimalaya.mail.MailStore;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.SyncDock;

/**
 * The app's foreground sync: one pass at a time, run on the host's io
 * thread, its progress on the sync dock.
 */
public final class SyncCoordinator {
    /** What only the activity does for a sync. */
    public interface Screens {
        /** The sync dock, built after the coordinator. */
        SyncDock dock();

        /** Reads a domain's list again after a pass (main thread). */
        void reload(PimDomain domain);

        /** Keeps the account cache current after a token refresh (any thread). */
        void renewed(AccountEntry updated);

        /** Whether a background run holds the sync. */
        boolean backgroundRunning();

        /** Whether the network is Wi-Fi, the mail listing's condition. */
        boolean wifi();

        /** Runs {@code then} once Wi-Fi is back, the dock saying it waits. */
        void waitForWifi(Runnable then);

        /** Resumes the background fill and the body download. */
        void fillMail();
    }

    private final Host host;

    private final Screens screens;

    private final PimdirDb pimdir;

    private final MailStore mail;

    /** The dock's model of a pass, which the passes report into. */
    private final SyncProgress progress;

    /** Every remote and phone pass, behind the sync dock. */
    private final SyncPasses passes;

    /**
     * The shared sync state: true while a sync runs. Every UI element
     * that reflects it (the lists' sync dock, the drawer's sync row)
     * subscribes through {@link #observe}, and {@link #setSyncing}
     * pushes the flag to all of them at once.
     */
    private volatile boolean syncing;

    private final List<Consumer<Boolean>> syncObservers = new ArrayList<>();

    public SyncCoordinator(Host host, SyncPasses.Parts parts, Screens screens) {
        this.host = host;
        this.screens = screens;
        this.pimdir = parts.pimdir();
        this.mail = parts.mail();
        progress =
                new SyncProgress(
                        host,
                        parts.cards(),
                        this::passes,
                        screens::dock,
                        () -> syncing,
                        screens::renewed);
        passes = new SyncPasses(host, parts, progress);
    }

    /** Every remote and phone pass. */
    public SyncPasses passes() {
        return passes;
    }

    /** The dock's model of a pass. */
    public SyncProgress progress() {
        return progress;
    }

    /** Each domain's filter, the shared one the list shows. */
    private MergedFilter filterOf(PimDomain domain) {
        return MergedFilter.of(host, domain);
    }

    /** Pushes the sync flag to every subscribed element (the lists'
     *  sync dock, the drawer's inert sync row). */
    /**
     * Starts a pass: takes the process's sync lock, turning the pass down
     * while a background run holds it, whose dock already says so.
     */
    public boolean startSync() {
        if (!pimdir.locks().takeSync()) {
            return false;
        }
        progress.resetSyncPlan();
        setSyncing(true);
        return true;
    }

    public void setSyncing(boolean value) {
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
     * Whether the sync is held, by a pass of the app's or a background run:
     * a step asked for now would do nothing.
     */
    public boolean syncHeld() {
        return syncing || screens.backgroundRunning();
    }

    /**
     * Subscribes an element to the sync state and hands it the current
     * value straight away, so it starts consistent.
     */
    public void observe(Consumer<Boolean> observer) {
        syncObservers.add(observer);
        observer.accept(syncing);
    }

    /**
     * Store-to-remote spoke: per addressbook, fetches the remote into
     * the store, pushes the staged local changes, and re-fetches the
     * pushed state. The phone is not touched; that is the local sync.
     * The shared syncing state drives the dock in the lists' app bar.
     */
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
     * Surfaces a failed sync in an error dialog. A pass that went through
     * says nothing: the lists show what it brought, and a conflict marks
     * its own row.
     */
    public void reportSync(SyncPasses.Outcome outcome) {
        if (outcome.failure != null) {
            // NOTE: the last sync's store keeps failing addressbooks usable.
            host.showError(outcome.failure, R.string.sync_failed);
        }
    }

    /**
     * The mail list's pull: the mail of the accounts and mailboxes the
     * filter shows, into the merged list.
     *
     * <p>The endpoint is the one the account was connected with, which is what
     * the mail domain of the connection flow exists to establish. It used to be
     * re-discovered on every refresh from a contacts account's address, because
     * there was no way to connect a mail account at all; that guessed at both
     * the server and the credentials, and worked only where the two domains
     * happened to share them.
     *
     * <p>Under a role chip ({@code role}, null for none) the pull syncs that
     * role's mailboxes of the shown accounts alone, ticked or not, as the
     * list shows them ({@link MailList#narrowed}).
     */
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
        boolean listing = screens.wifi();
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
                                    screens.waitForWifi(() -> syncMail(role));
                                }
                                screens.fillMail();
                            });
                });
    }

    /**
     * The agenda's pull: the calendars the filter shows, and their events.
     *
     * <p>Read-only and whole-collection: CalDAV's ctag and sync-token
     * rounds are what an incremental pass would use, and neither is
     * wired yet, so a refresh relists. One report carries a whole
     * calendar's objects, so this is one round trip per calendar.
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
     * Runs a store-wide write on the io thread while no sync runs: the
     * process's sync lock taken around it, so a background run (on a
     * thread of its own) never writes the same rows meanwhile. Turned down,
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
     * The contacts list's pull: the addressbooks the filter shows,
     * reconciled with their servers, each book's phone passes around its
     * server one. The drawer's sync is the one that takes every domain and
     * everything.
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
     * Syncs every domain in turn, contacts then mail then calendars, the
     * dialog naming each as it goes: the drawer's sync, which takes every
     * collection of every account that is on whatever the filters hide,
     * where a list's pull takes its own domain within its filter.
     */
    public void syncAll() {
        if (syncing) {
            return;
        }

        progress.syncAccount(null);
        if (!startSync()) {
            return;
        }
        boolean listing = screens.wifi();
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
                                    screens.waitForWifi(() -> syncMail(null));
                                }
                                screens.fillMail();
                                // NOTE: one error dialog, the contacts one first.
                                if (other != null && outcome.failure == null) {
                                    host.showError(other, R.string.sync_failed);
                                }
                            });
                });
    }
}
