package org.pimalaya.mail;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.Flags;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.MessageBody;
import org.pimalaya.file.FileStore;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirBlobs;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirItems;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirEngine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The mail half of the engine: one driver per session, servicing the
 * remote yields of the mailboxes run on it.
 *
 * <p>What shapes it is that mail authenticates once per session. IMAP is a
 * session and JMAP is one session resource, so both backends list many
 * mailboxes inside one login, where a WebDAV collection is one request
 * each. The engine, on the other hand, reconciles one collection at a time.
 * The two meet in the session: a pass runs an account's mailboxes on a few
 * sessions side by side ({@code WorkerPool}), each answering the listings of
 * the mailboxes its worker takes in turn, so a sync costs a handful of
 * connections per account rather than one per mailbox, and waits on the
 * network for a few mailboxes at once rather than one after another.
 *
 * <p>The session it walks and writes on is the caller's, opened for the
 * pass and closed with it, so the three markers a reader moved go out on
 * the connection the walk already had rather than on three of their own.
 *
 * <p>A listing names every message it carries (pimdir SYNC section 4): a
 * message's handle <em>is</em> its link id, and its summary comes off the
 * header fields the listing read, so a new message lands listed in the page
 * that found it, with no probe and no upgrade after it. Only the body needs
 * the network, which is why opening a message is the one read that still
 * reaches for one, beside the body step an account's window runs
 * ({@link MailBodies}).
 *
 * <p>A mailbox is listed whole, within the account's bound ({@link
 * MailScope}), newest first and a page at a time: each page lands in its own
 * write, so the top of the list shows while the rest fills in, and a pass
 * cut off resumes where its last page landed.
 */
public class MailEngine extends PimdirEngine {
    /**
     * The markers this app writes, and the only ones a push ever touches.
     *
     * <p>The set is closed on purpose. A server marks messages with
     * keywords nobody here models ({@code $junk}, a label, a rule's own
     * tag), and a push that reasoned about anything outside this list
     * would have to decide what to do with them; diffing inside it means
     * it never sees them.
     */
    private static final Flags.Marker[] WRITABLE = {
        Flags.Marker.SEEN, Flags.Marker.ANSWERED, Flags.Marker.FLAGGED, Flags.Marker.DELETED
    };

    /**
     * Messages a mailbox's first pass lists, the chunk an account's window
     * starts from: a number of messages, never a span of time.
     */
    static final int FIRST_CHUNK = 50;

    /** Messages one step of the background fill widens a mailbox by. */
    public static final int FILL_CHUNK = 500;

    /** Graph sessions an account runs at once: HTTP requests, nothing held. */
    static final int GRAPH = 4;

    /** JMAP sessions an account runs at once: HTTP requests, nothing held. */
    static final int JMAP = 4;

    /** IMAP sessions an account runs at once: a connection and a login each. */
    static final int IMAP = 3;

    /**
     * Gmail sessions an account runs at once: the account is one listing,
     * read once per run, and its metadata batches spread over the sessions
     * under one pacing bucket.
     */
    static final int GMAIL = 2;

    /**
     * How many sessions one account of this backend runs at once.
     *
     * <p>The size is the backend's: four for Graph and JMAP, which are HTTP
     * requests and nothing else; three for IMAP, each worker its own connection
     * and login; two for Gmail, whose reads are paced across the whole process
     * anyway, so more workers would only queue on the same bucket.
     */
    public static int sessionsOf(Account account) {
        if (AccountClient.isGraph(account)) {
            return GRAPH;
        }
        if (AccountClient.isJmap(account)) {
            return JMAP;
        }
        if (AccountClient.isGoogle(account)) {
            return GMAIL;
        }
        return IMAP;
    }

    /** The account's live connection; null on a driver that only stages,
     *  whose mutations reach no server. */
    private final MailSession session;

    /** What the account's collection ids are namespaced under. */
    private final String accountId;

    /** The store, for the account's bound kept beside it. */
    private final PimdirDb pimdir;

    /**
     * The members the last listed page named, by handle: what a meta fetch
     * reads back, since a listing already read everything one would.
     */
    private final Map<String, JSONObject> listed = new HashMap<>();

    /**
     * Counts the bodies this driver's bridge calls write, for the dock's
     * bar ({@link MailTally}); null when nothing is counting.
     */
    public MailTally tally;

    public MailEngine(PimdirDb pimdir, Clients clients, MailSession session, String accountId) {
        super(pimdir, clients);
        this.pimdir = pimdir;
        this.session = session;
        this.accountId = accountId;
    }

    /**
     * The account's mailboxes and the role each carries, in one round; a
     * Gmail or JMAP account's are remembered as listed account-wide, a
     * Gmail one's as keeping an All Mail ({@link PimdirCollections#ALL_MAIL}), and
     * whether the server erases one message alone. Any other account's
     * bodies once linked across its mailboxes are released, once
     * ({@link MailStore#repairLinks}).
     */
    public List<Mailbox> mailboxes() {
        List<Mailbox> mailboxes = clients.mail().listMailboxes(session);
        Account server = session.account();
        boolean accountWide = AccountClient.isGoogle(server) || AccountClient.isJmap(server);
        MailStore.markAccountWide(pimdir.context(), accountId, accountWide);
        MailStore.markAllMail(pimdir.context(), accountId, AccountClient.isGoogle(server));
        MailStore.markExpungesOne(pimdir.context(), accountId, session.expungesOne());
        if (!accountWide) {
            int released;
            synchronized (locks.store) {
                released = mail().repairLinks(accountId);
            }
            if (released > 0) {
                Log.i("pimalaya", "mail bodies linked across mailboxes released: " + released);
            }
        }
        return mailboxes;
    }

    /**
     * The bodies the store holds under some link ids, within this account:
     * a link id names one message across its mailboxes only where they are
     * listed account-wide (Gmail, JMAP), each a projection of one listing.
     * Elsewhere it is a handle of one mailbox (an IMAP UID names another
     * message in the next mailbox, a Graph id is its folder's copy alone),
     * so nothing is linked and every body is fetched.
     */
    @Override
    protected JSONObject lookup(JSONArray links) throws JSONException {
        return accountWide()
                ? offline.lookupObjects(links, accountId)
                : new JSONObject().put("objects", new JSONObject());
    }

