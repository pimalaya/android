package org.pimalaya.sync;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.LocalBook;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.pimalaya.account.SyncScope;
import org.pimalaya.account.TokenRenewal;
import org.pimalaya.calendar.CalendarEngine;
import org.pimalaya.calendar.CalendarRows;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.Clients;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.OauthGrant;
import org.pimalaya.client.OauthTokens;
import org.pimalaya.client.SubmissionRefused;
import org.pimalaya.client.SubmissionUncertain;
import org.pimalaya.client.Transport;
import org.pimalaya.contact.BookEntry;
import org.pimalaya.contact.CardStore;
import org.pimalaya.contact.ContactEngine;
import org.pimalaya.contact.ContactPhonePass;
import org.pimalaya.mail.MailBodies;
import org.pimalaya.mail.MailEngine;
import org.pimalaya.mail.MailStep;
import org.pimalaya.mail.MailStore;
import org.pimalaya.mail.MailTally;
import org.pimalaya.mail.MailWindow;
import org.pimalaya.phone.PhoneMirror;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirEngine;
import org.pimalaya.pimdir.PimdirSummary;

/**
 * Every remote and phone pass, headless and blocking: the contacts, mail
 * and calendar halves of a sync and the phone spokes. The account lookup,
 * the addressbook self-heal and the refresh-once-and-retry dance exist
 * exactly once. The caller keeps what is its alone: MainActivity the sync
 * dock and its in-memory account cache (fed by the progress), the
 * background check nothing ({@link #HEADLESS}). Everything blocks; callers
 * run it off the main thread.
 */
public final class SyncPasses {
    /**
     * What a pass reports as it goes, for the sync dock and the account
     * cache; every hook fires on the sync thread.
     */
    public interface Progress extends PimdirEngine.Progress {
        /** The pass moved on to one account, null between accounts. */
        void account(String email);

        /** How many of an account's address books, mailboxes or calendars have landed. */
        void collections(PimDomain domain, int done, int total);

        /** A token refresh re-persisted the account's credentials. */
        default void accountRefreshed(AccountEntry updated) {}
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

    /** What the passes read and write. */
    public record Parts(
            PimdirDb pimdir,
            CardStore cards,
            SecureStore accounts,
            MailStore mail,
            EventStore events,
            Clients clients) {}

    /**
     * Outcome of a remote run: what was pulled from the server, pushed
     * to it, and left conflicted awaiting the user's manual resolution,
     * plus the first failure. The phone passes it ran are not reported:
     * the phone is a view of the store, not a sync.
     */
    public static final class Outcome {
        final Set<String> remoteIn = new HashSet<>();
        final Set<String> remoteOut = new HashSet<>();
        final Set<String> remoteChanged = new HashSet<>();
        int conflicts;

        public Exception failure;

        /** Folds one book's report in; the sets dedupe across passes. */
        void absorb(ContactEngine.Report report) {
            remoteIn.addAll(report.remoteIn);
            remoteOut.addAll(report.remoteOut);
            remoteChanged.addAll(report.remoteChanged);
            conflicts += report.conflicts;
        }
    }

    /** What one mail pass over every account came to. */
    public static final class MailPass {
        public Exception failure;
        public int sent;
    }

    private final Context context;
    private final CardStore base;
    private final PimdirDb pimdir;
    private final SecureStore store;
    private final Clients clients;
    private final MailStore mail;
    private final EventStore events;
    private final Progress progress;

    /** What the passes read and write, the background check reading it too. */
    final Parts parts;

    public SyncPasses(Context context, Parts parts, Progress progress) {
        this.parts = parts;
        this.context = context;
        this.base = parts.cards();
        this.pimdir = parts.pimdir();
        this.store = parts.accounts();
        this.clients = parts.clients();
        this.mail = parts.mail();
        this.events = parts.events();
        this.progress = progress;
    }

    /**
     * The full remote run, the in-app sync's unit: self-heals accounts
     * whose addressbooks a schema rebuild dropped, then reconciles
     * every subscribed book account by account, one engine per account
     * so the account-level backends (JMAP, Google) list their cards
     * once per pass. An expired OAuth access token refreshes and
     * retries its account once; one account failing (revoked token,
     * server down) never blocks the others, the first failure riding
     * the outcome instead.
     */
    public Outcome syncRemote() {
        return syncRemote(SyncScope.all(context));
    }

