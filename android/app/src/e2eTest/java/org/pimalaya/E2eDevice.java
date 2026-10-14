package org.pimalaya;

import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.pimalaya.account.SyncScope;
import org.pimalaya.calendar.CalendarStager;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.Clients;
import org.pimalaya.client.MailSession;
import org.pimalaya.contact.BookEntry;
import org.pimalaya.contact.CardStore;
import org.pimalaya.contact.PimdirContacts;
import org.pimalaya.file.FileStore;
import org.pimalaya.mail.MailReader;
import org.pimalaya.mail.MailStager;
import org.pimalaya.mail.MailState;
import org.pimalaya.mail.MailStore;
import org.pimalaya.mail.MailSubmit;
import org.pimalaya.phone.Accounts;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.sync.SyncCoordinator;
import org.pimalaya.sync.SyncPasses;
import org.robolectric.shadows.ShadowNetworkInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One simulated phone: a store of its own (an isolated files directory, so its
 * own `pimdir.db` and blobs, and its own preferences), an in-memory account
 * roster, and a headless {@link SyncPasses} wired as {@link BackgroundJob}
 * wires one. Two devices in one test share nothing but the server and the
 * network the host JVM sees ({@link #network}).
 *
 * <p>It drives the remote pass as the open app does: the first sync is one
 * step ({@link MailStep}), a sync is a pass listing only on Wi-Fi, Load more
 * is a step, every one gated on {@link SyncCoordinator#wifi(Context)}, never
 * bypassed: a test puts the device on Wi-Fi rather than skipping the gate.
 */
final class E2eDevice {
    /** The network a device sees, injected into Robolectric's connectivity. */
    enum Network {
        WIFI,
        MOBILE,
        NONE
    }

    final Context context;
    final PimdirDb pimdir;
    final MailStore mail;
    final FileStore files;
    final EventStore events;
    final CardStore cards;
    final Clients clients = Clients.real();
    final Accounts accounts;
    final SyncPasses passes;

    /** The test's tag, whose scratch collections alone a pass takes among the suite's. */
    String tag;

    E2eDevice(Context app, String name) {
        context = new Isolated(app, name);
        pimdir = new PimdirDb(context);
        mail = new MailStore(context, pimdir);
        files = new FileStore(context, pimdir);
        events = new EventStore(context, pimdir);
        cards = new CardStore(context, pimdir);
        accounts = new Accounts(context);
        passes =
                new SyncPasses(
                        context,
                        new SyncPasses.Parts(pimdir, cards, accounts, mail, events, clients),
                        SyncPasses.HEADLESS);
    }

    /**
     * Puts every device of the run on a network: Robolectric's connectivity
     * is the application's, which the devices' contexts share.
     */
    static void network(Context app, Network network) {
        ConnectivityManager connectivity = app.getSystemService(ConnectivityManager.class);
        if (network == Network.NONE) {
            shadowOf(connectivity).setActiveNetworkInfo(null);
            shadowOf(connectivity).setDefaultNetworkActive(false);
            return;
        }
        int type =
                network == Network.WIFI
                        ? ConnectivityManager.TYPE_WIFI
                        : ConnectivityManager.TYPE_MOBILE;
        shadowOf(connectivity).setDefaultNetworkActive(true);
        shadowOf(connectivity)
                .setActiveNetworkInfo(
                        ShadowNetworkInfo.newInstance(
                                NetworkInfo.DetailedState.CONNECTED,
                                type,
                                0,
                                true,
                                NetworkInfo.State.CONNECTED));
    }

    /** Whether the app's Wi-Fi gate lets mail list on this device. */
    boolean wifi() {
        return SyncCoordinator.wifi(context);
    }

    /**
     * Connects a mail account as the connection flow hands it over, then runs
     * its first sync as the mail tab does (`SyncCoordinator.firstMail`): one
     * step on the account's session, on Wi-Fi only.
     */
    Exception connect(AccountEntry account) {
        accounts.add(account);
        if (account.covers(PimDomain.CONTACTS)) {
            return syncContacts(account.email);
        }
        if (account.covers(PimDomain.CALENDAR)) {
            return syncCalendars(account.email);
        }
        if (!wifi()) {
            return new IllegalStateException("the first sync waits for Wi-Fi");
        }
        try (MailSession session = passes.openMail(account)) {
            return passes.stepMail(account, session, null, true);
        } catch (Exception opened) {
            return opened;
        }
    }

    /**
     * One Load more over an account (`SyncCoordinator.loadMore`): the next step
     * of the one mailbox {@code only} names, or of the account for null.
     */
    Exception loadMore(String email, String only) {
        if (!wifi()) {
            return new IllegalStateException("Load more waits for Wi-Fi");
        }
        AccountEntry account = account(email);
        try (MailSession session = passes.openMail(account)) {
            return passes.stepMail(account, session, only, false);
        } catch (Exception opened) {
            return opened;
        }
    }

    /**
     * One mail pass over the account as a sync asks for it (`syncMail`),
     * scoped to some of its collections as the filter scopes one: the roster
     * is listed whole, and only these are reconciled, on Wi-Fi; on a metered
     * network the outbox among them is drained and nothing listed.
     */
    SyncPasses.MailPass sync(String email, Collection<String> collections) {
        Set<String> scoped = new HashSet<>(collections);
        return passes.mailPass(
                new SyncScope() {
                    @Override
                    public boolean account(String candidate) {
                        return candidate.equals(email);
                    }

                    @Override
                    public boolean collection(String candidate, String collection) {
                        return candidate.equals(email) && scoped.contains(collection);
                    }
                },
                wifi());
    }

    /**
     * One contacts pass over the account, as the contacts tab's pull runs
     * it: its books listed on first sight, subscribed ones reconciled.
     */
    Exception syncContacts(String email) {
        return passes.syncRemote(only(email)).failure;
    }

    /**
     * One calendar pass over the account, as the agenda's pull runs it,
     * hiding the scratch calendars of other tests as the filter would hide a
     * calendar: Google deletes a calendar minutes after it says so, and one
     * still listed meanwhile answers 404.
     */
    Exception syncCalendars(String email) {
        Map<String, String> names = new java.util.HashMap<>();
        return passes.calendarPass(
                new SyncScope() {
                    @Override
                    public boolean account(String candidate) {
                        return candidate.equals(email);
                    }

                    @Override
                    public boolean collection(String candidate, String collection) {
                        if (names.isEmpty()) {
                            for (EventStore.StoredCalendar calendar : events.loadCalendars()) {
                                names.put(calendar.id, calendar.name);
                            }
                        }
                        String name = names.getOrDefault(collection, "");
                        return candidate.equals(email)
                                && (tag == null
                                        || !name.startsWith(E2eFixture.PREFIX)
                                        || name.contains(tag));
                    }
                });
    }

    /** The account's address book named so, null when it lists none. */
    BookEntry book(String email, String name) {
        for (BookEntry entry : cards.loadSubscribedAddressbooks()) {
            if (entry.accountEmail.equals(email)
                    && (name == null || name.equals(entry.book.name()))) {
                return entry;
            }
        }
        return null;
    }

    /** The cards of one book whose name carries the text. */
    List<PimdirContacts.Indexed> cards(String book, String text) {
        List<PimdirContacts.Indexed> found = new ArrayList<>();
        for (PimdirContacts.Indexed indexed : contacts().list(book)) {
            if (indexed.name.contains(text)) {
                found.add(indexed);
            }
        }
        return found;
    }

    /** The store's contacts, staging as the contact form does. */
    PimdirContacts contacts() {
        return new PimdirContacts(pimdir);
    }

    /** The account's calendars, as listed. */
    List<EventStore.StoredCalendar> calendars(String email) {
        List<EventStore.StoredCalendar> found = new ArrayList<>();
        for (EventStore.StoredCalendar calendar : events.loadCalendars()) {
            if (calendar.accountEmail.equals(email)) {
                found.add(calendar);
            }
        }
        return found;
    }

    /** The account's calendar named so, null when it lists none. */
    EventStore.StoredCalendar calendar(String email, String name) {
        for (EventStore.StoredCalendar calendar : events.loadCalendars()) {
            if (calendar.accountEmail.equals(email) && name.equals(calendar.name)) {
                return calendar;
            }
        }
        return null;
    }

    /** The events of one calendar whose source carries the text. */
    List<EventStore.StoredEvent> events(String collection, String text) {
        List<EventStore.StoredEvent> found = new ArrayList<>();
        for (EventStore.StoredEvent event : events.loadEvents()) {
            if (event.collectionId.equals(collection) && event.ical.contains(text)) {
                found.add(event);
            }
        }
        return found;
    }

    /** A calendar driver that only stages, as the event editor's is. */
    CalendarStager calendarStager(String email) {
        return new CalendarStager(pimdir, clients);
    }

    /** A scope taking one account whole. */
    private static SyncScope only(String email) {
        return new SyncScope() {
            @Override
            public boolean account(String candidate) {
                return candidate.equals(email);
            }

            @Override
            public boolean collection(String candidate, String collection) {
                return candidate.equals(email);
            }
        };
    }

    /** The collection of one mailbox, by its name on the server. */
    String collection(String email, String mailbox) {
        return mail.collectionOf(email, mailbox);
    }

    /** The collection the source gives a role to, null when none does. */
    String role(String email, String role) {
        String prefix = PimdirAccount.collectionId(mail.accountIdOf(email), "");
        for (Map.Entry<String, String> entry : mail.roles().entrySet()) {
            if (entry.getKey().startsWith(prefix) && role.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The account's outbox, which a pass drains when its scope takes it. */
    String outbox(String email) {
        return mail.outbox().outboxOf(email);
    }

    /** What the store lists in one collection whose subject carries the text. */
    List<MailStore.StoredMessage> messages(String collection, String subject) {
        MailStore.Query query =
                mail.query((email, candidate) -> candidate.equals(collection), false, false, subject);
        List<MailStore.StoredMessage> found = new ArrayList<>();
        for (MailStore.StoredMessage message : mail.all(query)) {
            if (message.collection.equals(collection)) {
                found.add(message);
            }
        }
        return found;
    }

    /** The one message of a collection with this subject, null when there is none. */
    MailStore.StoredMessage message(String collection, String subject) {
        List<MailStore.StoredMessage> found = messages(collection, subject);
        if (found.size() > 1) {
            throw new AssertionError(found.size() + " messages named " + subject + " in " + collection);
        }
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Whether an item owes its server a push, read off the store: no base
     * agreed yet (a create, a move's target), markers past the agreed ones,
     * or, outside mail, a body past the agreed one.
     */
    boolean owes(String collection, String linkId) {
        try (Cursor cursor =
                pimdir.getReadableDatabase()
                        .rawQuery(
                                "SELECT i.flags, i.object_hash, b.link_id, b.base_present,"
                                        + " b.base_flags, b.base_object, c.kind"
                                        + " FROM items i"
                                        + " JOIN collections c ON c.id = i.collection"
                                        + " LEFT JOIN bindings b ON b.collection = i.collection"
                                        + " AND b.link_id = i.link_id AND b.source = ?"
                                        + " WHERE i.collection = ? AND i.link_id = ?",
                                new String[] {
                                    PimdirStorage.sourceOf(collection),
                                    PimdirStorage.collectionOf(collection),
                                    linkId
                                })) {
            if (!cursor.moveToFirst()) {
                return false;
            }
            if (cursor.isNull(2) || cursor.getInt(3) == 0) {
                return true;
            }
            boolean flagsMoved =
                    !cursor.isNull(0)
                            && !cursor.isNull(4)
                            && !flagSet(cursor.getString(0)).equals(flagSet(cursor.getString(4)));
            boolean bodyMoved =
                    !PimdirSummary.MAIL.equals(cursor.getString(6))
                            && !cursor.isNull(1)
                            && !cursor.getString(1).equals(cursor.getString(5));
            return flagsMoved || bodyMoved;
        }
    }

    private static Set<String> flagSet(String json) {
        try {
            JSONArray array = new JSONArray(json);
            Set<String> flags = new HashSet<>();
            for (int index = 0; index < array.length(); index++) {
                flags.add(array.getString(index));
            }
            return flags;
        } catch (JSONException error) {
            throw new AssertionError(error);
        }
    }

    /** A driver that only stages, as the reader's and the composer's are. */
    MailStager stager(String email) {
        return new MailStager(pimdir, clients);
    }

    MailReader reader(String email) {
        return new MailReader(mail, files, clients.mail(), stager(email));
    }

    MailSubmit submitter(String email) {
        return new MailSubmit(clients.mail(), mail, stager(email));
    }

    /** The account as the roster holds it now. */
    AccountEntry account(String email) {
        for (AccountEntry account : accounts.loadAll()) {
            if (account.email.equals(email)) {
                return account;
            }
        }
        throw new AssertionError("no account " + email);
    }

    /**
     * A context whose files, preferences and list filters are this device's
     * alone; the mail state is the process's, which the devices share.
     */
    private static final class Isolated extends ContextWrapper
            implements MergedFilter.Owner, MailState.Owner {
        private final String name;

        private final Map<String, MergedFilter> filters = new HashMap<>();

        Isolated(Context base, String name) {
            super(base);
            this.name = name;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public Map<String, MergedFilter> filters() {
            return filters;
        }

        @Override
        public MailState mailState() {
            return MailState.of(getBaseContext());
        }

        @Override
        public File getFilesDir() {
            File dir = new File(super.getFilesDir(), "e2e-" + name);
            dir.mkdirs();
            return dir;
        }

        @Override
        public File getCacheDir() {
            File dir = new File(super.getCacheDir(), "e2e-" + name);
            dir.mkdirs();
            return dir;
        }

        @Override
        public File getDatabasePath(String database) {
            return super.getDatabasePath(isolated(database));
        }

        @Override
        public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(
                String database,
                int mode,
                android.database.sqlite.SQLiteDatabase.CursorFactory factory,
                android.database.DatabaseErrorHandler errors) {
            return super.openOrCreateDatabase(isolated(database), mode, factory, errors);
        }

        @Override
        public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(
                String database,
                int mode,
                android.database.sqlite.SQLiteDatabase.CursorFactory factory) {
            return super.openOrCreateDatabase(isolated(database), mode, factory);
        }

        @Override
        public boolean deleteDatabase(String database) {
            return super.deleteDatabase(isolated(database));
        }

        /** A database name made this device's; a path is one already. */
        private String isolated(String database) {
            return database.contains(File.separator) ? database : "e2e-" + name + "-" + database;
        }

        @Override
        public SharedPreferences getSharedPreferences(String file, int mode) {
            return super.getSharedPreferences("e2e-" + name + "-" + file, mode);
        }
    }

    /**
     * The roster in memory: the Android keystore that encrypts the real one
     * is a device's, and a test's accounts live as long as the test.
     */
    static final class Accounts extends SecureStore {
        private final List<AccountEntry> entries = new ArrayList<>();

        Accounts(Context context) {
            super(context);
        }

        @Override
        public synchronized List<AccountEntry> loadAll() {
            return new ArrayList<>(entries);
        }

        @Override
        public synchronized void add(AccountEntry entry) {
            entries.removeIf(candidate -> candidate.email.equals(entry.email));
            entries.add(entry);
        }

        @Override
        public synchronized void remove(String email) {
            entries.removeIf(candidate -> candidate.email.equals(email));
        }
    }
}