    /**
     * Reconciles one mailbox with its server: list, then push what is
     * staged.
     *
     * <p>Within the account's bound: the scope's floor on the {@code Date}
     * header, or none. No backend's checkpoint is bound to a scope (an IMAP
     * modseq, a Gmail history id, a JMAP state, a Graph delta link made with
     * no filter), so a widened bound lists only the band it lacks.
     *
     * <p>No hydrate after it, unlike a calendar: a mailbox is a list of
     * summaries and a message rises off it by being opened, so a placement
     * below full is the ordinary state of one rather than something to
     * repair. And no upgrade either: every message a listing carries arrives
     * named.
     */
    public void sync(String collection) {
        step(Progress.STAGE_SERVER, 0);
        list(collection, scopeOf(collection));
    }

    /**
     * Widens one mailbox by its next chunk: the {@code count} newest messages
     * dated below its floor, the oldest {@code Date} among them its new
     * floor, within the account's bound. A chunk is a number of messages,
     * never a span of time. Answers whether there was anything to widen: a
     * mailbox listed whole within the bound has nothing.
     *
     * <p>A mailbox never listed lists its first chunk instead, and a round
     * under way resumes rather than opening another.
     *
     * <p>The wider scope lists only the band below the old floor, on every
     * backend: no checkpoint is bound to a scope, a Graph delta link being
     * made with no filter, so it reports what changes in the band too.
     */
    public boolean widen(String collection, int count) {
        String bound = bound(collection);
        MailStore.Coverage coverage = mail().coverage(collection);
        if (coverage.filling || coverage.at == null) {
            sync(collection);
            return true;
        }
        if (!MailScope.limits(coverage.since, bound)) {
            return false;
        }
        step(Progress.STAGE_SERVER, 0);
        long started = System.nanoTime();
        String floor = timedFloor(collection, coverage.since, count);
        Log.d(
                "pimalaya",
                "mail widen " + collection + " by " + count + " below " + coverage.since
                        + ": floor " + floor + " in " + (System.nanoTime() - started) / 1_000_000
                        + " ms");
        list(collection, MailScope.clamp(floor, bound));
        return true;
    }

    /**
     * Lists one mailbox from {@code since} (null for all of it), within the
     * account's bound: the round an IMAP first step opens each mailbox with
     * ({@link ImapStep}). A mailbox already listed, or under way, syncs as
     * {@link #sync} does.
     */
    void syncSince(String collection, String since) {
        MailStore.Coverage coverage = mail().coverage(collection);
        if (coverage.filling || coverage.at != null) {
            sync(collection);
            return;
        }
        step(Progress.STAGE_SERVER, 0);
        list(collection, MailScope.clamp(since, bound(collection)));
    }

    /**
     * Lists a mailbox down to {@code since} (null for all mail), within the
     * account's bound, unless what it covers reaches there already: the
     * band a step's frontier lacks, or the whole mailbox for one never
     * listed. Answers whether it listed.
     */
    boolean reach(String collection, String since) {
        String floor = MailScope.clamp(since, bound(collection));
        MailStore.Coverage coverage = mail().coverage(collection);
        if (coverage.at != null && !coverage.filling && !MailScope.limits(coverage.since, floor)) {
            return false;
        }
        step(Progress.STAGE_SERVER, 0);
        list(collection, floor);
        return true;
    }

    /**
     * The floor one pass lists a mailbox from, within the account's bound:
     * where its round under way lists from, else what its last closed round
     * covered, so a pass after the first is a delta; and for a mailbox never
     * listed, its first chunk, the {@link #FIRST_CHUNK} newest messages, or
     * on an account stepped through account-wide (Gmail) its bound, whose
     * round names what the store holds ({@link #nameUnnamed}).
     */
    private String scopeOf(String collection) {
        String bound = bound(collection);
        MailStore.Coverage coverage = mail().coverage(collection);
        if (coverage.filling) {
            return MailScope.clamp(coverage.roundSince, bound);
        }
        if (coverage.at != null) {
            return MailScope.clamp(coverage.since, bound);
        }
        if (accountWide()) {
            return bound;
        }
        String floor = timedFloor(collection, null, FIRST_CHUNK);
        return MailScope.clamp(floor, bound);
    }

    /**
     * The floor of a mailbox's next chunk: the oldest {@code Date} among its
     * {@code count} newest messages dated before {@code before} (null for no
     * ceiling), null when fewer lie below it and the mailbox is whole there.
     */
    String floor(String collection, String before, int count) {
        return clients.mail().mailFloor(session, mailboxOf(collection), before, count);
    }

    /**
     * The {@code Date}s of a mailbox's {@code count} newest messages, newest
     * first, as {@link #floor} reads them: fewer when the mailbox holds
     * fewer.
     */
    List<String> newest(String collection, int count) {
        return clients.mail().mailNewest(session, mailboxOf(collection), null, count);
    }

    /**
     * Whether a failure is the server refusing one mailbox, the connection
     * standing: a 404, which the bridge answers for an IMAP mailbox the
     * server would not open ({@code NO} to {@code EXAMINE} or
     * {@code SELECT}: deleted since the roster was read, or not yet seen by
     * a new session) as for a Graph folder or a Gmail label gone. A step and
     * a pass skip such a mailbox, the next roster dropping it if it is gone.
     * Only a mailbox's own reads end up here: a push answers the 404 of a
     * message gone itself ({@link #gone}).
     */
    public static boolean refused(Exception failure) {
        return isGone(failure);
    }

    /**
     * Skips one mailbox a step or a pass found refused ({@link #refused}),
     * logged; any other failure is thrown on.
     */
    static void skip(String collection, RuntimeException failure) {
        if (!refused(failure)) {
            throw failure;
        }
        Log.w("pimalaya", "mailbox refused, skipped: " + collection + ": " + failure.getMessage());
    }

    /** {@link #floor}, its time counted as the network's toward the first page. */
    private String timedFloor(String collection, String before, int count) {
        long started = System.nanoTime();
        try {
            return floor(collection, before, count);
        } finally {
            remote(System.nanoTime() - started);
        }
    }

    /**
     * Runs the engine's round or delta over a mailbox from {@code since}. No
     * backend's checkpoint is bound to the scope it was made under, so a
     * widening is a band round.
     */
    private void list(String collection, String since) {
        Log.d(
                "pimalaya",
                "mail sync " + collection + " since " + since + ": "
                        + clients.offline().syncImmutable(this, collection, since, false)
                        + ", the pass so far " + clock);
        MailState.of(pimdir.context()).relisted(collection);
    }

