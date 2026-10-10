package org.pimalaya;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.SubmissionRefused;
import org.pimalaya.client.Transport;

/**
 * The mail and calendar halves of a remote sync, the contacts half being
 * {@link SyncRunner}'s: every mail account's outbox drained and mailboxes
 * reconciled, every calendar account's calendars reconciled. The app runs
 * them behind its sync strip, the background check headless; neither needs
 * the other's screen. Everything blocks; callers run it off the main
 * thread.
 */
final class RemotePass {
    /** What a pass reports as it goes, for the sync strip. */
    interface Progress extends PimdirEngine.Progress {
        /** The pass moved on to one account, null between accounts. */
        void account(String email);

        /** How many of an account's mailboxes have landed. */
        void mailboxes(int done, int total);
    }

    /** A pass nobody watches, the background check's. */
    static final Progress HEADLESS =
            new Progress() {
                @Override
                public void account(String email) {}

                @Override
                public void mailboxes(int done, int total) {}

                @Override
                public void step(PimDomain domain, int stage, int count) {}
            };

    private final Context context;
    private final PimdirDb pimdir;
    private final PimalayaClient client;
    private final SecureStore store;
    private final MailStore mail;
    private final EventStore events;
    private final SyncRunner runner;
    private final Progress progress;

    RemotePass(
            Context context,
            PimdirDb pimdir,
            PimalayaClient client,
            SecureStore store,
            MailStore mail,
            EventStore events,
            SyncRunner runner,
            Progress progress) {
        this.context = context;
        this.pimdir = pimdir;
        this.client = client;
        this.store = store;
        this.mail = mail;
        this.events = events;
        this.runner = runner;
        this.progress = progress;
    }

    /** What one mail pass over every account came to. */
    static final class MailPass {
        Exception failure;
        int sent;
    }

    /**
     * Every mail account's outbox drained and mailboxes synced, on the
     * calling thread: those the scope takes.
     */
    MailPass mailPass(SyncScope scope) {
        progress.account(null);
        MailPass pass = new MailPass();
        for (AccountEntry account : accountsFor(PimDomain.MAIL)) {
            if (!scope.account(account.email)) {
                continue;
            }
            progress.account(account.email);
            // One connection for the account's whole pass: the drain, the
            // walk and every marker the reader moved go out on it rather
            // than on one apiece.
            try (MailSession session = openMail(account)) {
                // NOTE: the outbox first, so a message sent a moment ago is
                // already in the sent mailbox by the time the walk beside it
                // lists one.
                try {
                    if (scope.collection(account.email, mail.outboxOf(account.email))) {
                        pass.sent += drainOutbox(account, session);
                    }
                } catch (Exception error) {
                    Log.w("pimalaya", "outbox drain failed: " + account.email, error);
                    if (pass.failure == null) {
                        pass.failure = error;
                    }
                }

                Exception error = fetchMail(account, session, scope);
                if (pass.failure == null) {
                    pass.failure = error;
                }
            } catch (Exception error) {
                Log.w("pimalaya", "mail sync failed: " + account.email, error);
                if (pass.failure == null) {
                    pass.failure = error;
                }
            }
        }
        return pass;
    }

    /**
     * Hands over everything waiting in one account's outbox, on the
     * calling thread, answering how many went out.
     *
     * <p>One at a time, in the append order the queue owes them, and the
     * row goes only once the submission has been accepted: the message
     * has left, so acknowledging it is what finishes the action (STORAGE
     * section 15.5). A submission is therefore at-least-once, and a drain
     * that dies between the handover and the acknowledgement sends the
     * message twice, which is the trade against losing it.
     *
     * <p>A failure has two answers. A message the server refused for good
     * is parked carrying what it said, and the drain carries on to the
     * ones behind it: nothing will ever make that message acceptable, and
     * leaving it queued would offer it again on every sync for ever.
     * Anything else counts an attempt and stops the drain where it is,
     * since the usual cause is that there is no network and the next
     * message would fail too.
     */
    private int drainOutbox(AccountEntry account, MailSession session) throws Exception {
        int sent = 0;
        for (MailStore.Outgoing waiting : mail.outgoing(account.email)) {
            String filed;
            try {
                filed = client.submitMessage(session, waiting.source);
            } catch (SubmissionRefused refused) {
                Log.w("pimalaya", "submission refused: " + account.email, refused);
                mail.parkOutgoing(waiting.id, refused.getMessage());
                continue;
            } catch (Exception error) {
                mail.retryOutgoing(waiting.id);
                throw error;
            }
            if (filed != null) {
                mail.aliasSentCopy(waiting.messageId, filed);
            }
            mail.acknowledge(waiting.id);
            sent += 1;
        }
        return sent;
    }

