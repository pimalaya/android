package org.pimalaya;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pimalaya.client.Account;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.client.OauthTokens;

/**
 * The one sync path behind every entry point: the in-app sync and the
 * background worker both run their passes here, so the account lookup,
 * the addressbook self-heal and the refresh-once-and-retry dance exist
 * exactly once. The callers keep what is theirs alone: MainActivity the
 * loader dialog, the toasts and its in-memory account cache (fed by the
 * observer), SyncWorker the scheduling and the notification. Everything
 * blocks; callers run it off the main thread.
 */
final class SyncRunner {
    /**
     * Observes a run for the foreground loader and the account cache;
     * every hook fires on the sync thread.
     */
    interface Observer {
        /** A book's pass is starting (the loader titles itself). */
        void bookStarted(BookEntry book);

        /** An engine stage stepped (the loader's detail line). */
        void step(int stage, int count);

        /** A token refresh re-persisted the account's credentials. */
        void accountRefreshed(AccountEntry updated);
    }

    /**
     * Outcome of a remote run: what was pulled from the server, pushed
     * to it, merged on the phone axis, and left conflicted awaiting the
     * user's manual resolution, plus the first failure.
     */
    static final class Outcome {
        final Set<String> localIn = new HashSet<>();
        final Set<String> localOut = new HashSet<>();
        final Set<String> localChanged = new HashSet<>();
        final Set<String> remoteIn = new HashSet<>();
        final Set<String> remoteOut = new HashSet<>();
        final Set<String> remoteChanged = new HashSet<>();
        int conflicts;

        /** Whether any synced book mirrors into the phone's Contacts
         *  app, which is what earns the report its Local line. */
        boolean local;

        Exception failure;

        /** Folds one book's report in; the sets dedupe across passes. */
        void absorb(OfflineEngine.Report report) {
            localIn.addAll(report.localIn);
            localOut.addAll(report.localOut);
            localChanged.addAll(report.localChanged);
            remoteIn.addAll(report.remoteIn);
            remoteOut.addAll(report.remoteOut);
            remoteChanged.addAll(report.remoteChanged);
            conflicts += report.conflicts;
        }
    }

    private final Context context;
    private final CardStore base;
    private final PimdirDb pimdir;
    private final SecureStore store;
    private final PimalayaClient client;

    /** Null when the run is headless (the background worker). */
    private final Observer observer;

    SyncRunner(
            Context context,
            CardStore base,
            PimdirDb pimdir,
            SecureStore store,
            PimalayaClient client,
            Observer observer) {
        this.context = context;
        this.base = base;
        this.pimdir = pimdir;
        this.store = store;
        this.client = client;
        this.observer = observer;
    }