    /**
     * The floor of a mailbox's bound today, null for all of its mail: its
     * account's, or none for a mailbox kept whole.
     */
    private String bound(String collection) {
        return MailScope.sinceOf(pimdir.context(), accountId, collection);
    }

    /** The account's mail store, for the coverage a pass starts from. */
    MailStore mail() {
        if (mail == null) {
            mail = new MailStore(pimdir.context(), pimdir);
        }
        return mail;
    }

    private MailStore mail;

    /**
     * The order a pass takes an account's mailboxes in: the inbox, the sent
     * mail, the drafts, every other by name, the junk and the trash last. The
     * first dialog waits on the inbox before anything else, and every pass
     * after it lands the inbox first.
     */
    public static List<Mailbox> ordered(List<Mailbox> mailboxes) {
        List<Mailbox> ordered = new ArrayList<>(mailboxes);
        ordered.sort(
                java.util.Comparator.comparingInt((Mailbox mailbox) -> rank(mailbox.role))
                        .thenComparing(mailbox -> mailbox.name, String.CASE_INSENSITIVE_ORDER));
        return ordered;
    }

    /** Where a role sorts in a pass: lower first. */
    static int rank(String role) {
        if (role == null) {
            return 3;
        }
        switch (role) {
            case "inbox":
                return 0;
            case "sent":
                return 1;
            case "drafts":
                return 2;
            case "junk":
                return 4;
            case "trash":
                return 5;
            default:
                return 3;
        }
    }

    /** The mailbox behind a collection id, which is what IMAP names it by. */
    String mailboxOf(String collection) {
        return PimdirAccount.nameOf(accountId, collection);
    }

    /** The collection id of one of the account's mailboxes, by its name. */
    String collectionOf(String mailbox) {
        return PimdirAccount.collectionId(accountId, mailbox);
    }

    /** The account's live connection, null on a driver that only stages. */
    MailSession session() {
        return session;
    }

    /** The app's context, for what the account keeps beside the store. */
    Context context() {
        return pimdir.context();
    }

    /** The store id the account's collections are namespaced under. */
    String accountId() {
        return accountId;
    }

    /** Whether the account is stepped through account-wide (Gmail). */
    private boolean accountWide() {
        return MailStore.accountWide(pimdir.context(), accountId);
    }

    /**
     * The mailboxes the step under way holds the account's messages in
     * ({@link #stepRead}), what {@link #held} answers over; none outside
     * a step.
     */
    private List<String> holding = List.of();

    /**
     * Which of some server handles the store binds in the mailboxes the step
     * under way holds: what the bridge asks of each page a step lists
     * ({@code held}), so no step reads the account's every handle.
     */
    Set<String> held(List<String> handles) {
        synchronized (locks.store) {
            return offline.heldOf(holding, handles);
        }
    }

    /** {@link #held}, as the reply to the bridge's {@code held} yield. */
    @Override
    protected JSONObject held(JSONObject yielded) throws JSONException {
        List<String> handles = stringsOf(yielded.getJSONArray("handles"));
        return new JSONObject().put("held", new JSONArray(held(handles)));
    }

    /** The cache folder {@link #scratch} makes its folders in. */
    public static final String SCRATCH = "mail-sources";

    /**
     * A directory of its own under the app's cache, for one read of whole
     * messages into files ({@link #sources}); the caller deletes it.
     */
    File scratch() {
        File dir =
                new File(
                        new File(pimdir.context().getCacheDir(), SCRATCH),
                        UUID.randomUUID().toString());
        if (!dir.mkdirs()) {
            throw new IllegalStateException("Could not create " + dir);
        }
        return dir;
    }

    /** Deletes a directory {@link #scratch} made, with what is left in it. */
    static void discard(File dir) {
        File[] files = dir.listFiles();
        for (int index = 0; files != null && index < files.length; index++) {
            files[index].delete();
        }
        dir.delete();
    }

    /**
     * The page a step lands ({@link #land}), answered in place of a listing
     * by the next enumerate; null while none is.
     */
    private Landing landing;

    /** What a step's next enumerate answers: its members of one mailbox. */
    private static final class Landing {
        final JSONArray items;
        final String checkpoint;
        final boolean empty;

        Landing(JSONArray items, String checkpoint, boolean empty) {
            this.items = items;
            this.checkpoint = checkpoint;
            this.empty = empty;
        }
    }

    /**
     * Lands a step's members of one mailbox ({@link MailStep}) through the
     * engine, as it lands a listing: each item named as a listed one is.
     * Where the mailbox holds nothing yet and the engine opens a round, the
     * items are that round, whole, closed with {@code checkpoint} (the
     * account's history id taken before the step), so the next pass is a
     * delta from it; anywhere else they are a delta adding them, which
     * leaves the checkpoint and finds nothing absent.
     */
    void land(String collection, JSONArray items, String checkpoint) {
        boolean empty;
        synchronized (locks.store) {
            empty = !offline.bindsAny(collection);
        }
        landing = new Landing(items, checkpoint, empty);
        try {
            String bound = bound(collection);
            MailStore.Coverage coverage = mail().coverage(collection);
            String since = coverage.at != null ? MailScope.clamp(coverage.since, bound) : bound;
            Log.d(
                    "pimalaya",
                    "mail step " + collection + " (" + items.length() + "): "
                            + clients.offline().syncImmutable(this, collection, since, false));
            MailState.of(pimdir.context()).relisted(collection);
        } finally {
            landing = null;
        }
    }

    /**
     * One page of a mailbox's listing, straight from the backend: the
     * engine's request in, its reply out, every member named by the header
     * fields the listing read.
     */
    @Override
    protected PimDomain domain() {
        return PimDomain.MAIL;
    }

