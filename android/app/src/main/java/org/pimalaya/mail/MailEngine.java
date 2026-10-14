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
import org.pimalaya.client.Description;
import org.pimalaya.client.Flags;
import org.pimalaya.client.GraphStepReply;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.MessageBody;
import org.pimalaya.client.MessageRef;
import org.pimalaya.client.MessageSource;
import org.pimalaya.client.MessageSources;
import org.pimalaya.client.NamedStep;
import org.pimalaya.client.NamedStepItem;
import org.pimalaya.client.StepRequest;
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
import java.util.function.Supplier;

/**
 * The mail driver: one per session, serving its mailboxes' remote yields.
 *
 * <p>Mail logs in per session, the engine reconciles per collection: a pass
 * runs a few caller-opened sessions side by side ({@code WorkerPool}). Pages
 * land newest first in their own writes, named (pimdir SYNC section 4).
 */
public final class MailEngine extends PimdirEngine {
    /**
     * The markers this app writes, and the only ones a push ever touches.
     *
     * <p>Closed on purpose: servers carry keywords nobody here models
     * ({@code $junk}, labels), and diffing inside this set never sees them.
     */
    private static final Flags.Marker[] WRITABLE = {
        Flags.Marker.SEEN, Flags.Marker.ANSWERED, Flags.Marker.FLAGGED, Flags.Marker.DELETED
    };

    /**
     * Messages a mailbox's first pass lists: a count, never a time span.
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
     * Gmail sessions an account runs at once.
     *
     * <p>The account is one listing, read once per run, its metadata batches
     * spread over the sessions under one pacing bucket.
     */
    static final int GMAIL = 2;

    /**
     * How many sessions one account of this backend runs at once.
     *
     * <p>Four for Graph and JMAP (plain HTTP), three for IMAP (a login each),
     * two for Gmail, paced process-wide so more would queue on one bucket.
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

    /** The live connection; null on a stage-only driver, reaching no server. */
    private final MailSession session;

    /** What the account's collection ids are namespaced under. */
    private final String accountId;

    /** The store, for the account's bound kept beside it. */
    private final PimdirDb pimdir;

    /**
     * The members the last listed page named, by handle.
     *
     * <p>What a meta fetch reads back: the listing read all it would.
     */
    private final Map<String, JSONObject> listed = new HashMap<>();

    /** Counts the bodies bridge calls write ({@link MailTally}), or null. */
    public MailTally tally;

    /** A driver on {@code session}, null to stage only, for one account. */
    public MailEngine(PimdirDb pimdir, Clients clients, MailSession session, String accountId) {
        super(pimdir, clients);
        this.pimdir = pimdir;
        this.session = session;
        this.accountId = accountId;
    }