    /**
     * The remote run narrowed to a scope: the books of the accounts and
     * collections it takes.
     */
    public Outcome syncRemote(SyncScope scope) {
        Outcome outcome = new Outcome();

        // NOTE: self-heal an account whose addressbooks a schema rebuild
        // dropped by re-fetching them all-subscribed. The local account
        // never appears here (synthesized in memory, seeded on launch).
        Set<String> known = new HashSet<>();
        for (BookEntry entry : base.loadAllAddressbooks()) {
            known.add(entry.accountEmail);
        }
        // NOTE: only the accounts that cover contacts. An account connected
        // for mail alone has no address books to re-fetch, and asking its
        // server for some would fail once per sync.
        for (AccountEntry account : store.loadAll()) {
            if (!account.covers(PimDomain.CONTACTS)
                    || known.contains(account.email)
                    || !scope.account(account.email)) {
                continue;
            }
            try {
                List<Addressbook> books;
                try (Transport transport = new Transport()) {
                    books =
                            clients.contact().listAddressbooks(
                                    transport, account.server(PimDomain.CONTACTS));
                }
                base.replaceAddressbooks(account.email, books);
                new PimdirCollections(pimdir, context)
                        .replace(
                                account.email,
                                PimdirSummary.CONTACT,
                                PimdirCollections.of(account.email, books));
            } catch (Exception error) {
                Log.w("pimalaya", "addressbook recovery failed", error);
            }
        }

        Map<String, List<BookEntry>> byAccount = new LinkedHashMap<>();
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            if (!scope.collection(entry.accountEmail, entry.book.url())) {
                continue;
            }
            byAccount
                    .computeIfAbsent(entry.accountEmail, email -> new ArrayList<>())
                    .add(entry);
        }

        for (Map.Entry<String, List<BookEntry>> group : byAccount.entrySet()) {
            if (LocalBook.is(group.getKey())) {
                continue;
            }
            AccountEntry entry = entryFor(group.getKey());
            if (entry == null) {
                continue;
            }

            AccountCredential contacts = entry.credential(PimDomain.CONTACTS);
            try {
                syncAccount(
                        entry.email, entry.server(PimDomain.CONTACTS), group.getValue(), outcome);
                SyncStamps.mark(context, entry.email);
            } catch (Exception error) {
                if (PimdirEngine.expiredToken(error) && contacts.renewable()) {
                    try {
                        syncAccount(entry.email, refresh(entry), group.getValue(), outcome);
                        SyncStamps.mark(context, entry.email);
                        continue;
                    } catch (Exception retryError) {
                        error = retryError;
                    }
                }

                // NOTE: one account failing (revoked token, server down)
                // must not block the others.
                Log.w("pimalaya", "sync failed for " + group.getKey(), error);
                if (outcome.failure == null) {
                    outcome.failure = error;
                }
            }
        }

        return outcome;
    }

    /**
     * The phone spoke alone, offline and silent (no progress steps): the
     * two-way phone pass of every mirrored book, tallying into the report,
     * each skipping on its quiet path when neither side changed. Returns a
     * failure, or null. A book with no Android account or no contacts
     * permission is skipped.
     */
    public Exception syncPhone(ContactEngine.Report report) {
        try {
            ContactPhonePass engine = new ContactPhonePass(pimdir, clients);
            for (BookEntry entry : phoneSyncedBooks()) {
                engine.sync(entry.book.url(), report);
            }
        } catch (Exception error) {
            return error;
        }
        return null;
    }

    /**
     * The calendars' phone spoke alone, offline and silent: the phone pass of
     * every calendar the phone shows, each skipping on its quiet path.
     * Answers whether any brought a calendar-app edit in. Nothing without the
     * calendar permission.
     */
    public boolean syncPhoneCalendars() {
        if (!PhoneMirror.CALENDAR.granted(context)) {
            return false;
        }
        boolean changed = false;
        for (String collection : CalendarRows.shown(context)) {
            changed |= CalendarRows.phonePass(pimdir, collection);
        }
        return changed;
    }