    @Override
    protected JSONObject enumerate(JSONObject yielded) throws JSONException {
        String collection = yielded.getString("collection");
        JSONObject listing = yielded.getJSONObject("listing");
        if (landing != null) {
            return landed(collection, listing);
        }
        JSONObject request = new JSONObject();
        request.put("listing", listing);
        JSONObject scope = yielded.optJSONObject("scope");
        request.put("scope", scope == null ? new JSONObject() : scope);
        request.put("covered", covered(collection, scope));
        Long reach = MailWindow.reach(pimdir.context(), accountId);
        if (reach != null) {
            request.put("received", reach);
        }

        long started = System.nanoTime();
        JSONObject page = clients.mail().enumerateMailbox(session, mailboxOf(collection), request);
        nameUnnamed(collection, listing, page);
        nameByMessageId(collection, page);
        remote(System.nanoTime() - started);
        return listed(collection, page, true);
    }

    /**
     * The page a step lands, as {@link #land} says: a round's only page
     * over a mailbox holding nothing, else a delta.
     */
    private JSONObject landed(String collection, JSONObject listing) throws JSONException {
        Landing step = landing;
        landing = null;
        JSONObject page = new JSONObject();
        page.put("items", step.items);
        page.put("vanished", new JSONArray());
        page.put("last", true);
        boolean round = "round".equals(listing.optString("kind"));
        page.put("complete", round && step.empty);
        if (round && step.empty) {
            page.put("checkpoint", step.checkpoint);
        }
        nameByMessageId(collection, page);
        return listed(collection, page, false);
    }

    /**
     * Keeps what a page named for a meta fetch to read back, counts it, and
     * with {@code arrivals} owes the bodies of a delta's members new to the
     * store, which download whatever their date ({@link MailOwed}): a
     * listing's, not a step's, whose bodies download with it.
     */
    private JSONObject listed(String collection, JSONObject page, boolean arrivals)
            throws JSONException {
        listed.clear();
        JSONArray items = page.optJSONArray("items");
        List<String> handles = new ArrayList<>();
        Map<String, String> links = new HashMap<>();
        for (int index = 0; items != null && index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            String handle = item.getString("handle");
            listed.put(handle, item);
            handles.add(handle);
            links.put(handle, item.optString("linkId", handle));
        }
        if (arrivals && !page.optBoolean("complete") && !handles.isEmpty()) {
            Map<String, String[]> bound;
            synchronized (locks.store) {
                bound = offline.bound(collection, handles);
            }
            List<String> arrived = new ArrayList<>();
            for (String handle : handles) {
                if (!bound.containsKey(handle)) {
                    arrived.add(links.get(handle));
                }
            }
            MailOwed.owe(pimdir.context(), collection, arrived);
        }
        listed(handles.size());
        step(Progress.STAGE_DOWNLOAD, handles.size());
        return page;
    }

    /**
     * Whether the store's coverage of a mailbox already holds a scope: a
     * round over it then lists what changed, where a first chunk lists its
     * band. Graph tells the two apart by it: its first chunk lands by
     * {@code /messages} without waiting on a delta link, and the round a
     * later pass opens over the covered scope makes that link.
     */
    boolean covered(String collection, JSONObject scope) {
        MailStore.Coverage coverage = mail().coverage(collection);
        if (coverage.at == null) {
            return false;
        }
        if (coverage.since == null) {
            return true;
        }
        String since = scope == null || scope.isNull("since") ? null : scope.optString("since", null);
        return since != null && since.compareTo(coverage.since) >= 0;
    }

