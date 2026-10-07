package org.pimalaya;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The mail half of the engine: one driver per account, servicing the
 * remote yields of every mailbox it holds.
 *
 * <p>What shapes it is that mail authenticates once for the whole
 * account. IMAP is a session and JMAP is one session resource, so both
 * backends list every mailbox inside one login, where a WebDAV
 * collection is one request each. The engine, on the other hand,
 * reconciles one collection at a time. The two meet in the session: one
 * connection per account answers every mailbox's listing in turn, which is
 * what keeps a sync at one connection per account rather than one per
 * mailbox.
 *
 * <p>The session it walks and writes on is the caller's, opened once
 * for the pass and closed with it, so the three markers a reader moved
 * go out on the connection the walk already had rather than on three of
 * their own.
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

    /** The account's mailboxes and the role each carries, in one round. */
    List<Mailbox> mailboxes() {
        return client.listMailboxes(session);
    }

    /**
     * Reconciles one mailbox with its server: list, then push what is
     * staged.
     *
     * <p>Within the account's bound: the scope's floor on the {@code Date}
     * header, or none. Whether the backend's checkpoint is bound to the
     * scope it was made under decides what a widened bound lists: a Graph
     * delta link made under a filter relists the wider scope, an IMAP
     * modseq, a Gmail history id or a JMAP state lists only the band it
     * lacks.
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
     * <p>Where the backend's checkpoint is bound to no scope (IMAP, Gmail,
     * JMAP), the wider scope lists only the band below the old floor. A Graph
     * delta link made under a filter is bound to it, so the wider scope is
     * listed whole again: pimdir keeps one checkpoint per source, and a band
     * listed apart from it would leave the delta blind to what the band holds.
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
        step(Progress.STAGE_SERVER, 0);
        String floor = floor(collection, coverage.since, count);
        list(collection, MailScope.clamp(floor, bound));
        return true;
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
        String floor = floor(collection, null, FIRST_CHUNK);
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

    /** Runs the engine's round or delta over a mailbox from {@code since}. */
    private void list(String collection, String since) {
        boolean scopeBound = session != null && PimalayaClient.isGraph(session.account());
        Log.d(
                "pimalaya",
                "mail sync " + collection + " since " + since + ": "
                        + client.offlineSyncImmutable(this, collection, since, scopeBound)
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
    protected JSONObject enumerate(JSONObject yielded) throws JSONException {
        String collection = yielded.getString("collection");
        JSONObject request = new JSONObject();
        request.put("listing", yielded.getJSONObject("listing"));
        JSONObject scope = yielded.optJSONObject("scope");
        request.put("scope", scope == null ? new JSONObject() : scope);

        long started = System.nanoTime();
        String raw = client.enumerateMailboxRaw(session, mailboxOf(collection), request);
        remote(System.nanoTime() - started);
        JSONObject page = PimalayaClient.reply(raw);

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
                client.deleteMessage(session, mailbox, handle);
                return result(handle, true, null, null);
            default:
                // NOTE: a message is not authored here and never edited: the
                // one thing this app composes goes out through the outbox and
                // is submitted rather than appended. Refusing keeps the
                // placement staged instead of reporting a write nobody made.
                Log.w("pimalaya", "unsupported mail push " + change.getString("op"));
                return result(handle, false, null, null);
        }
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
