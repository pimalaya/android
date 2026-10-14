package org.pimalaya.sync;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.Accounts;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.pimalaya.calendar.CalendarEngine;
import org.pimalaya.calendar.CalendarPool;
import org.pimalaya.calendar.CalendarRows;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.SubmissionRefused;
import org.pimalaya.client.SubmissionUncertain;
import org.pimalaya.client.Transport;
import org.pimalaya.mail.MailBodies;
import org.pimalaya.mail.MailEngine;
import org.pimalaya.mail.MailPool;
import org.pimalaya.mail.MailStep;
import org.pimalaya.mail.MailStore;
import org.pimalaya.mail.MailTally;
import org.pimalaya.mail.MailWindow;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;

/**
 * The mail and calendar halves of a remote sync, the contacts half being
 * {@link SyncRunner}'s: every mail account's outbox drained and mailboxes
 * reconciled, every calendar account's calendars reconciled. The app runs
 * them behind its sync dock, the background check headless; neither needs
 * the other's screen. Everything blocks; callers run it off the main
 * thread.
 */
public final class RemotePass {
    /** What a pass reports as it goes, for the sync dock. */
    public interface Progress extends PimdirEngine.Progress {
        /** The pass moved on to one account, null between accounts. */
        void account(String email);

        /** How many of an account's mailboxes or calendars have landed. */
        void collections(PimDomain domain, int done, int total);
    }

