package org.pimalaya;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Account;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The mail half of the engine: one driver per session, servicing the
 * remote yields of the mailboxes run on it.
 *
 * <p>What shapes it is that mail authenticates once per session. IMAP is a
 * session and JMAP is one session resource, so both backends list many
 * mailboxes inside one login, where a WebDAV collection is one request
 * each. The engine, on the other hand, reconciles one collection at a time.
 * The two meet in the session: a pass runs an account's mailboxes on a few
 * sessions side by side ({@link MailPool}), each answering the listings of
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
 * reaches for one.
 *
 * <p>A mailbox is listed whole, within the account's bound ({@link
 * MailScope}), newest first and a page at a time: each page lands in its own
 * write, so the top of the list shows while the rest fills in, and a pass
 * cut off resumes where its last page landed.
 */
class MailEngine extends PimdirEngine {
    /** The IMAP {@code \Seen} flag, as the store's JSON array spells it. */
    static final String SEEN = "\\Seen";

    /** The IMAP {@code \Answered} flag: the message was replied to. */
    static final String ANSWERED = "\\Answered";

    /** The IMAP {@code \Flagged} flag: the message was marked important. */
    static final String FLAGGED = "\\Flagged";

    /**
     * The IMAP {@code \Deleted} flag: the message is marked for removal by
     * a later expunge, which is what deleting means on an account naming
     * no trash to move it into.
     */
    static final String DELETED = "\\Deleted";

    /**
     * The markers this app writes, and the only ones a push ever touches.
     *
     * <p>The set is closed on purpose. A server marks messages with
     * keywords nobody here models ({@code $junk}, a label, a rule's own
     * tag), and a push that reasoned about anything outside this list
     * would have to decide what to do with them; diffing inside it means
     * it never sees them.
     */
    private static final String[] WRITABLE = {SEEN, ANSWERED, FLAGGED, DELETED};

    /**
     * Messages a mailbox's first pass lists, and a scroll past the list's
     * floor widens a mailbox by: a number of messages, never a span of time.
     */
    static final int FIRST_CHUNK = 50;

    /** Messages one step of the background fill widens a mailbox by. */
    static final int FILL_CHUNK = 500;

    /**
     * Told after every write a mail driver's pass lands, so a list showing
     * the store redraws as the pages of a first pass land; null when
     * nothing is listening. Called on the sync thread.
     */
    static volatile Runnable onWrite;

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

    MailEngine(PimdirDb pimdir, PimalayaClient client, MailSession session, String accountId) {
        super(pimdir, client);
        this.pimdir = pimdir;
        this.session = session;
        this.accountId = accountId;
    }

