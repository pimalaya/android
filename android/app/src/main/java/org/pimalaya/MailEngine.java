package org.pimalaya;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.MailRef;
import org.pimalaya.client.MailRound;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.Message;
import org.pimalaya.client.PimalayaClient;

import java.util.ArrayList;
import java.util.List;

/**
 * The mail half of the engine: one driver per account, servicing the
 * remote yields of every mailbox it holds.
 *
 * <p>What shapes it is that mail authenticates once for the whole
 * account. IMAP is a session and JMAP is one session resource, so both
 * backends list every mailbox inside one login, where a WebDAV
 * collection is one request each. The engine, on the other hand,
 * reconciles one collection at a time. The two meet in {@link #walk}: one
 * account-wide walk primes a cache, and every mailbox's enumerate and
 * meta fetch is answered out of it. It is the same shape the contacts
 * driver uses for the account-level backends, and it is what keeps a sync
 * at one connection per account rather than one per mailbox.
 *
 * <p>The session it walks and writes on is the caller's, opened once
 * for the pass and closed with it, so the three markers a reader moved
 * go out on the connection the walk already had rather than on three of
 * their own.
 *
 * <p>A meta fetch costs nothing for the second reason mail is unusual: a
 * message's handle <em>is</em> its link id, and the summary a listing
 * renders comes off the envelope the walk already read. Only the body
 * needs the network, which is why opening a message is the one read that
 * still reaches for one.
 */
final class MailEngine extends PimdirEngine {
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

    /** How many messages a full round takes off the end of a mailbox. */
    private static final int PER_MAILBOX = 50;

    /** The account's live connection; null on a driver that only stages,
     *  whose mutations reach no server. */
    private final MailSession session;

    /** What the account's collection ids are namespaced under. */
    private final String accountId;

    MailEngine(PimdirDb pimdir, PimalayaClient client, MailSession session, String accountId) {
        super(pimdir, client);
        this.session = session;
        this.accountId = accountId;
    }

    /** The account's mailboxes and the role each carries, in one round. */
    List<Mailbox> mailboxes() {
        return client.listMailboxes(session);
    }

    /**
     * Reconciles one mailbox with the walk: pull, then push what is staged.
     *
     * <p>No hydrate after it, unlike a calendar: a mailbox is a spine and
     * a message rises off it by being opened, so a placement below full is
     * the ordinary state of one rather than something to repair. A probe
     * is not: the sync files a new message as an unnamed handle with no
     * summary, which no listing shows, so a meta upgrade names it off its
     * envelope.
     */
    void sync(String collection) {
        step(Progress.STAGE_SERVER, 0);
        Log.d(
                "pimalaya",
                "mail sync " + collection + ": " + client.offlineSync(this, collection, false));

        List<String> probed = offline.probedHandles(collection);
        if (!probed.isEmpty()) {
            Log.d(
                    "pimalaya",
                    "name " + collection + " (" + probed.size() + " probed): "
                            + client.offlineUpgradeMeta(this, collection, probed));
        }
    }

    /** The mailbox behind a collection id, which is what IMAP names it by. */
    private String mailboxOf(String collection) {
        return PimdirAccount.nameOf(accountId, collection);
    }

    @Override
    protected JSONObject enumerate(JSONObject yielded) throws JSONException {
        String collection = yielded.getString("collection");
        String cursor = yielded.isNull("cursor") ? null : yielded.getString("cursor");
        MailRound round =
                client.enumerateMailbox(session, mailboxOf(collection), cursor, PER_MAILBOX);

        JSONArray items = new JSONArray();
        for (MailRef message : round.items) {
            JSONObject item = new JSONObject();
            item.put("handle", message.id);
            item.put("flags", new JSONArray(message.flags));
            items.put(item);
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        reply.put("vanished", new JSONArray(round.vanished));
        // NOTE: a complete round is a claim worth being careful about: it
        // covers the window this store holds and not the mailbox, so
        // everything older than the window reads as vanished and is
        // dropped. That is what the mirror has always held, and saying
        // otherwise would leave rows nothing can refresh. A QRESYNC round
        // is a delta and says so, so it retires only what the server
        // reported gone.
        reply.put("complete", round.complete);
        if (!round.checkpoint.isEmpty()) {
            reply.put("checkpoint", round.checkpoint);
        }
        return reply;
    }

    /**
     * The envelopes of the named messages, with no body whichever tier is
     * asked.
     *
     * <p>Those and no others: a pass that found one new message reads one
     * envelope, where the spine used to arrive as a window of every
     * mailbox whether or not anything in it moved.
     *
     * <p>A body is a different question, and the reader's: it fetches the
     * bytes and stores them ({@link MailStore#saveSource}), one message at
     * a time and only ever one someone asked for. Answering one here would
     * be an entire mailbox of downloads to reconcile a spine, and a
     * message crossing a wire that carries text where a message is bytes.
     */
    @Override
    protected JSONObject fetch(JSONObject yielded) throws JSONException {
        String collection = yielded.getString("collection");
        String mailbox = mailboxOf(collection);
        List<String> handles = stringsOf(yielded.getJSONArray("handles"));

        JSONArray items = new JSONArray();
        for (Message message : client.fetchEnvelopes(session, mailbox, handles)) {
            JSONObject item = new JSONObject();
            item.put("handle", message.id);
            // The handle is the identity: an IMAP UID names the message
            // within its mailbox and a JMAP Email id across the account,
            // which is exactly what a link id has to do.
            item.put("linkId", message.id);
            item.put(
                    "summary",
                    PimdirSummary.mail(
                            null,
                            message.subject,
                            message.from,
                            message.fromAddress,
                            null,
                            message.date,
                            0,
                            message.hasAttachment));
            item.put("sortKey", PimdirSummary.mailSortKey(message.date));
            items.put(item);
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        return reply;
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