    /** The subscribed books set to mirror into the phone's contacts. */
    public List<BookEntry> phoneSyncedBooks() {
        List<BookEntry> phone = new ArrayList<>();
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            if (entry.phoneSynced) {
                phone.add(entry);
            }
        }
        return phone;
    }

    /**
     * One account's engine pass: every one of its subscribed books
     * reconciles through io-offline (spine sync, body hydration,
     * conflict resolution), sharing one driver so the account-level
     * backends list their cards once per pass.
     *
     * <p>The books are listed first, for what the source says of each:
     * which one is the default and which ones are read only. The roster
     * itself is left as it is, the switches hanging off it.
     */
    private void syncAccount(
            String email, Account account, List<BookEntry> books, Outcome outcome)
            throws Exception {
        if (progress != null) {
            progress.account(email);
        }
        try (Transport primary = new Transport()) {
            try {
                new PimdirCollections(pimdir, context)
                        .restate(
                                PimdirCollections.of(
                                        email, clients.contact().listAddressbooks(primary, account)));
            } catch (Exception error) {
                // NOTE: a 401 is the pass's to refresh on, anything else
                // leaves the books as last stated.
                if (PimdirEngine.expiredToken(error)) {
                    throw error;
                }
                Log.w("pimalaya", "addressbook roles failed for " + AccountEntry.redacted(email), error);
            }

            ContactEngine engine = engine(primary, account);
            if (progress != null) {
                progress.collections(PimDomain.CONTACTS, 0, books.size());
            }

            for (int index = 0; index < books.size(); index++) {
                BookEntry entry = books.get(index);
                outcome.absorb(engine.syncBook(entry.book.url(), entry.remoteSynced));
                if (progress != null) {
                    progress.collections(PimDomain.CONTACTS, index + 1, books.size());
                }
            }
        }
    }

    /** An engine wired to the pass's progress display, steps and counts. */
    private ContactEngine engine(Transport primary, Account account) {
        ContactEngine engine = new ContactEngine(base, pimdir, clients, primary, account);
        if (progress != null) {
            engine.progress = progress;
        }
        return engine;
    }

    /** Refreshes the contacts token and returns the fresh credentials. */
    private Account refresh(AccountEntry entry) {
        return refreshed(entry, PimDomain.CONTACTS).server(PimDomain.CONTACTS);
    }

    /**
     * Renews one domain's credential and re-persists the account,
     * returning the updated entry.
     *
     * <p>Whichever domain asks, the credential is renewed once and
     * every domain that signs in with it follows, because a credential
     * is stored once per consent rather than copied per domain
     * ({@link AccountCredential}). A provider that issues a fresh
     * refresh token on every use retires the old one immediately, so
     * copies were exactly what could not be kept in step.
     */
    AccountEntry refreshed(AccountEntry entry, PimDomain domain) {
        // NOTE: one renewal per credential at a time, across the workers of a
        // pass: a rotating provider retires a refresh token on its first use
        // (TokenRenewal). One write, and every domain signing in with this
        // credential is renewed by it, there being only the one copy.
        AccountEntry updated =
                TokenRenewal.renew(
                        store,
                        entry,
                        domain,
                        credential -> {
                            try (Transport transport = new Transport()) {
                                OauthGrant grant = credential.renewal.grant();
                                // NOTE: the refresh names no scope, keeping
                                // the server's default.
                                OauthTokens tokens =
                                        clients.account().refresh(
                                                transport,
                                                new OauthGrant(
                                                        grant.tokenEndpoint(),
                                                        grant.clientId(),
                                                        grant.clientSecret(),
                                                        null),
                                                credential.refreshToken());
                                return new TokenRenewal.Renewed(
                                        tokens.accessToken(), tokens.refreshToken());
                            }
                        });

        if (progress != null) {
            progress.accountRefreshed(updated);
        }
        return updated;
    }

    /**
     * One account's calls in one domain, with its access token kept
     * fresh: a call answered 401 refreshes the token once and runs
     * again, and every later call in the scope uses the refreshed
     * connection.
     *
     * <p>The read-only domains need this exactly as much as contacts
     * does. An access token lives about an hour, so without it the
     * second sync of any OAuth mail or calendar account of a session
     * fails and keeps failing, with a stored refresh token sitting
     * unused beside it.
     */
    final class Session {
        private AccountEntry account;
        private final PimDomain domain;

        private Session(AccountEntry account, PimDomain domain) {
            this.account = account;
            this.domain = domain;
        }

        /**
         * Runs {@code call}, refreshing once on a 401. Callable from several
         * threads at once (an account's calendars run side by side): the
         * refresh is made once, by whichever call saw the token it replaces,
         * and the others retry on the token it made, since a provider
         * issuing a fresh refresh token on every use retires the old one.
         */
        <T> T call(Call<T> call) throws Exception {
            AccountEntry used = current();
            try {
                return call.on(used.server(domain));
            } catch (Exception error) {
                if (!PimdirEngine.expiredToken(error) || !used.credential(domain).renewable()) {
                    throw error;
                }
                return call.on(renewedSince(used).server(domain));
            }
        }

        private synchronized AccountEntry current() {
            return account;
        }

        /** The account renewed past {@code used}, renewing it unless another call has. */
        private synchronized AccountEntry renewedSince(AccountEntry used) {
            if (account == used) {
                account = refreshed(account, domain);
            }
            return account;
        }
    }

    /** One call against a domain's endpoint, retryable after a refresh. */
    interface Call<T> {
        T on(Account server) throws Exception;
    }

    /** A token-refreshing scope for one account's domain. */
    Session session(AccountEntry account, PimDomain domain) {
        return new Session(account, domain);
    }

    /**
     * The stored contacts account for an address, or null.
     *
     * <p>Scoped to the domain because one address can be connected for several,
     * and this runner syncs address books: matching on the address alone could
     * hand back a mail account and sync a mailbox as a book.
     */
    private AccountEntry entryFor(String email) {
        for (AccountEntry entry : store.loadAll()) {
            if (entry.covers(PimDomain.CONTACTS) && entry.email.equals(email)) {
                return entry;
            }
        }
        return null;
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
                    Log.w("pimalaya", "outbox drain failed: " + AccountEntry.redacted(account.email), error);
                    if (pass.failure == null) {
                        pass.failure = error;
                    }
                }

                Exception error = listing ? fetchMail(account, session, scope) : null;
                if (pass.failure == null) {
                    pass.failure = error;
                }
            } catch (Exception error) {
                Log.w("pimalaya", "mail sync failed: " + AccountEntry.redacted(account.email), error);
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
                filed = clients.mail().submit(session, waiting.source);
            } catch (SubmissionRefused refused) {
                Log.w("pimalaya", "submission refused: " + AccountEntry.redacted(account.email), refused);
                mail.parkOutgoing(waiting.id, refused.getMessage());
                continue;
            } catch (SubmissionUncertain uncertain) {
                // NOTE: the message may have gone: parked, never handed
                // over again unasked, and the drain stops here as for any
                // failure of the connection.
                Log.w("pimalaya", "submission uncertain: " + AccountEntry.redacted(account.email), uncertain);
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
            return MailClient.openMail(account.server(PimDomain.MAIL));
        } catch (Exception refused) {
            AccountCredential credential = account.credential(PimDomain.MAIL);
            if (!PimdirEngine.expiredToken(refused) || !credential.renewable()) {
                throw refused instanceof RuntimeException
                        ? (RuntimeException) refused
                        : new IllegalStateException(refused);
            }
            return MailClient.openMail(
                    refreshed(account, PimDomain.MAIL).server(PimDomain.MAIL));
        }
    }

    /** The stored accounts connected for one domain ({@link SecureStore#accountsFor}). */
    public List<AccountEntry> accountsFor(PimDomain domain) {
        return store.accountsFor(domain);
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
     * ({@link WorkerPool}), in the pass's order, the inbox first: the wait is
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
                    new MailEngine(pimdir, clients, session, accountId).mailboxes();
            mail.replaceMailboxes(account.email, mailboxes);

            // NOTE: the inbox first, then the sent mail, rather than in the
            // order the server lists them: a first pass lists one chunk of
            // each, and the inbox is what the list has to show first.
            for (Mailbox mailbox : MailEngine.ordered(mailboxes)) {
                // NOTE: a pass scoped to the filter skips what it hides. The
                // roster above is still read, so the filter keeps offering it.
                String collection = mail.collectionOf(account.email, mailbox.name());
                if (!scope.collection(account.email, collection)) {
                    continue;
                }
                collections.add(collection);
            }
        } catch (Exception error) {
            Log.w("pimalaya", "mail sync failed: " + AccountEntry.redacted(account.email), error);
            return error;
        }

        // NOTE: how many there are before the first lands, so the bar fills
        // from the first count rather than from the first mailbox.
        progress.collections(PimDomain.MAIL, 0, collections.size());
        AtomicInteger landed = new AtomicInteger();
        WorkerPool.Outcome outcome;
        try (WorkerPool<MailSession> pool =
                new WorkerPool<>(
                        MailEngine.sessionsOf(account.server(PimDomain.MAIL)),
                        session,
                        () -> openMail(account),
                        MailSession::join)) {
            outcome =
                    pool.run(
                            "mail pass " + account.email,
                            collections,
                            (worker, collection, remote) -> {
                                MailEngine engine =
                                        new MailEngine(pimdir, clients, worker, accountId);
                                try {
                                    engine.sync(collection);
                                } finally {
                                    remote.addAndGet(engine.remoteSoFar());
                                    progress.collections(
                                            PimDomain.MAIL,
                                            landed.incrementAndGet(),
                                            collections.size());
                                }
                            });
        }
        // NOTE: a mailbox the server refuses to open is skipped, unless every
        // one is (MailEngine.refused): the next roster drops it if it is gone.
        Exception failure = null;
        Exception refusal = null;
        for (WorkerPool.Failure failed : outcome.failures) {
            if (MailEngine.refused(failed.error)) {
                Log.w("pimalaya", "mailbox refused, skipped: " + failed.key, failed.error);
                refusal = refusal == null ? failed.error : refusal;
            } else {
                Log.w("pimalaya", "mail sync failed: " + failed.key, failed.error);
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
            new MailEngine(pimdir, clients, session, accountIdOf(account.email))
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
                || AccountClient.isGoogle(account.server(PimDomain.MAIL))) {
            return stepMail(account, session, only, false);
        }
        progress.account(account.email);
        try {
            progress.step(PimDomain.MAIL, PimdirEngine.Progress.STAGE_SERVER, 0);
            progress.collections(PimDomain.MAIL, 0, 1);
            new MailEngine(pimdir, clients, session, accountIdOf(account.email))
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
            MailEngine engine = new MailEngine(pimdir, clients, session, accountId);
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
                String collection = mail.collectionOf(account.email, mailbox.name());
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
            } else if (AccountClient.isGoogle(account.server(PimDomain.MAIL))) {
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
            Log.w("pimalaya", "mail step failed: " + AccountEntry.redacted(account.email), error);
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
        Session session = session(account, PimDomain.CALENDAR);
        try (Transport transport = new Transport()) {
            try {
                // NOTE: as the mail pass does, and for the same reason: the
                // calendar listing is a discovery walk, and it is the
                // slowest round trip of the pass.
                progress.step(PimDomain.CALENDAR, PimdirEngine.Progress.STAGE_SERVER, 0);
                events.replaceCalendars(
                        account.email,
                        session.call(server -> clients.calendar().listCalendars(transport, server)));
            } catch (Exception error) {
                Log.w("pimalaya", "calendar list failed: " + AccountEntry.redacted(account.email), error);
                return error;
            }
            // NOTE: the roster the phone's calendars hang off, its first
            // listing included: a setup chooses before its calendars are known.
            try {
                CalendarRows.reconcile(context, pimdir);
            } catch (Exception error) {
                Log.w("pimalaya", "phone calendars failed: " + AccountEntry.redacted(account.email), error);
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
            try (WorkerPool<Transport> pool =
                    new WorkerPool<>(CalendarEngine.WORKERS, transport, Transport::new, null)) {
                WorkerPool.Outcome outcome =
                        pool.run(
                                "calendar pass " + account.email,
                                calendars,
                                (worker, collection, remote) -> {
                                    running.incrementAndGet();
                                    try {
                                        session.call(
                                                server -> {
                                                    CalendarEngine engine =
                                                            new CalendarEngine(
                                                                    pimdir,
                                                                    clients,
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
                for (WorkerPool.Failure failed : outcome.failures) {
                    Log.w("pimalaya", "calendar sync failed: " + failed.key, failed.error);
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

    /**
     * One domain's collections as the store holds them, every account's:
     * the mailboxes, the calendars, or the subscribed address books (the
     * on-device one among them).
     */
    public List<PimdirCollections.Stored> collectionsOf(PimDomain domain) {
        PimdirCollections collections = new PimdirCollections(pimdir, context);
        switch (domain) {
            case MAIL:
                return collections.list(PimdirSummary.MAIL);
            case CALENDAR:
                return collections.list(PimdirSummary.CALENDAR);
            default:
                return base.subscribedBooks(collections);
        }
    }
}