    /**
     * The account's mailboxes and the role each carries, in one round; a
     * Gmail account's are remembered as listed account-wide, and whether
     * the server erases one message alone.
     */
    List<Mailbox> mailboxes() {
        List<Mailbox> mailboxes = client.listMailboxes(session);
        MailStore.markAccountWide(
                pimdir.context(), accountId, PimalayaClient.isGoogle(session.account()));
        MailStore.markExpungesOne(pimdir.context(), accountId, session.expungesOne());
        return mailboxes;
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
    void sync(String collection) {
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
     *
     * <p>An account listed account-wide holds one floor: its mailboxes widen
     * below the most recent floor among them, sharing one listing. One
     * already at or below that chunk's floor (an inbox whose first chunk
     * reached further) widens below its own instead, so a scroll never
     * stalls.
     */
    boolean widen(String collection, int count) {
        String bound = bound();
        MailStore.Coverage coverage = mail().coverage(collection);
        if (coverage.filling || coverage.at == null) {
            sync(collection);
            return true;
        }
        if (!MailScope.limits(coverage.since, bound)) {
            return false;
        }
        boolean accountWide = MailStore.accountWide(pimdir.context(), accountId);
        String ceiling = accountWide ? accountFloor(coverage.since) : coverage.since;
        step(Progress.STAGE_SERVER, 0);
        String floor = timedFloor(collection, ceiling, count);
        if (accountWide && floor != null && coverage.since.compareTo(floor) <= 0) {
            floor = timedFloor(collection, coverage.since, count);
        }
        list(collection, MailScope.clamp(floor, bound));
        return true;
    }

    /**
     * The most recent floor among the account's mailboxes, {@code since} at
     * the least: where the account's one listing widens from.
     */
    private String accountFloor(String since) {
        String prefix = PimdirAccount.collectionId(accountId, "");
        String latest = since;
        for (MailStore.Edge edge : mail().edges()) {
            if (edge.collection.startsWith(prefix)
                    && edge.limit != null
                    && edge.limit.compareTo(latest) > 0) {
                latest = edge.limit;
            }
        }
        return latest;
    }

    /**
     * The floor one pass lists a mailbox from, within the account's bound:
     * where its round under way lists from, else what its last closed round
     * covered, so a pass after the first is a delta; and for a mailbox never
     * listed, its first chunk, the {@link #FIRST_CHUNK} newest messages.
     */
    private String scopeOf(String collection) {
        String bound = bound();
        MailStore.Coverage coverage = mail().coverage(collection);
        if (coverage.filling) {
            return MailScope.clamp(coverage.roundSince, bound);
        }
        if (coverage.at != null) {
            return MailScope.clamp(coverage.since, bound);
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
        return client.mailFloor(session, mailboxOf(collection), before, count);
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
                        + client.offlineSyncImmutable(this, collection, since, false)
                        + ", the pass so far " + clock);
    }

    /** The floor of the account's bound today, null for all of its mail. */
    private String bound() {
        return MailScope.sinceOf(pimdir.context(), accountId);
    }

    /** The account's mail store, for the coverage a pass starts from. */
    private MailStore mail() {
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
    static List<Mailbox> ordered(List<Mailbox> mailboxes) {
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
    private String mailboxOf(String collection) {
        return PimdirAccount.nameOf(accountId, collection);
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
        JSONObject request = new JSONObject();
        request.put("listing", yielded.getJSONObject("listing"));
        JSONObject scope = yielded.optJSONObject("scope");
        request.put("scope", scope == null ? new JSONObject() : scope);
        request.put("covered", covered(collection, scope));

        long started = System.nanoTime();
        String raw = client.enumerateMailboxRaw(session, mailboxOf(collection), request);
        JSONObject page = PimalayaClient.reply(raw);
        nameUnnamed(collection, page);
        nameByMessageId(collection, page);
        remote(System.nanoTime() - started);

        listed.clear();
        JSONArray items = page.optJSONArray("items");
        for (int index = 0; items != null && index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            listed.put(item.getString("handle"), item);
        }
        listed(items == null ? 0 : items.length());
        step(Progress.STAGE_DOWNLOAD, items == null ? 0 : items.length());
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
     * Names the members a page listed by id and markers alone (a Graph
     * delta), so nothing reaches the store unnamed (pimdir SYNC section 4):
     * one the store binds keeps the summary it holds, every other one is
     * read with its summary ({@link #read}), and one gone since it was
     * listed is left out.
     */
    void nameUnnamed(String collection, JSONObject page) throws JSONException {
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

        Map<String, String[]> bound = offline.bound(collection, unnamed);
        List<String> unbound = new ArrayList<>();
        for (String handle : unnamed) {
            if (!bound.containsKey(handle)) {
                unbound.add(handle);
            }
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
        synchronized (STORE) {
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
        synchronized (STORE) {
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
            if (create != null) {
                item.put("linkId", create);
            }
        }
    }

    /** The messages of a mailbox read by id with their summary, as listed. */
    protected JSONArray read(String collection, List<String> handles) {
        return client.nameMessages(session, mailboxOf(collection), handles);
    }

    @Override
    protected boolean listingsNamed() {
        return true;
    }

    /**
     * The named members of the last page, whichever tier is asked.
     *
     * <p>A sync never asks: a listing names everything it carries. A meta
     * upgrade revisiting a claim reads back what the last page named, and a
     * body is the reader's to fetch ({@link MailStore#saveSource}), one
     * message at a time and only one someone opened; answering one here
     * would be an entire mailbox of downloads to reconcile a listing.
     */
    @Override
    protected JSONObject fetch(JSONObject yielded) throws JSONException {
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

    /** Tells a listening list that a write landed, page by page. */
    @Override
    protected void applied(JSONArray effects) {
        Runnable listener = onWrite;
        if (listener != null && effects.length() > 0) {
            listener.run();
        }
    }

    /** What deleting one message staged, which its row and toast say. */
    enum Deletion {
        /** A submission withdrawn, with the sent copy staged beside it. */
        WITHDRAWN,
        /** Moved into the account's trash. */
        MOVED,
        /** Marked {@code \Deleted} in place, the row staying. */
        MARKED,
        /** Removed from the trash, a delete for good. */
        ERASED
    }

    /**
     * Stages one message's deletion, as its account allows.
     *
     * <p>A message waiting to go out was never sent, so withdrawing its
     * submission is the delete, the sent copy staged beside it going too.
     * Outside the trash it moves there; in the trash it is removed, which
     * the sync pushes as a delete for good. Where the account records no
     * trash, or its server erases no single message (no UIDPLUS, whose
     * plain EXPUNGE takes every marked message), it is marked
     * {@code \Deleted} and stays.
     */
    Deletion stageDelete(MailStore.StoredMessage message) throws JSONException {
        if (message.pending) {
            mail().acknowledge(message.queued);
            String sent = mail().sentOf(message.accountEmail);
            if (sent != null && offline.isPendingCreate(sent, message.id)) {
                mutateRemove(sent, PimdirStorage.provisionalOf(message.id));
            }
            return Deletion.WITHDRAWN;
        }

        String handle = offline.handleFor(message.collection, message.id);
        String trash = mail().trashOf(message.accountEmail);
        if (!trash.isEmpty() && !trash.equals(message.mailbox)) {
            mutateMove(message.collection, handle, mail().collectionOf(message.accountEmail, trash));
            return Deletion.MOVED;
        }
        if (!trash.isEmpty() && mail().expungesOne(message.accountEmail)) {
            mutateRemove(message.collection, handle);
            return Deletion.ERASED;
        }
        mutateFlags(
                message.collection,
                handle,
                withFlag(mail().flagsOf(message.collection, message.id), DELETED, true));
        return Deletion.MARKED;
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
            destroy(mailbox, handle);
            return result(handle, true, null, null);
        }
        if (!ownCollection(to)) {
            String create = offline.pendingCreateOf(to, change.optString("linkId", handle));
            return refuse(to, create, handle);
        }

        relocate(mailbox, handle, mailboxOf(to));
        String linkId = change.optString("linkId", handle);
        synchronized (STORE) {
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
     * sent being a lie the sender reads as a sent one. And off IMAP a
     * create with no origin is a sent copy the provider files itself,
     * which its listing lands.
     */
    private JSONObject pushAdd(String collection, String mailbox, String handle, JSONObject change)
            throws JSONException {
        String linkId = change.optString("linkId", PimdirStorage.nameOf(handle));
        synchronized (STORE) {
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
                return refuse(collection, linkId, handle);
            }
            copy(mailboxOf(from), origin.getString("handle"), mailbox);
            return result(handle, true, null, null);
        }

        Account server = session.account();
        if (PimalayaClient.isGraph(server)
                || PimalayaClient.isGoogle(server)
                || PimalayaClient.isJmap(server)) {
            return "sent".equals(mail().roles().get(collection))
                    ? result(handle, false, null, null)
                    : refuse(collection, linkId, handle);
        }
        if (mail().submitting(linkId)) {
            return result(handle, false, null, null);
        }

        byte[] source = mail().storedSource(collection, linkId);
        if (source == null) {
            return result(handle, false, null, null);
        }
        JSONArray flags = change.optJSONArray("flags");
        append(mailbox, source, flags == null ? new JSONArray() : flags);
        return result(handle, true, null, null);
    }

    /** Whether a collection is one of this account's mailboxes. */
    private boolean ownCollection(String collection) {
        return collection.startsWith(PimdirAccount.collectionId(accountId, ""));
    }

    /** Moves one message from {@code mailbox} into {@code target} on the server. */
    protected void relocate(String mailbox, String handle, String target) {
        client.relocateMessage(session, mailbox, handle, target);
    }

    /** Copies one message from {@code mailbox} into {@code target} on the server. */
    protected void copy(String mailbox, String handle, String target) {
        client.copyMessage(session, mailbox, handle, target);
    }

    /** Deletes one message for good on the server. */
    protected void destroy(String mailbox, String handle) {
        client.destroyMessage(session, mailbox, handle);
    }

    /** Appends one message to {@code mailbox} on the server. */
    protected void append(String mailbox, byte[] source, JSONArray flags) {
        client.appendMessage(session, mailbox, source, flags);
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
        JSONArray staged = change.optJSONArray("flags");
        JSONArray base = offline.baseFlags(collection, handle);

        for (String flag : WRITABLE) {
            boolean wanted = has(staged, flag);
            if (base != null && wanted == has(base, flag)) {
                continue;
            }
            client.setMessageFlag(session, mailbox, handle, flag, wanted);
        }
        return result(handle, true, null, null);
    }

    /** Whether a marker set names one marker. */
    static boolean has(JSONArray flags, String flag) {
        for (int index = 0; flags != null && index < flags.length(); index++) {
            if (flag.equals(flags.optString(index))) {
                return true;
            }
        }
        return false;
    }

    /** The marker set with one marker added or removed. */
    static JSONArray withFlag(JSONArray flags, String flag, boolean add) {
        JSONArray kept = new JSONArray();
        for (int index = 0; flags != null && index < flags.length(); index++) {
            if (!flag.equals(flags.optString(index))) {
                kept.put(flags.optString(index));
            }
        }
        if (add) {
            kept.put(flag);
        }
        return kept;
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