    /**
     * One book's full pass, the background worker's unit. The local
     * book has no server, so its pass is the phone spoke alone; a
     * server book runs the full three-spoke pass with its account's
     * stored credentials, an expired OAuth access token refreshed and
     * the book retried once.
     */
    OfflineEngine.Report syncBook(BookEntry book) throws Exception {
        String url = book.book.url;
        bookStarted(book);

        if (LocalBook.is(book.accountEmail)) {
            OfflineEngine.Report report = new OfflineEngine.Report();
            engine(null).syncPhone(url, report);
            return report;
        }

        AccountEntry entry = entryFor(book.accountEmail);
        if (entry == null) {
            Log.w("pimalaya", "no stored account for " + book.accountEmail + ", skipping");
            return new OfflineEngine.Report();
        }

        AccountConnection contacts = entry.connection(PimDomain.CONTACTS);
        try {
            return engine(contacts.account).syncBook(url, book.remoteSynced);
        } catch (Exception error) {
            if (!expiredToken(error) || contacts.refreshToken == null) {
                throw error;
            }
            return engine(refresh(entry)).syncBook(url, book.remoteSynced);
        }
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
    Outcome syncRemote() {
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
            if (!account.covers(PimDomain.CONTACTS) || known.contains(account.email)) {
                continue;
            }
            try {
                List<Addressbook> books =
                        client.listAddressbooks(
                                account.connection(PimDomain.CONTACTS).account);
                base.replaceAddressbooks(account.email, books);
                new PimdirCollections(pimdir, context)
                        .replace(
                                account.email,
                                PimdirMeta.CONTACT,
                                PimdirCollections.of(account.email, books));
            } catch (Exception error) {
                Log.w("pimalaya", "addressbook recovery failed", error);
            }
        }

        Map<String, List<BookEntry>> byAccount = new LinkedHashMap<>();
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
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

            AccountConnection contacts = entry.connection(PimDomain.CONTACTS);
            try {
                syncAccount(contacts.account, group.getValue(), outcome);
            } catch (Exception error) {
                if (expiredToken(error) && contacts.refreshToken != null) {
                    try {
                        syncAccount(refresh(entry), group.getValue(), outcome);
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
     * The phone spoke alone: reconciles the per-addressbook Android
     * accounts (which also purges a book just switched off), then runs
     * the two-way phone engine pass per phone-synced book, tallying
     * into the report. Returns a failure, or null. Needs the contacts
     * permission; the caller gates on it.
     */
    Exception syncLocal(OfflineEngine.Report report) {
        List<BookEntry> phoneBooks = phoneSyncedBooks();

        try {
            // NOTE: pass the full phone-synced set at once; reconcile
            // purges the Android accounts of books no longer mirrored.
            Accounts.reconcile(context, phoneBooks);

            OfflineEngine engine = engine(null);
            for (BookEntry entry : phoneBooks) {
                bookStarted(entry);
                engine.syncPhone(entry.book.url, report);
            }
        } catch (Exception error) {
            return error;
        }

        return null;
    }

    /** The subscribed books set to mirror into the phone's contacts. */
    List<BookEntry> phoneSyncedBooks() {
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
     */
    private void syncAccount(Account account, List<BookEntry> books, Outcome outcome)
            throws Exception {
        OfflineEngine engine = engine(account);

        for (BookEntry entry : books) {
            bookStarted(entry);
            outcome.absorb(engine.syncBook(entry.book.url, entry.remoteSynced));
            outcome.local |= entry.phoneSynced;
        }
    }

    /** An engine wired to the observer's progress display. */
    private OfflineEngine engine(Account account) {
        OfflineEngine engine = new OfflineEngine(base, pimdir, client, account, context);
        if (observer != null) {
            engine.progress = observer::step;
        }
        return engine;
    }

    /** Refreshes the contacts token and returns the fresh credentials. */
    private Account refresh(AccountEntry entry) {
        return refreshed(entry, PimDomain.CONTACTS).connection(PimDomain.CONTACTS).account;
    }

    /**
     * Refreshes one domain's OAuth access token and re-persists the
     * account, returning the updated entry. Providers may rotate the
     * refresh token, so a reissued one replaces the stored one.
     */
    AccountEntry refreshed(AccountEntry entry, PimDomain domain) {
        AccountConnection connection = entry.connection(domain);
        OauthTokens tokens =
                client.oauthRefresh(
                        connection.tokenEndpoint,
                        connection.clientId,
                        connection.clientSecret,
                        connection.refreshToken,
                        null);

        // NOTE: the refreshed token replaces this domain's connection alone.
        // Another domain of the same account may well hold a different token,
        // from a different consent, and refreshing one must not overwrite it.
        String refreshToken =
                tokens.refreshToken != null ? tokens.refreshToken : connection.refreshToken;
        AccountEntry updated =
                entry.with(domain, connection.withAccessToken(tokens.accessToken, refreshToken));

        store.add(updated);
        if (observer != null) {
            observer.accountRefreshed(updated);
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

        <T> T call(Call<T> call) throws Exception {
            AccountConnection connection = account.connection(domain);
            try {
                return call.on(connection.account);
            } catch (Exception error) {
                if (!expiredToken(error) || connection.refreshToken == null) {
                    throw error;
                }
                account = refreshed(account, domain);
                return call.on(account.connection(domain).account);
            }
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

    private void bookStarted(BookEntry book) {
        if (observer != null) {
            observer.bookStarted(book);
        }
    }

    /** True for an HTTP 401 from any backend (expired or revoked token). */
    static boolean expiredToken(Exception error) {
        return error instanceof PimalayaException
                && Integer.valueOf(401).equals(((PimalayaException) error).status);
    }
}