    /** A pass nobody watches, the background check's. */
    public static final Progress HEADLESS =
            new Progress() {
                @Override
                public void account(String email) {}

                @Override
                public void collections(PimDomain domain, int done, int total) {}

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

    public RemotePass(
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
    public static final class MailPass {
        public Exception failure;
        public int sent;
    }

    /**
     * Every mail account's outbox drained and, with {@code listing}, its
     * mailboxes synced, on the calling thread: those the scope takes. A
     * pass lists nothing on a metered network, where the outbox goes out
     * all the same.
     */
    public MailPass mailPass(SyncScope scope, boolean listing) {
        progress.account(null);
        MailPass pass = new MailPass();
        List<String> queued = new ArrayList<>();
        for (MailStore.StoredMessage waiting : mail.outgoing()) {
            queued.add(waiting.accountEmail);
        }
        for (AccountEntry account : accountsFor(PimDomain.MAIL)) {
            if (!scope.account(account.email) || (!listing && !queued.contains(account.email))) {
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
                    Log.w("pimalaya", "outbox drain failed: " + Accounts.redacted(account.email), error);
                    if (pass.failure == null) {
                        pass.failure = error;
                    }
                }

                Exception error = listing ? fetchMail(account, session, scope) : null;
                if (pass.failure == null) {
                    pass.failure = error;
                }
            } catch (Exception error) {
                Log.w("pimalaya", "mail sync failed: " + Accounts.redacted(account.email), error);
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
     * <p>A failure has three answers. A message the server refused for good
     * is parked carrying what it said, and the drain carries on to the
     * ones behind it: nothing will ever make that message acceptable, and
     * leaving it queued would offer it again on every sync for ever. A
     * message handed over whole with no verdict back may have gone, so it
     * is parked as uncertain rather than sent again on its own, for the
     * user to send again once they find it did not go.
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
                Log.w("pimalaya", "submission refused: " + Accounts.redacted(account.email), refused);
                mail.parkOutgoing(waiting.id, refused.getMessage());
                continue;
            } catch (SubmissionUncertain uncertain) {
                // NOTE: the message may have gone: parked, never handed
                // over again unasked, and the drain stops here as for any
                // failure of the connection.
                Log.w("pimalaya", "submission uncertain: " + Accounts.redacted(account.email), uncertain);
                mail.parkUncertain(waiting.id, uncertain.getMessage());
                throw uncertain;
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
    public MailSession openMail(AccountEntry account) {
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
    public List<AccountEntry> accountsFor(PimDomain domain) {
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
    public Exception calendarPass(SyncScope scope) {
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
            Log.w("pimalaya", "mail sync failed: " + Accounts.redacted(account.email), error);
            return error;
        }

        // NOTE: how many there are before the first lands, so the bar fills
        // from the first count rather than from the first mailbox.
        progress.collections(PimDomain.MAIL, 0, collections.size());
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
                                    progress.collections(PimDomain.MAIL, done, total));
        }
        // NOTE: a mailbox the server refuses to open is skipped, unless every
        // one is (MailEngine.refused): the next roster drops it if it is gone.
        Exception failure = null;
        Exception refusal = null;
        for (MailPool.Failure failed : outcome.failures) {
            if (MailEngine.refused(failed.error)) {
                Log.w("pimalaya", "mailbox refused, skipped: " + failed.collection, failed.error);
                refusal = refusal == null ? failed.error : refusal;
            } else {
                Log.w("pimalaya", "mail sync failed: " + failed.collection, failed.error);
                failure = failure == null ? failed.error : failure;
            }
        }
        if (failure == null && refusal != null && outcome.failures.size() == collections.size()) {
            failure = refusal;
        }
        if (failure == null) {
            SyncStamps.mark(context, account.email);
        }
        return failure;
    }

    /**
     * The first sync of one mailbox the filter newly ticks, never listed,
     * on the calling thread: its first chunk of about 50 newest headers
     * ({@link MailEngine#widen}), the floor of what it reached its own
     * frontier, so the body step downloads their bodies and *Load more*
     * steps it from there. Answers what went wrong, or null.
     */
    public Exception firstOf(AccountEntry account, MailSession session, String collection) {
        progress.account(account.email);
        try {
            progress.step(PimDomain.MAIL, PimdirEngine.Progress.STAGE_SERVER, 0);
            progress.collections(PimDomain.MAIL, 0, 1);
            new MailEngine(pimdir, client, session, accountIdOf(account.email))
                    .widen(collection, MailStep.COUNT);
            MailWindow.moveBackMailbox(context, collection, mail.coverage(collection).since);
            progress.collections(PimDomain.MAIL, 1, 1);
            return null;
        } catch (Exception error) {
            Log.w("pimalaya", "mailbox first sync failed: " + collection, error);
            return error;
        }
    }

    /**
     * What *Load more* brings one account, on the calling thread: a step
     * ({@link #stepMail}) of the account or of the one mailbox {@code only}
     * names. The junk or the trash, which no step of its account brings, is
     * stepped by its label on Gmail and elsewhere widened by its next chunk
     * of headers ({@link MailEngine#widen}), its bodies left to opening.
     * Answers what went wrong, or null.
     */
    public Exception loadMore(AccountEntry account, MailSession session, String only) {
        if (only == null
                || MailBodies.downloads(mail.roles().get(only), false)
                || PimalayaClient.isGoogle(account.server(PimDomain.MAIL))) {
            return stepMail(account, session, only, false);
        }
        progress.account(account.email);
        try {
            progress.step(PimDomain.MAIL, PimdirEngine.Progress.STAGE_SERVER, 0);
            progress.collections(PimDomain.MAIL, 0, 1);
            new MailEngine(pimdir, client, session, accountIdOf(account.email))
                    .widen(only, MailStep.COUNT);
            progress.collections(PimDomain.MAIL, 1, 1);
            return null;
        } catch (Exception error) {
            Log.w("pimalaya", "mailbox widen failed: " + only, error);
            return error;
        }
    }

    /**
     * One step of one account's mail ({@link MailStep}) on its session, on
     * the calling thread: for the first sync ({@code first}) after its
     * mailboxes are read and stored, which opens every one of them; else
     * over the mailboxes stored, of the account or the one {@code only}
     * names. The account's frontier moves back to what it brought, all
     * mail once nothing older is left ({@link MailWindow}), or the one
     * mailbox's own when it stepped that alone. Answers what went wrong, or
     * null.
     */
    public Exception stepMail(AccountEntry account, MailSession session, String only, boolean first) {
        progress.account(account.email);
        String accountId = accountIdOf(account.email);
        try {
            progress.step(PimDomain.MAIL, PimdirEngine.Progress.STAGE_SERVER, 0);
            MailEngine engine = new MailEngine(pimdir, client, session, accountId);
            // NOTE: the step's bodies are its download, counted as their
            // files land: nothing before its listing ends, the bar then
            // indeterminate on the first account.
            engine.tally =
                    new MailTally(
                            (landed, planned) ->
                                    progress.advance(
                                            PimDomain.MAIL,
                                            PimdirEngine.Progress.STAGE_DOWNLOAD,
                                            landed,
                                            planned));
            List<String> collections = new ArrayList<>();
            List<Mailbox> mailboxes;
            if (first) {
                mailboxes = engine.mailboxes();
                mail.replaceMailboxes(account.email, mailboxes);
            } else {
                mailboxes = new ArrayList<>();
                for (PimdirCollections.Stored stored : mail.loadMailboxes(List.of(account.email))) {
                    // NOTE: the outbox is the app's queue, no mailbox the
                    // server knows.
                    if (stored.id.equals(mail.outboxOf(account.email))) {
                        continue;
                    }
                    mailboxes.add(new Mailbox(stored.name, stored.role));
                }
            }
            // NOTE: the account's step takes what the filter ticks, the inbox
            // alone to begin with off Gmail; a mailbox's own step holds by
            // every one of them.
            MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);
            for (Mailbox mailbox : MailEngine.ordered(mailboxes)) {
                String collection = mail.collectionOf(account.email, mailbox.name);
                if (only != null || filter.chosen(collection)) {
                    collections.add(collection);
                }
            }
            // NOTE: a Gmail step files what it brings in All Mail too, so an
            // archived message is held and never read again. A step over
            // the trash or the junk alone holds by that mailbox alone: what
            // lies there was taken out of every other one.
            String allMail = MailStore.allMailOf(accountId);
            if (only != null && !MailBodies.downloads(mail.roles().get(only), false)) {
                collections = new ArrayList<>(List.of(only));
            } else if (PimalayaClient.isGoogle(account.server(PimDomain.MAIL))) {
                mail.ensureAllMail(account.email);
                collections.add(allMail);
            }
            progress.collections(PimDomain.MAIL, 0, 1);
            MailStep.Outcome outcome =
                    MailStep.of(account.server(PimDomain.MAIL))
                            .run(engine, account.email, collections, only, first);
            progress.collections(PimDomain.MAIL, 1, 1);
            Log.d(
                    "pimalaya",
                    "mail step " + account.email + ": " + outcome.brought + " brought, frontier "
                            + outcome.frontier + (outcome.exhausted ? ", all held" : ""));

            MailWindow.stepped(context, accountId, only, outcome);
            SyncStamps.mark(context, account.email);
            return null;
        } catch (Exception error) {
            Log.w("pimalaya", "mail step failed: " + Accounts.redacted(account.email), error);
            return error;
        }
    }

    /**
     * One account's calendars reconciled with its server, on the calling
     * thread. Answers what went wrong, or null; a calendar whose pass
     * fails leaves the ones beside it alone.
     */
    public Exception fetchCalendars(AccountEntry account, SyncScope scope) {
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
                Log.w("pimalaya", "calendar list failed: " + Accounts.redacted(account.email), error);
                return error;
            }
            // NOTE: the roster the phone's calendars hang off, its first
            // listing included: a setup chooses before its calendars are known.
            try {
                CalendarRows.reconcile(context, pimdir);
            } catch (Exception error) {
                Log.w("pimalaya", "phone calendars failed: " + Accounts.redacted(account.email), error);
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
            progress.collections(PimDomain.CALENDAR, 0, calendars.size());
            String accountId = accountIdOf(account.email);
            AtomicInteger running = new AtomicInteger();
            AtomicInteger landed = new AtomicInteger();
            PimdirEngine.Progress alone = alone(running);
            Exception failure;
            try (CalendarPool<Transport> pool =
                    new CalendarPool<>(CalendarPool.SIZE, transport, Transport::new)) {
                CalendarPool.Outcome outcome =
                        pool.run(
                                account.email,
                                calendars,
                                (worker, collection, remote) -> {
                                    running.incrementAndGet();
                                    try {
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
                                                    engine.progress = alone;
                                                    try {
                                                        engine.sync(collection);
                                                    } finally {
                                                        remote.addAndGet(engine.remoteSoFar());
                                                    }
                                                    return null;
                                                });
                                    } finally {
                                        running.decrementAndGet();
                                        progress.collections(
                                                PimDomain.CALENDAR,
                                                landed.incrementAndGet(),
                                                calendars.size());
                                    }
                                });
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

    /**
     * The pass's progress for one account's calendar engines: every step,
     * but a step's count only while its calendar is the one {@code running},
     * so the bar never jumps between three downloads.
     */
    private PimdirEngine.Progress alone(AtomicInteger running) {
        return new PimdirEngine.Progress() {
            @Override
            public void step(PimDomain domain, int stage, int count) {
                progress.step(domain, stage, count);
            }

            @Override
            public void advance(PimDomain domain, int stage, int done, int total) {
                if (running.get() == 1) {
                    progress.advance(domain, stage, done, total);
                }
            }
        };
    }

    /** The store id one account's collections are namespaced under. */
    public String accountIdOf(String email) {
        if (accountIds == null) {
            accountIds = new PimdirAccount(context);
        }
        return accountIds.idOf(email);
    }

    /** The account-to-store-id map, opened the first time one is asked for. */
    private PimdirAccount accountIds;
}