    /**
     * Opens one account's mail connection, renewing its access token once
     * if the server refuses the sign-in.
     *
     * <p>The refresh belongs here rather than around each verb, because
     * the session <em>is</em> the sign-in: a token that expired is a
     * connection that cannot be opened, and one already open outlives the
     * token that opened it.
     */
    MailSession openMail(AccountEntry account) {
        try {
            return PimalayaClient.openMail(account.server(PimDomain.MAIL));
        } catch (Exception refused) {
            AccountCredential credential = account.credential(PimDomain.MAIL);
            if (!SyncRunner.expiredToken(refused) || !credential.renewable()) {
                throw refused instanceof RuntimeException
                        ? (RuntimeException) refused
                        : new IllegalStateException(refused);
            }
            return PimalayaClient.openMail(
                    runner.refreshed(account, PimDomain.MAIL).server(PimDomain.MAIL));
        }
    }

    /** The stored accounts connected for one domain. */
    List<AccountEntry> accountsFor(PimDomain domain) {
        List<AccountEntry> matching = new ArrayList<>();
        for (AccountEntry entry : store.loadAll()) {
            if (entry.covers(domain)) {
                matching.add(entry);
            }
        }
        return matching;
    }

    /**
     * Every calendar account synced, on the calling thread: those the
     * scope takes. Answers the first failure.
     */
    Exception calendarPass(SyncScope scope) {
        progress.account(null);
        Exception failure = null;
        // NOTE: the calendar accounts, rather than the contacts accounts that
        // happened to be CalDAV-shaped. That filter was the closest thing to a
        // calendar account the app had before the connection flow could make
        // one, and it walked a CardDAV home looking for calendars.
        for (AccountEntry account : accountsFor(PimDomain.CALENDAR)) {
            if (!scope.account(account.email)) {
                continue;
            }
            Exception error = fetchCalendars(account, scope);
            if (failure == null) {
                failure = error;
            }
        }
        return failure;
    }

    /**
     * One account's mail reconciled with its server, on the calling
     * thread. Answers what went wrong, or null.
     *
     * <p>One walk, then one engine pass per mailbox off it: the walk is
     * what authenticates and what says which mailboxes there are, and
     * what a pass does instead of replacing is reconcile, which is what
     * lets a staged marker or a staged delete survive it.
     *
     * <p>The mailboxes then run side by side on the account's pool
     * ({@link MailPool}), in the pass's order, the inbox first: the wait is
     * the network's, mailbox after mailbox, and a few sessions wait on a few
     * mailboxes at once. The caller's session is the pool's first, so the
     * walk, the drain before it and the first worker share one connection.
     * One mailbox failing leaves the others running; the first failure is
     * what the pass reports.
     */
    Exception fetchMail(AccountEntry account, MailSession session, SyncScope scope) {
        progress.account(account.email);
        String accountId = accountIdOf(account.email);
        List<String> collections = new ArrayList<>();
        try {
            // NOTE: the step at once. Listing the mailboxes is a round trip,
            // and a dialog over a blank line for the length of one reads as
            // a dialog that has not started.
            progress.step(PimDomain.MAIL, PimdirEngine.Progress.STAGE_SERVER, 0);

            List<Mailbox> mailboxes =
                    new MailEngine(pimdir, client, session, accountId).mailboxes();
            mail.replaceMailboxes(account.email, mailboxes);

            // NOTE: the inbox first, then the sent mail, rather than in the
            // order the server lists them: a first pass lists one chunk of
            // each, and the inbox is what the list has to show first.
            for (Mailbox mailbox : MailEngine.ordered(mailboxes)) {
                // NOTE: a pass scoped to the filter skips what it hides. The
                // roster above is still read, so the filter keeps offering it.
                String collection = mail.collectionOf(account.email, mailbox.name);
                if (!scope.collection(account.email, collection)) {
                    continue;
                }
                collections.add(collection);
            }
        } catch (Exception error) {
            Log.w("pimalaya", "mail sync failed: " + account.email, error);
            return error;
        }

        MailPool.Outcome outcome;
        try (MailPool<MailSession> pool =
                new MailPool<>(
                        MailPool.sizeOf(account.server(PimDomain.MAIL)),
                        session,
                        () -> openMail(account))) {
            outcome =
                    pool.run(
                            account.email,
                            collections,
                            worker -> new MailEngine(pimdir, client, worker, accountId),
                            MailEngine::sync,
                            (done, total) ->
                                    progress.mailboxes(done, total));
        }
        for (MailPool.Failure failure : outcome.failures) {
            Log.w("pimalaya", "mail sync failed: " + failure.collection, failure.error);
        }
        if (outcome.failure() == null) {
            SyncStamps.mark(context, account.email);
        }
        return outcome.failure();
    }