    /**
     * The account's mailboxes and their roles, in one round.
     *
     * <p>Records account-wide listing (Gmail, JMAP), All Mail (Gmail) and
     * single-message erase; other accounts' cross-linked bodies are released
     * once ({@link MailStore#repairLinks}).
     */
    public List<Mailbox> mailboxes() {
        List<Mailbox> mailboxes = clients.mail().listMailboxes(session);
        Account server = session.account();
        boolean accountWide = AccountClient.isGoogle(server) || AccountClient.isJmap(server);
        MailPolicy.markAccountWide(pimdir.context(), accountId, accountWide);
        MailPolicy.markAllMail(pimdir.context(), accountId, AccountClient.isGoogle(server));
        MailPolicy.markExpungesOne(pimdir.context(), accountId, session.expungesOne());
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
     * The bodies the store holds under some link ids, within this account.
     *
     * <p>A link id spans mailboxes only on account-wide listings (Gmail, JMAP);
     * elsewhere a handle is one mailbox's (an IMAP UID, a Graph id), so nothing
     * links and every body is fetched.
     */
    @Override
    protected JSONObject lookup(JSONArray links) throws JSONException {
        return accountWide()
                ? offline.lookupObjects(links, accountId)
                : new JSONObject().put("objects", new JSONObject());
    }

    /**
     * Reconciles one mailbox with its server: list, then push what is staged.
     *
     * <p>Within the bound on {@code Date}; no checkpoint is bound to a scope,
     * so a widened bound lists only the band it lacks. No hydrate or upgrade
     * after: a summary is a message's ordinary state, and listings name all.
     */
    public void sync(String collection) {
        step(Progress.STAGE_SERVER, 0);
        list(collection, scopeOf(collection));
    }

    /**
     * Widens one mailbox by its next chunk; answers whether it could.
     *
     * <p>The {@code count} newest below its floor (a count, never a span), the
     * oldest becoming the floor, within the bound. Never listed: its first
     * chunk; a round under way resumes. Only the band lists, every backend.
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
     * Lists one mailbox from {@code since} (null for all), within the bound.
     *
     * <p>The round an IMAP first step opens each mailbox with; one listed or
     * under way syncs as {@link #sync} does.
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
     * Lists a mailbox down to {@code since} unless covered; answers whether.
     *
     * <p>Null for all mail, within the bound: a step's missing band, or the
     * whole of a mailbox never listed.
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
     * The floor one pass lists a mailbox from, within the account's bound.
     *
     * <p>A round's under way, else the last coverage (a delta), else the
     * {@link #FIRST_CHUNK} newest; account-wide (Gmail) its bound, whose round
     * names what the store holds ({@link #nameUnnamed}).
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
     * A mailbox's {@code count} newest {@code Date}s, newest first, or fewer.
     */
    List<String> newest(String collection, int count) {
        return clients.mail().floor(session, mailboxOf(collection), null, count).dates();
    }

    /**
     * Whether a failure is the server refusing one mailbox, connection intact.
     *
     * <p>A 404: an IMAP mailbox refused {@code EXAMINE}/{@code SELECT}, a Graph
     * folder or Gmail label gone. Steps and passes skip it. Only a mailbox's
     * reads land here; a push's 404 is a message gone ({@link #gone}).
     */
    public static boolean refused(Exception failure) {
        return isGone(failure);
    }

    /** Skips and logs a {@link #refused} mailbox; other failures throw on. */
    static void skip(String collection, RuntimeException failure) {
        if (!refused(failure)) {
            throw failure;
        }
        Log.w("pimalaya", "mailbox refused, skipped: " + collection + ": " + failure.getMessage());
    }

    /**
     * The floor of a mailbox's next chunk, null when it is whole there.
     *
     * <p>The oldest {@code Date} of its {@code count} newest before
     * {@code before} (null for none), timed as network toward the first page.
     */
    private String timedFloor(String collection, String before, int count) {
        long started = System.nanoTime();
        try {
            return clients.mail().floor(session, mailboxOf(collection), before, count).floor();
        } finally {
            remote(System.nanoTime() - started);
        }
    }

    /**
     * Runs the engine's round or delta over a mailbox from {@code since}.
     *
     * <p>No checkpoint is bound to its scope, so a widening is a band round.
     */
    private void list(String collection, String since) {
        Log.d(
                "pimalaya",
                "mail sync " + collection + " since " + since + ": "
                        + clients.offline().syncImmutable(this, collection, since, false)
                        + ", the pass so far " + clock);
        MailState.of(pimdir.context()).relisted(collection);
    }

    /** A mailbox's bound floor today: its account's, null when kept whole. */
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
     * The order a pass takes an account's mailboxes in.
     *
     * <p>Inbox, sent, drafts, others by name, junk and trash last: the first
     * dialog waits on the inbox, and every pass lands it first.
     */
    public static List<Mailbox> ordered(List<Mailbox> mailboxes) {
        List<Mailbox> ordered = new ArrayList<>(mailboxes);
        ordered.sort(
                java.util.Comparator.comparingInt((Mailbox mailbox) -> rank(mailbox.role()))
                        .thenComparing(mailbox -> mailbox.name(), String.CASE_INSENSITIVE_ORDER));
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
        return MailPolicy.accountWide(pimdir.context(), accountId);
    }

    /**
     * The mailboxes the step under way holds messages in; none outside one.
     */
    private List<String> holding = List.of();

    /**
     * Which handles the store binds in the step's {@link #holding} mailboxes.
     *
     * <p>Asked by the bridge of each page a step lists, so no step reads the
     * account's every handle.
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
     * A fresh cache directory for one read of whole messages into files.
     *
     * <p>Filled by {@link #sources}; the caller deletes it.
     */
    static File scratch(Context context) {
        File dir =
                new File(
                        new File(context.getCacheDir(), SCRATCH),
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

    /** The page a step lands, answered by the next enumerate, else null. */
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
     * Lands a step's members of one mailbox, named as listed ones are.
     *
     * <p>On an empty mailbox opening a round, they are that round, closed with
     * {@code checkpoint} (the history id before the step), so the next pass is
     * a delta; elsewhere a delta adding them, finding nothing absent.
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
     * One page of a mailbox's listing, straight from the backend.
     *
     * <p>Every member named by the header fields the listing read.
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

    /** A step's page ({@link #land}): an empty box's round, else a delta. */
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
     * Keeps a page's names for meta fetches, counts it, owes arrivals.
     *
     * <p>With {@code arrivals}, a delta's members new to the store are owed
     * their body whatever the date ({@link MailOwed}); a step's download
     * anyway.
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
     * Whether the store's coverage of a mailbox already holds a scope.
     *
     * <p>A round then lists changes, a first chunk its band. Graph lands a
     * first chunk by {@code /messages} with no delta link; a later round over
     * the covered scope makes that link.
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
     * Names members a page listed by id and markers alone (pimdir SYNC 4).
     *
     * <p>Bound ones keep their summary, others are read
     * ({@link MailClient#nameMessages}), gone ones dropped. On Gmail rounds
     * only refiled or new arrivals are read ({@link #steppedIn}).
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
            JSONArray named =
                    clients.mail().nameMessages(session, mailboxOf(collection), unbound);
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
     * Which unheld members of a Gmail round's page are named.
     *
     * <p>Those held elsewhere, and those ahead of every held message, unless a
     * band round: listed newest received first, they arrived after.
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
     * Whether the round met a held message: past it, unheld means older mail.
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
        if (MailPolicy.keepsAllMail(pimdir.context(), accountId)) {
            collections.add(MailStore.allMailOf(accountId));
        }
        return collections;
    }

    /**
     * Names an arrival by the pending create sharing its {@code Message-ID}.
     *
     * <p>So the engine lands the create on it (pimdir SYNC §6), not a second
     * copy: a provider-filed sent copy. Already bound members stay as named.
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
                String staged = mail().policy().stagedSentCopy(messageId);
                create = staged == null ? null : creates.remove(staged);
                if (create != null) {
                    mail().policy().forgetSentAlias(messageId);
                }
            }
            if (create != null) {
                item.put("linkId", create);
            }
        }
    }

    @Override
    protected boolean listingsNamed() {
        return true;
    }

    /**
     * The last page's members at meta, or {@link #download}'s bodies at full.
     *
     * <p>A sync never asks; a meta upgrade reads back the last page. Full is
     * the body step's alone, the reader fetching itself.
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

    /** The rows {@link #download} asked for, by handle, during its upgrade. */
    private final Map<String, MailBodies.Row> downloading = new HashMap<>();

    /** The bodies {@link #stored} pinned, while the upgrade runs. */
    private final List<String> pinned = new ArrayList<>();

    /** The handles the upgrade asked the server for, while it runs. */
    private final Set<String> asked = new HashSet<>();

    /** The handles whose body was handed over and filed, while it runs. */
    private final Set<String> answered = new HashSet<>();

    /** Files of messages a step read whole, by handle, a fetch reuses. */
    private final Map<String, File> fetched = new HashMap<>();

    /** Each fetched body's summary, by link id, restated once it lands. */
    private final Map<String, JSONObject> summaries = new HashMap<>();

    /** Each fetched body's attachments, by link id, recorded once it lands. */
    private final Map<String, List<MessageBody.Attachment>> attachments = new HashMap<>();

    /**
     * Hands over a step's whole-message files for downloads to store.
     *
     * <p>An empty map once they are done.
     */
    void fetched(Map<String, File> files) {
        fetched.clear();
        fetched.putAll(files);
    }

    /**
     * Downloads some bodies of one mailbox through the upgrade to {@code Full}.
     *
     * <p>A held body links; pending ones and those off the frontier (unless
     * {@link MailBodies.Row#always}) are skipped. Summary and parts restated.
     * Answers rows not landed; a gone one is marked {@link MailState#gone}.
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
     * Some bodies as a full fetch's reply, filed and answered by hash and size.
     *
     * <p>Read into files ({@link #sources}) unless a step did; none crosses the
     * boundary. Gone or unhanded ones are left out, below {@code Full}; an
     * expired token fails the fetch for the caller to renew.
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
                dir = scratch(pimdir.context());
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
     * Files one message's file as a full fetch's item, or null when unreadable.
     *
     * <p>The bridge reads it once ({@link MailClient#describeMessage}) and it
     * is moved, never copied, into the blob store; summary and parts are kept
     * for {@link #download}. No body crosses the boundary.
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
        Description described;
        try {
            described = clients.mail().describeMessage(file);
        } catch (RuntimeException unreadable) {
            Log.w("pimalaya", "body not stored: " + collection + " " + handle, unreadable);
            file.delete();
            return null;
        } finally {
            MailPoison.end(pimdir.context(), key);
        }
        String hash = described.hash();
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

        JSONObject summary = described.summary();
        if (summary != null) {
            summaries.put(row.linkId, summary);
        }
        if (described.attachments() == null) {
            // NOTE: stored all the same; a body the parser cannot read
            // has no parts to record, and its reader says so.
            Log.w("pimalaya", "parts not read: " + collection + " " + handle);
        } else {
            attachments.put(row.linkId, described.attachments());
        }

        JSONObject item = new JSONObject();
        item.put("handle", handle);
        item.put("linkId", row.linkId);
        item.put("sortKey", row.sortKey);
        item.put("hash", hash);
        item.put("persisted", described.size());
        return item;
    }

    /**
     * What a step over {@code only} (null for the account) asks the bridge.
     *
     * <p>About {@link MailStep#COUNT} unheld messages below the frontier,
     * within the bound, after the JMAP {@link MailWindow#anchors}, over
     * {@code mailboxes} (null for none); see {@link #stepRead}.
     */
    StepRequest stepRequest(String only, List<String> mailboxes) {
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
        return new StepRequest(
                mailbox,
                MailStep.COUNT,
                frontier,
                bound,
                MailWindow.anchors(context, accountId, mailbox),
                mailboxes);
    }

    /**
     * One Gmail or JMAP step read by the bridge, as {@code request} asks.
     *
     * <p>Each message read into a file of {@code dir}, {@link #counted}; the
     * bridge asks which of each page the store holds ({@link #held}).
     */
    NamedStep stepRead(List<String> holding, StepRequest request, File dir) {
        return counted(
                holding, request, dir, () -> clients.mail().step(session, request, dir, this));
    }

    /** {@link #stepRead} of a Microsoft Graph account. */
    GraphStepReply graphStepRead(List<String> holding, StepRequest request, File dir) {
        return counted(
                holding,
                request,
                dir,
                () -> clients.mail().graphStep(session, request, dir, this));
    }

    /**
     * Runs {@code step} with its bodies counted as files land ({@link #tally}).
     *
     * <p>Its count planned before, closed on what it brought; {@code holding}
     * names the mailboxes whose messages it leaves out ({@link #held}).
     */
    private <T> T counted(
            List<String> holding, StepRequest request, File dir, Supplier<T> step) {
        this.holding = holding;
        try {
            if (tally == null) {
                return step.get();
            }
            tally.expect(request.count());
            try (MailTally.Watch counting = tally.watch(dir)) {
                return step.get();
            } finally {
                tally.expect(0);
            }
        } finally {
            this.holding = List.of();
        }
    }

    /** A step's item on the engine's wire, its file and labels left out. */
    static JSONObject wireItem(NamedStepItem item) throws JSONException {
        JSONObject named = new JSONObject();
        named.put("handle", item.handle());
        named.put("flags", new JSONArray(item.flags()));
        named.put("linkId", item.linkId());
        if (item.summary() != null) {
            named.put("summary", item.summary());
        }
        if (!item.sortKey().isEmpty()) {
            named.put("sortKey", item.sortKey());
        }
        return named;
    }

    /** The handles {@link #sources} found gone while a download runs. */
    private final Set<String> gone = new HashSet<>();

    /**
     * Some messages' sources, each read into a file of {@code dir}, by handle.
     *
     * <p>A gone one is left out and added to {@link #gone}; one not handed over
     * is left out.
     */
    private Map<String, File> sources(String mailbox, List<String> handles, File dir) {
        MessageSources reply = clients.mail().fetchSources(session, mailbox, handles, dir);
        Map<String, File> files = files(reply);
        gone.addAll(reply.gone());
        return files;
    }

    /**
     * The files a read of whole messages wrote, by handle.
     *
     * <p>This driver's ({@link #sources}) and the reader's
     * ({@link MailReader#fetch}).
     */
    static Map<String, File> files(MessageSources reply) {
        Map<String, File> files = new HashMap<>();
        for (MessageSource item : reply.items()) {
            files.put(item.id(), new File(item.path()));
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
     * A staged removal (pimdir SYNC §4): a move to a destination, or a delete.
     *
     * <p>A move's pending create is withdrawn once delivered: the message comes
     * back with the destination's listing under its new handle.
     */
    private JSONObject pushRemove(String mailbox, String handle, JSONObject change)
            throws JSONException {
        String to = change.isNull("to") ? null : change.optString("to", null);
        if (to == null) {
            try {
                clients.mail().destroy(session, new MessageRef(mailbox, handle));
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
            clients.mail().relocate(session, new MessageRef(mailbox, handle), mailboxOf(to));
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
     * A staged create (pimdir SYNC §4): with an origin a copy, else an append.
     *
     * <p>Arrives with the next listing. Kept pending: a move's target, a sent
     * copy before its submission went, and a provider-filed sent copy (no
     * origin, account submitting over its session).
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
                clients.mail()
                        .copy(
                                session,
                                new MessageRef(mailboxOf(from), origin.getString("handle")),
                                mailbox);
            } catch (RuntimeException failure) {
                gone(mailboxOf(from), origin.getString("handle"), failure);
                return result(handle, false, null, null);
            }
            return result(handle, true, null, null);
        }

        if (AccountClient.submitsOverSession(session.account()) || mail().outbox().submitting(linkId)) {
            return result(handle, false, null, null);
        }

        byte[] source = mail().storedSource(collection, linkId);
        if (source == null) {
            return result(handle, false, null, null);
        }
        clients.mail().append(session, mailbox, source, Flags.read(change.optJSONArray("flags")));
        return result(handle, true, null, null);
    }

    /** Whether a collection is one of this account's mailboxes. */
    private boolean ownCollection(String collection) {
        return collection.startsWith(PimdirAccount.collectionId(accountId, ""));
    }

    /**
     * Writes the markers that moved, one verb per marker.
     *
     * <p>The difference since last agreed, not the set: verbs add or remove
     * one. With no agreed set (just walked in), all four are stated.
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
                clients.mail().setFlag(session, new MessageRef(mailbox, handle), flag, wanted);
            } catch (RuntimeException failure) {
                gone(mailbox, handle, failure);
                break;
            }
        }
        return result(handle, true, null, null);
    }

    /**
     * Takes a write's 404 as the message gone since listed: converged.
     *
     * <p>Nothing left to remove, move or mark; a copy is refused. Answered so a
     * push never fails a pass on a 404 {@link #refused} would misread. Other
     * failures throw on.
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
                            PimdirAccount.collectionId(accountId, mailbox.name()),
                            accountEmail,
                            mailbox.name(),
                            null,
                            null));
        }
        return listed;
    }
}