    /**
     * Names the members a page listed by id and markers alone, so nothing
     * reaches the store unnamed (pimdir SYNC section 4): one the store binds
     * keeps the summary it holds, every other one is read with its summary
     * ({@link #read}), and one gone since it was listed is left out.
     *
     * <p>A Graph delta lists them so. So does a round over a mailbox of an
     * account stepped through account-wide (Gmail), which lists the
     * mailbox's ids alone: there, of the members the mailbox does not
     * hold, only those the account holds in another mailbox (newly filed
     * here) and those listed before the first message the account holds
     * (arrived since its history expired) are read; the rest are older mail
     * no step brought, left out, as a band round leaves all of them.
     */
    void nameUnnamed(String collection, JSONObject listing, JSONObject page)
            throws JSONException {
        JSONArray items = page.optJSONArray("items");
        List<String> unnamed = new ArrayList<>();
        for (int index = 0; items != null && index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            if (!item.has("summary")) {
                unnamed.add(item.getString("handle"));
            }
        }
        if (unnamed.isEmpty()) {
            return;
        }

        Map<String, String[]> bound;
        synchronized (locks.store) {
            bound = offline.bound(collection, unnamed);
        }
        List<String> unbound = new ArrayList<>();
        for (String handle : unnamed) {
            if (!bound.containsKey(handle)) {
                unbound.add(handle);
            }
        }
        if (page.optBoolean("complete") && accountWide()) {
            unbound = steppedIn(listing, unnamed, bound);
        }
        Map<String, JSONObject> read = new HashMap<>();
        if (!unbound.isEmpty()) {
            JSONArray named = read(collection, unbound);
            for (int index = 0; index < named.length(); index++) {
                JSONObject item = named.getJSONObject(index);
                read.put(item.getString("handle"), item);
            }
        }

        JSONArray kept = new JSONArray();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            String handle = item.getString("handle");
            if (item.has("summary")) {
                kept.put(item);
            } else if (bound.containsKey(handle)) {
                item.put("linkId", bound.get(handle)[0]);
                kept.put(item);
            } else if (read.containsKey(handle)) {
                kept.put(read.get(handle));
            }
        }
        page.put("items", kept);
    }

    /**
     * Which members of a Gmail round's page the mailbox does not hold are
     * named ({@link #nameUnnamed}): those the account holds elsewhere, and
     * those listed ahead of every message it holds, unless the round lists a
     * band. Listed newest received first, so what comes ahead of every
     * message held arrived after them.
     */
    private List<String> steppedIn(
            JSONObject listing, List<String> unnamed, Map<String, String[]> bound) {
        if (listing.isNull("cursor")) {
            sawHeld = false;
        }
        Set<String> held;
        synchronized (locks.store) {
            held = offline.heldOf(accountCollections(), unnamed);
        }
        boolean band = listing.optBoolean("band");
        List<String> named = new ArrayList<>();
        for (String handle : unnamed) {
            if (bound.containsKey(handle)) {
                sawHeld = true;
            } else if (held.contains(handle)) {
                sawHeld = true;
                named.add(handle);
            } else if (!band && !sawHeld) {
                named.add(handle);
            }
        }
        return named;
    }

    /**
     * Whether the round under way met a message the account holds: past
     * it, a member nobody holds is older mail, never an arrival.
     */
    private boolean sawHeld;

    /** The account's mailboxes as the store keeps them. */
    private List<String> accountCollections() {
        String prefix = PimdirAccount.collectionId(accountId, "");
        List<String> collections = new ArrayList<>();
        for (MailStore.Edge edge : mail().edges()) {
            if (edge.collection.startsWith(prefix)) {
                collections.add(edge.collection);
            }
        }
        // NOTE: an archived message is held in All Mail alone, which no
        // edge shows.
        if (MailStore.keepsAllMail(pimdir.context(), accountId)) {
            collections.add(MailStore.allMailOf(accountId));
        }
        return collections;
    }

    /**
     * Names an arrival by the pending create staged for it, matched on the
     * {@code Message-ID} both carry, so the engine lands the create on it
     * (pimdir SYNC §6) rather than pushing a second copy: the sent copy a
     * provider filed itself. A member this source binds already is left
     * named as it is.
     */
    void nameByMessageId(String collection, JSONObject page) throws JSONException {
        JSONArray items = page.optJSONArray("items");
        if (items == null || items.length() == 0) {
            return;
        }
        Map<String, String> creates;
        synchronized (locks.store) {
            creates = offline.pendingCreatesByMessageId(collection);
        }
        if (creates.isEmpty()) {
            return;
        }

        List<String> handles = new ArrayList<>(items.length());
        for (int index = 0; index < items.length(); index++) {
            handles.add(items.getJSONObject(index).getString("handle"));
        }
        Map<String, String[]> bound;
        synchronized (locks.store) {
            bound = offline.bound(collection, handles);
        }

        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            JSONObject summary = item.optJSONObject("summary");
            JSONObject mail = summary == null ? null : summary.optJSONObject("mail");
            String messageId = mail == null ? null : mail.optString("message_id", null);
            if (messageId == null || bound.containsKey(item.getString("handle"))) {
                continue;
            }
            String create = creates.remove(messageId);
            if (create == null) {
                // NOTE: a provider that stamped a Message-ID of its own on
                // what it sent (Gmail) files its copy under that one.
                String staged = mail().stagedSentCopy(messageId);
                create = staged == null ? null : creates.remove(staged);
                if (create != null) {
                    mail().forgetSentAlias(messageId);
                }
            }
            if (create != null) {
                item.put("linkId", create);
            }
        }
    }

    /** The messages of a mailbox read by id with their summary, as listed. */
    protected JSONArray read(String collection, List<String> handles) {
        return clients.mail().nameMessages(session, mailboxOf(collection), handles);
    }

    @Override
    protected boolean listingsNamed() {
        return true;
    }

    /**
     * The named members of the last page at the meta tier, the bodies
     * {@link #download} asked for at the full one.
     *
     * <p>A sync never asks: a listing names everything it carries. A meta
     * upgrade revisiting a claim reads back what the last page named. A
     * full one is the body step's alone ({@link MailBodies}), the reader
     * fetching what someone opens itself ({@link MailStore#saveSource}).
     */
    @Override
    protected JSONObject fetch(JSONObject yielded) throws JSONException {
        if ("full".equals(yielded.optString("tier"))) {
            return fetchBodies(yielded.getString("collection"), yielded.getJSONArray("handles"));
        }
        JSONArray items = new JSONArray();
        for (String handle : stringsOf(yielded.getJSONArray("handles"))) {
            JSONObject item = listed.get(handle);
            if (item != null) {
                items.put(item);
            }
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        return reply;
    }

    /** The rows {@link #download} asked for, by handle, while its upgrade runs. */
    private final Map<String, MailBodies.Row> downloading = new HashMap<>();

    /** The bodies {@link #stored} pinned ({@link PimdirItems#pin}), while the upgrade runs. */
    private final List<String> pinned = new ArrayList<>();

    /** The handles the upgrade asked the server for, while it runs. */
    private final Set<String> asked = new HashSet<>();

    /** The handles whose body was handed over and filed, while it runs. */
    private final Set<String> answered = new HashSet<>();

    /**
     * The files of messages already read whole, by handle, a fetch takes
     * rather than reading them again: a step's ({@link #fetched}).
     */
    private final Map<String, File> fetched = new HashMap<>();

    /** The summary each fetched body gives, by link id, restated once it lands. */
    private final Map<String, JSONObject> summaries = new HashMap<>();

    /** The attachments each fetched body carries, by link id, recorded once it lands. */
    private final Map<String, List<MessageBody.Attachment>> attachments = new HashMap<>();

    /**
     * Hands over the files of messages a step read whole, by handle, for
     * the downloads after it to store rather than read again; an empty map
     * once they are done.
     */
    void fetched(Map<String, File> files) {
        fetched.clear();
        fetched.putAll(files);
    }

    /**
     * Downloads the bodies of some messages of one mailbox through the
     * engine's upgrade to {@code Full}: a body the store holds under the
     * same link id is linked rather than fetched, every other one is
     * fetched, and each lands with its base moved to it. A message still
     * pending (a move's target, a sent copy) is left: it has no handle the
     * server knows; so is one the frontier no longer takes, read again
     * rather than trusted from the plan, unless it downloads whatever its
     * date ({@link MailBodies.Row#always}).
     *
     * <p>The sort key stays the listing's; the summary is restated from
     * the body (its attachment mark, size and invitation,
     * {@link MailStore#restateFromBody}), and the parts recorded as files
     * ({@link FileStore#recordAttachments}), as opening it does.
     *
     * <p>Answers the rows whose body did not land: asked of the server and
     * not handed over, or not stored. A landed one is owed no more
     * ({@link MailOwed}); nor is one the server answered gone, which is not
     * a failure: it is marked so ({@link MailState#gone}) and left for the
     * next listing of its mailbox to take away.
     */
    public List<MailBodies.Row> download(String collection, List<MailBodies.Row> rows) {
        downloading.clear();
        summaries.clear();
        attachments.clear();
        asked.clear();
        answered.clear();
        gone.clear();
        List<String> handles = new ArrayList<>(rows.size());
        // NOTE: read now rather than trusted from the plan: a frontier moved
        // later since frees bodies a stale plan would fetch back.
        String window = MailWindow.since(pimdir.context(), accountId, collection);
        String role = mail().roles().get(collection);
        boolean whole = MailOffline.whole(pimdir.context(), collection);
        List<String> poisoned = new ArrayList<>();
        synchronized (locks.store) {
            for (MailBodies.Row row : rows) {
                if (!(row.always || MailBodies.takes(row.sortKey, window, role, whole))
                        || offline.isPendingCreate(collection, row.linkId)) {
                    continue;
                }
                // NOTE: a body whose reading killed the app is never fetched
                // again: failed for good, owed no more.
                String key = MailOwed.key(collection, row.linkId);
                if (MailPoison.poisoned(pimdir.context(), key)) {
                    poisoned.add(key);
                    continue;
                }
                String handle = offline.handleFor(collection, row.linkId);
                if (handle != null) {
                    handles.add(handle);
                    downloading.put(handle, row);
                }
            }
        }
        MailOwed.settle(pimdir.context(), poisoned);
        if (handles.isEmpty()) {
            return List.of();
        }
        try {
            Log.d(
                    "pimalaya",
                    "mail bodies " + collection + " (" + handles.size() + "): "
                            + clients.offline().upgrade(this, collection, handles));
        } finally {
            // NOTE: every write naming them has returned, committed or not:
            // one no row names (the write failed) is an orphan to unlink.
            for (String hash : pinned) {
                PimdirItems.unpin(pimdir, hash);
            }
            PimdirItems.deferUnlink(pimdir, pinned);
            pinned.clear();
        }
        synchronized (locks.store) {
            for (Map.Entry<String, JSONObject> summary : summaries.entrySet()) {
                mail().restateFromBody(collection, summary.getKey(), summary.getValue());
            }
            FileStore files = new FileStore(pimdir.context(), pimdir);
            for (Map.Entry<String, List<MessageBody.Attachment>> parts : attachments.entrySet()) {
                // NOTE: one message failing leaves the others to record.
                try {
                    files.recordAttachments(accountId, parts.getKey(), parts.getValue());
                } catch (RuntimeException failure) {
                    Log.w("pimalaya", "attachments not recorded: " + parts.getKey(), failure);
                }
            }
        }
        List<MailBodies.Row> failed = new ArrayList<>();
        List<String> landed = new ArrayList<>();
        List<String> vanished = new ArrayList<>();
        for (String handle : handles) {
            MailBodies.Row row = downloading.get(handle);
            String key = MailOwed.key(collection, row.linkId);
            if (gone.contains(handle) && !answered.contains(handle)) {
                vanished.add(key);
            } else if (asked.contains(handle) && !answered.contains(handle)) {
                failed.add(row);
            } else {
                landed.add(key);
            }
        }
        MailState.of(pimdir.context()).gone.addAll(vanished);
        landed.addAll(vanished);
        MailOwed.settle(pimdir.context(), landed);
        if (!vanished.isEmpty()) {
            Log.i("pimalaya", "mail bodies " + collection + ": " + vanished.size() + " gone");
        }
        if (!failed.isEmpty()) {
            Log.w("pimalaya", "mail bodies " + collection + ": " + failed.size() + " not landed");
        }
        downloading.clear();
        summaries.clear();
        attachments.clear();
        asked.clear();
        answered.clear();
        gone.clear();
        return failed;
    }

    /**
     * The bodies of some messages, as the reply to a full fetch: each one
     * read whole into a file ({@link #sources}), unless a step read it
     * already ({@link #fetched}), filed in the object store under its hash
     * and answered by that hash and its size, with the link id and sort key
     * the store holds, so no body crosses the boundary twice. One the
     * server no longer has ({@link #gone}), or fails to hand over, is left
     * out and stays below {@code Full}, which {@link #download} answers as
     * gone or as not landed; an expired token fails the fetch, for the
     * caller to renew.
     */
    private JSONObject fetchBodies(String collection, JSONArray wanted) throws JSONException {
        List<String> missing = new ArrayList<>();
        Map<String, File> files = new HashMap<>();
        for (String handle : stringsOf(wanted)) {
            if (!downloading.containsKey(handle)) {
                continue;
            }
            asked.add(handle);
            File file = fetched.remove(handle);
            if (file != null) {
                files.put(handle, file);
            } else {
                missing.add(handle);
            }
        }
        File dir = null;
        long started = System.nanoTime();
        try {
            if (!missing.isEmpty()) {
                dir = scratch();
                try (MailTally.Watch counting = tally == null ? null : tally.watch(dir)) {
                    files.putAll(sources(mailboxOf(collection), missing, dir));
                }
            }
        } catch (RuntimeException failure) {
            if (expiredToken(failure)) {
                throw failure;
            }
            Log.w("pimalaya", "bodies not downloaded: " + collection, failure);
        } finally {
            remote(System.nanoTime() - started);
        }

        JSONArray items = new JSONArray();
        try {
            for (Map.Entry<String, File> entry : files.entrySet()) {
                JSONObject item = stored(collection, entry.getKey(), entry.getValue());
                if (item != null) {
                    items.put(item);
                    answered.add(entry.getKey());
                }
            }
        } finally {
            if (dir != null) {
                discard(dir);
            }
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        return reply;
    }

    /**
     * One message's file filed in the object store and answered as a full
     * fetch's item, its summary and parts kept for {@link #download} to
     * restate; null when it cannot be read.
     *
     * <p>The bridge reads the file once for its hash, its summary and its
     * parts ({@link MailClient#describeMessage}), and the file is moved
     * into the blob store, never copied: no body is read on this side or
     * crosses the boundary.
     */
    private JSONObject stored(String collection, String handle, File file) throws JSONException {
        MailBodies.Row row = downloading.get(handle);

        // NOTE: a body whose reading killed the app before is not read
        // again, and does not land (MailPoison).
        String key = MailOwed.key(collection, row.linkId);
        if (!MailPoison.begin(pimdir.context(), key)) {
            file.delete();
            return null;
        }
        JSONObject described;
        try {
            described = clients.mail().describeMessage(file);
        } catch (RuntimeException unreadable) {
            Log.w("pimalaya", "body not stored: " + collection + " " + handle, unreadable);
            file.delete();
            return null;
        } finally {
            MailPoison.end(pimdir.context(), key);
        }
        String hash = described.getString("hash");
        // NOTE: pinned before it is moved in and until the write naming it
        // has returned (download), so no collector unlinks it meanwhile.
        PimdirItems.pin(pimdir, hash);
        pinned.add(hash);
        try {
            new PimdirBlobs(pimdir.blobs()).adopt(hash, file);
        } catch (IOException unreadable) {
            Log.w("pimalaya", "body not stored: " + collection + " " + handle, unreadable);
            return null;
        }

        JSONObject summary = described.optJSONObject("summary");
        if (summary != null) {
            summaries.put(row.linkId, summary);
        }
        if (described.isNull("attachments")) {
            // NOTE: stored all the same; a body the parser cannot read
            // has no parts to record, and its reader says so.
            Log.w("pimalaya", "parts not read: " + collection + " " + handle);
        } else {
            attachments.put(
                    row.linkId,
                    MailClient.attachmentsOf(described.optJSONArray("attachments")));
        }

        JSONObject item = new JSONObject();
        item.put("handle", handle);
        item.put("linkId", row.linkId);
        item.put("sortKey", row.sortKey);
        item.put("hash", hash);
        item.put("persisted", described.getLong("size"));
        return item;
    }

    /**
     * What a step over {@code only} (the collection a view filtered to it
     * steps alone, null for the account) asks the bridge ({@link
     * #stepped}), each backend reading what it needs: about {@link
     * MailStep#COUNT} messages the store does not hold (each listed page
     * asked of {@link #held}), below the {@code frontier} (the mailbox's
     * own over it alone, else the account's; null for all mail or until
     * the account holds one), within the account's {@code bound}, after
     * the {@code anchors} the last JMAP step left ({@link
     * MailWindow#anchors}).
     */
    JSONObject stepRequest(String only) throws JSONException {
        Context context = pimdir.context();
        String frontier = null;
        if (MailWindow.held(context, accountId)) {
            frontier =
                    only == null
                            ? MailWindow.since(context, accountId)
                            : MailWindow.since(context, accountId, only);
        }
        String bound = MailScope.sinceOf(context, accountId);
        String mailbox = only == null ? null : mailboxOf(only);
        return new JSONObject()
                .put("count", MailStep.COUNT)
                .put("frontier", frontier == null ? JSONObject.NULL : frontier)
                .put("bound", bound == null ? JSONObject.NULL : bound)
                .put("anchors", new JSONArray(MailWindow.anchors(context, accountId, mailbox)));
    }

    /**
     * {@link #stepped}, its bodies counted as their files land ({@link
     * #tally}): the step's count planned before it reads, closed on what
     * it brought once it answers. {@code holding} the mailboxes whose
     * messages the step leaves out ({@link #held}).
     */
    JSONObject stepRead(String mailbox, List<String> holding, JSONObject request, File dir) {
        this.holding = holding;
        try {
            if (tally == null) {
                return stepped(mailbox, request, dir);
            }
            tally.expect(request.optInt("count", MailStep.COUNT));
            try (MailTally.Watch counting = tally.watch(dir)) {
                return stepped(mailbox, request, dir);
            } finally {
                tally.expect(0);
            }
        } finally {
            this.holding = List.of();
        }
    }

    /**
     * One step of the account read by the bridge ({@link MailStep}), as
     * {@code request} asks ({@link #stepRequest}), of {@code mailbox} or of
     * the account for null, each message read into a file of {@code dir}.
     * The bridge asks this driver which of each page it lists the store
     * holds ({@link #held}).
     */
    protected JSONObject stepped(String mailbox, JSONObject request, File dir) {
        return clients.mail().mailStep(session, mailbox, request, dir, this);
    }

    /**
     * The handles {@link #sources} found gone (deleted since they were
     * listed), apart from those not handed over, while a download runs.
     */
    protected final Set<String> gone = new HashSet<>();

    /**
     * Some messages' sources as the server holds them, each read into a
     * file of {@code dir}, by handle; one gone left out and added to
     * {@link #gone}, one not handed over left out.
     */
    protected Map<String, File> sources(String mailbox, List<String> handles, File dir) {
        return sources(session, mailbox, handles, dir);
    }

    /**
     * {@link #sources} on {@code on}, a session other than the driver's: the
     * reader's own, opened for the one message it fetches.
     */
    protected Map<String, File> sources(
            MailSession on, String mailbox, List<String> handles, File dir) {
        JSONObject reply = clients.mail().fetchMessageSources(on, mailbox, handles, dir);
        Map<String, File> files = new HashMap<>();
        JSONArray read = reply.optJSONArray("items");
        for (int index = 0; read != null && index < read.length(); index++) {
            JSONObject item = read.optJSONObject(index);
            if (item != null) {
                files.put(item.optString("id"), new File(item.optString("path")));
            }
        }
        JSONArray missing = reply.optJSONArray("gone");
        for (int index = 0; missing != null && index < missing.length(); index++) {
            gone.add(missing.optString(index));
        }
        return files;
    }

    @Override
    protected void applied(JSONArray effects) {
        MailStager.applied(pimdir, effects);
    }

    @Override
    protected JSONObject push(JSONObject yielded) throws JSONException {
        String collection = yielded.getString("collection");
        JSONArray changes = yielded.getJSONArray("changes");
        step(Progress.STAGE_UPLOAD, changes.length());

        JSONArray results = new JSONArray();
        for (int index = 0; index < changes.length(); index++) {
            results.put(pushOne(collection, changes.getJSONObject(index)));
        }

        JSONObject reply = new JSONObject();
        reply.put("results", results);
        return reply;
    }

    private JSONObject pushOne(String collection, JSONObject change) throws JSONException {
        String mailbox = mailboxOf(collection);
        String handle = change.getString("handle");

        switch (change.getString("op")) {
            case "setFlags":
                return pushFlags(collection, mailbox, handle, change);
            case "remove":
                return pushRemove(mailbox, handle, change);
            case "add":
                return pushAdd(collection, mailbox, handle, change);
            default:
                // NOTE: a message is immutable, so no update is ever
                // derived for one. Refusing keeps the placement staged
                // instead of reporting a write nobody made.
                Log.w("pimalaya", "unsupported mail push " + change.getString("op"));
                return result(handle, false, null, null);
        }
    }

    /**
     * A removal as staged (pimdir SYNC §4): one naming a destination is a
     * server move, a plain one a delete for good.
     *
     * <p>A move's pending create is withdrawn once the move is delivered:
     * the message arrives with the destination's next listing under the
     * handle the server gave it there, which is the identity a message is
     * filed under.
     */
    private JSONObject pushRemove(String mailbox, String handle, JSONObject change)
            throws JSONException {
        String to = change.isNull("to") ? null : change.optString("to", null);
        if (to == null) {
            try {
                destroy(mailbox, handle);
            } catch (RuntimeException failure) {
                gone(mailbox, handle, failure);
            }
            return result(handle, true, null, null);
        }
        if (!ownCollection(to)) {
            return result(handle, false, null, null);
        }

        // NOTE: a message gone is moved nowhere, so its target's create
        // goes as a delivered move's does.
        try {
            relocate(mailbox, handle, mailboxOf(to));
        } catch (RuntimeException failure) {
            gone(mailbox, handle, failure);
        }
        String linkId = change.optString("linkId", handle);
        synchronized (locks.store) {
            String create = offline.pendingCreateOf(to, linkId);
            if (create != null) {
                offline.withdrawCreate(to, create);
            }
        }
        return result(handle, true, null, null);
    }

    /**
     * A create as staged (pimdir SYNC §4): one with an origin is a
     * server-side copy, one without an append. Accepted with no handle
     * assigned, the copy arriving with the mailbox's next listing under the
     * handle the server gave it.
     *
     * <p>Three wait, rejected and so kept pending. A move's target is
     * delivered by its source's removal, which relocates it. A sent copy
     * waits for its submission, a copy filed for a message that was not
     * sent being a lie the sender reads as a sent one. And on an account
     * submitting over its session a create with no origin is a sent copy
     * the provider files itself, which its listing lands.
     */
    private JSONObject pushAdd(String collection, String mailbox, String handle, JSONObject change)
            throws JSONException {
        String linkId = change.optString("linkId", PimdirStorage.nameOf(handle));
        synchronized (locks.store) {
            // NOTE: withdrawn since the sync loaded it, by the move it was
            // the target of, delivered on another session.
            if (!offline.isPendingCreate(collection, linkId)
                    || offline.moveSourceOf(collection, linkId) != null) {
                return result(handle, false, null, null);
            }
        }

        JSONObject origin = change.optJSONObject("origin");
        if (origin != null) {
            String from = origin.getString("collection");
            if (!ownCollection(from)) {
                return result(handle, false, null, null);
            }
            // NOTE: a copy of a message gone has no arrival to wait for.
            try {
                copy(mailboxOf(from), origin.getString("handle"), mailbox);
            } catch (RuntimeException failure) {
                gone(mailboxOf(from), origin.getString("handle"), failure);
                return result(handle, false, null, null);
            }
            return result(handle, true, null, null);
        }

        if (AccountClient.submitsOverSession(session.account()) || mail().submitting(linkId)) {
            return result(handle, false, null, null);
        }

        byte[] source = mail().storedSource(collection, linkId);
        if (source == null) {
            return result(handle, false, null, null);
        }
        append(mailbox, source, Flags.read(change.optJSONArray("flags")));
        return result(handle, true, null, null);
    }

    /** Whether a collection is one of this account's mailboxes. */
    private boolean ownCollection(String collection) {
        return collection.startsWith(PimdirAccount.collectionId(accountId, ""));
    }

    /** Moves one message from {@code mailbox} into {@code target} on the server. */
    protected void relocate(String mailbox, String handle, String target) {
        clients.mail().relocateMessage(session, mailbox, handle, target);
    }

    /** Copies one message from {@code mailbox} into {@code target} on the server. */
    protected void copy(String mailbox, String handle, String target) {
        clients.mail().copyMessage(session, mailbox, handle, target);
    }

    /** Deletes one message for good on the server. */
    protected void destroy(String mailbox, String handle) {
        clients.mail().destroyMessage(session, mailbox, handle);
    }

    /** Appends one message to {@code mailbox} on the server. */
    protected void append(String mailbox, byte[] source, Flags flags) {
        clients.mail().append(session, mailbox, source, flags);
    }

    /**
     * Writes the markers that moved, one verb per marker.
     *
     * <p>The difference and not the set: both backends' write verbs add or
     * remove one marker, so what has to be worked out is which of the four
     * this app models changed since the source last agreed. A placement
     * with no agreed set is one the walk has just brought in, and the only
     * honest reading of the staged set there is that all four are stated.
     */
    private JSONObject pushFlags(
            String collection, String mailbox, String handle, JSONObject change)
            throws JSONException {
        Flags staged = Flags.read(change.optJSONArray("flags"));
        Flags base = offline.baseFlags(collection, handle);

        for (Flags.Marker flag : WRITABLE) {
            boolean wanted = staged.has(flag);
            if (base != null && wanted == base.has(flag)) {
                continue;
            }
            try {
                clients.mail().setMessageFlag(session, mailbox, handle, flag, wanted);
            } catch (RuntimeException failure) {
                gone(mailbox, handle, failure);
                break;
            }
        }
        return result(handle, true, null, null);
    }

    /**
     * Takes a write on one message failing with a 404 as the message gone
     * from the server since it was listed (deleted for good, purged from
     * the trash or the spam, a draft saved under a new id): nothing left
     * to remove, move or mark, so the change converged, and a copy of it
     * is refused. Any other failure is thrown on.
     *
     * <p>Answered here so a push never fails a pass with a 404, which
     * {@link #refused} reads as the server refusing the mailbox: a
     * mailbox gone fails its listing, before anything is pushed.
     */
    private static void gone(String mailbox, String handle, RuntimeException failure) {
        if (!isGone(failure)) {
            throw failure;
        }
        Log.w("pimalaya", "message gone from the server: " + mailbox + " " + handle);
    }

    /** The mailboxes of one walk, in the store's collection shape. */
    static List<PimdirCollections.Stored> collectionsOf(
            String accountEmail, String accountId, List<Mailbox> mailboxes) {
        List<PimdirCollections.Stored> listed = new ArrayList<>(mailboxes.size());
        for (Mailbox mailbox : mailboxes) {
            listed.add(
                    new PimdirCollections.Stored(
                            PimdirAccount.collectionId(accountId, mailbox.name),
                            accountEmail,
                            mailbox.name,
                            null,
                            null));
        }
        return listed;
    }
}