    /**
     * One account's calendars reconciled with its server, on the calling
     * thread. Answers what went wrong, or null; a calendar whose pass
     * fails leaves the ones beside it alone.
     */
    Exception fetchCalendars(AccountEntry account, SyncScope scope) {
        progress.account(account.email);
        // NOTE: one session for the whole account, so the listing and every
        // event round after it share the token a refresh may have replaced
        // part-way; the listing's transport is then the first calendar
        // worker's.
        SyncRunner.Session session = runner.session(account, PimDomain.CALENDAR);
        try (Transport transport = new Transport()) {
            try {
                // NOTE: as the mail pass does, and for the same reason: the
                // calendar listing is a discovery walk, and it is the
                // slowest round trip of the pass.
                progress.step(PimDomain.CALENDAR, PimdirEngine.Progress.STAGE_SERVER, 0);
                events.replaceCalendars(
                        account.email,
                        session.call(server -> client.listCalendars(transport, server)));
            } catch (Exception error) {
                Log.w("pimalaya", "calendar list failed: " + account.email, error);
                return error;
            }
            // NOTE: the roster the phone's calendars hang off, its first
            // listing included: a setup chooses before its calendars are known.
            try {
                CalendarRows.reconcile(context, pimdir);
            } catch (Exception error) {
                Log.w("pimalaya", "phone calendars failed: " + account.email, error);
            }

            List<String> calendars = new ArrayList<>();
            for (EventStore.StoredCalendar calendar : events.loadCalendars()) {
                if (!calendar.accountEmail.equals(account.email)) {
                    continue;
                }
                if (!scope.collection(account.email, calendar.id)) {
                    continue;
                }
                calendars.add(calendar.id);
            }

            // NOTE: the calendars side by side, as the mailboxes are, the
            // listing's transport the first worker's: the wait was the
            // network's, one calendar after another.
            String accountId = accountIdOf(account.email);
            Exception failure;
            try (CalendarPool<Transport> pool =
                    new CalendarPool<>(CalendarPool.SIZE, transport, Transport::new)) {
                CalendarPool.Outcome outcome =
                        pool.run(
                                account.email,
                                calendars,
                                (worker, collection, remote) ->
                                        session.call(
                                                server -> {
                                                    CalendarEngine engine =
                                                            new CalendarEngine(
                                                                    pimdir,
                                                                    client,
                                                                    worker,
                                                                    server,
                                                                    accountId,
                                                                    account.email);
                                                    engine.progress = progress;
                                                    try {
                                                        engine.sync(collection);
                                                    } finally {
                                                        remote.addAndGet(engine.remoteSoFar());
                                                    }
                                                    return null;
                                                }));
                for (CalendarPool.Failure failed : outcome.failures) {
                    Log.w("pimalaya", "calendar sync failed: " + failed.collection, failed.error);
                }
                failure = outcome.failure();
            }
            if (failure == null) {
                SyncStamps.mark(context, account.email);
            }
            return failure;
        }
    }

    /** The store id one account's collections are namespaced under. */
    String accountIdOf(String email) {
        if (accountIds == null) {
            accountIds = new PimdirAccount(context);
        }
        return accountIds.idOf(email);
    }

    /** The account-to-store-id map, opened the first time one is asked for. */
    private PimdirAccount accountIds;
}
