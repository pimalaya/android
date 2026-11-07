package org.pimalaya.mail;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONObject;
import org.pimalaya.R;
import org.pimalaya.client.Flags;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.client.PimdirSql;
import org.pimalaya.mail.MailStore.StoredMessage;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirItems;
import org.pimalaya.pimdir.PimdirQueue;
import org.pimalaya.pimdir.PimdirSummary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outbox: each account's pending submissions on the store's queue.
 *
 * <p>Not a mailbox ({@link PimdirQueue}): what is queued, what the drain hands
 * over, and what was parked.
 */
public final class Outbox {
    /**
     * The outbox's own collection: what its rows show under, and the anchor
     * of a submission whose account marks no sent mailbox.
     *
     * <p>A control character no server hands out and no user types, so it never
     * collides with a real mailbox. It holds no items.
     */
    private static final String OUTBOX = "\u0001outbox";

    /** A parked reason's prefix when the message may have gone, not refused. */
    static final String UNCERTAIN = "uncertain: ";

    /** The rows being handed over, kept on disk so a crash mid-way shows. */
    private static final String HANDING = "handing";


    private final Context context;
    private final PimdirItems items;
    private final PimdirAccount accounts;
    private final PimdirQueue queue;

    /** An account's sent mailbox, or null ({@link MailStore#sentOf}). */
    private final java.util.function.Function<String, String> sent;

    /**
     * What each queued row's message derives to, by body and payload: a body
     * is immutable under its name, so a listing derives it once.
     */
    private final Map<String, Submission> derived =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Submission> eldest) {
                    return size() > 256;
                }
            });

    Outbox(Context context, PimdirItems items, PimdirAccount accounts, PimdirQueue queue,
            java.util.function.Function<String, String> sent) {
        this.context = context;
        this.items = items;
        this.accounts = accounts;
        this.queue = queue;
        this.sent = sent;
    }

    /**
     * Whether a submission of the message is still waiting or parked.
     *
     * <p>The sent copy staged for it is then not to be filed yet.
     */
    boolean submitting(String messageId) {
        for (PimdirQueue.Action action : submissions()) {
            if (messageId.equals(submission(action).messageId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * What a listing draws of one queued submission, derived from its message
     * ({@link Submission}).
     */
    Submission submission(PimdirQueue.Action action) {
        String key = action.objectHash() + "\n" + action.payload();
        Submission known = derived.get(key);
        if (known == null) {
            known = Submission.of(action.payload(), queue.body(action.objectHash()));
            derived.put(key, known);
        }
        return known;
    }

    /** Every submission still in an outbox, waiting or parked. */
    List<PimdirQueue.Action> submissions() {
        List<PimdirQueue.Action> actions = new ArrayList<>(queue.pending());
        actions.addAll(queue.parked());
        actions.removeIf(action -> !PimdirQueue.SUBMIT.equals(action.kind()));
        return actions;
    }

    /** Whether a collection is an account's outbox ({@link #outboxOf}). */
    public static boolean holds(String collection) {
        return collection.endsWith(OUTBOX);
    }

    /** Where one account's outgoing messages show, in the list and the filter. */
    public String outboxOf(String accountEmail) {
        return PimdirAccount.collectionId(accounts.idOf(accountEmail), OUTBOX);
    }

    /**
     * The collection a submission from one account is anchored on (STORAGE
     * Annex B.2, section 15.6): the one its sent copy belongs to, the
     * account's sent mailbox, else the outbox's own, for an account marking
     * none or whose roster has not been read yet.
     */
    String anchorOf(String accountEmail) {
        String anchor = sent.apply(accountEmail);
        return anchor != null ? anchor : outboxOf(accountEmail);
    }

    /** Whether a queue row is anchored on one of the account's collections. */
    private boolean anchoredIn(String accountEmail, PimdirQueue.Action action) {
        return action.collection()
                .startsWith(PimdirAccount.collectionId(accounts.idOf(accountEmail), ""));
    }

    /**
     * Every submission of one account still in the outbox, waiting or parked,
     * wherever it is anchored.
     */
    List<PimdirQueue.Action> submissions(String accountEmail) {
        List<PimdirQueue.Action> actions = submissions();
        actions.removeIf(action -> !anchoredIn(accountEmail, action));
        return actions;
    }

    /** Anchors an account's submissions on its outbox, as they were. */
    void reanchor(String accountEmail) {
        String outbox = outboxOf(accountEmail);
        for (PimdirQueue.Action action : submissions(accountEmail)) {
            if (action.collection().equals(outbox)) {
                continue;
            }
            long moved =
                    queue.enqueue(
                            new PimdirQueue.Enqueued(
                                    outbox,
                                    accountEmail,
                                    action.kind(),
                                    action.payload(),
                                    queue.body(action.objectHash())));
            if (!action.pending()) {
                queue.park(moved, action.error());
            }
            queue.acknowledge(action.id());
        }
    }

    /**
     * Stages one composed message from {@code from} for the next drain to hand
     * over, as Annex B.2's {@code submit} ({@link Submission#payload}),
     * anchored on its sent copy's mailbox ({@link #anchorOf}).
     */
    void queueSubmission(String from, byte[] source) {
        queue.enqueue(
                new PimdirQueue.Enqueued(
                        anchorOf(from),
                        from,
                        PimdirQueue.SUBMIT,
                        Submission.payload(from, source),
                        source));
    }

    /**
     * Everything waiting on the queue, across every account, newest first.
     *
     * <p>Parked rows among them: a refused message is still a message the
     * sender wrote, and the row it shows says so rather than disappearing.
     */
    public List<StoredMessage> outgoing() {
        List<StoredMessage> messages = new ArrayList<>();
        for (PimdirQueue.Action action : submissions()) {
            messages.add(outgoing(action));
        }
        java.util.Collections.sort(messages, (left, right) -> Long.compare(right.queued.id(), left.queued.id()));
        return messages;
    }

    /** One queued submission as a row, read off its payload. */
    private StoredMessage outgoing(PimdirQueue.Action action) {
        Submission submission = submission(action);
        String from = submission.from();

        return StoredMessage.outgoing(
                // NOTE: under the outbox, wherever the row is anchored: the
                // filter's outbox box shows and drains it.
                new StoredMessage.MessageHeader(
                        from,
                        outboxOf(from),
                        outboxName(),
                        submission.messageId(),
                        submission.subject(),
                        "",
                        from),
                PimdirSummary.stampOf(submission.sortKey()),
                // NOTE: read, because the sender wrote it. The copy the
                // submission files carries \\Seen for the same reason.
                Flags.of(Flags.Marker.SEEN),
                new StoredMessage.Queued(
                        action.id(),
                        action.objectHash(),
                        !action.pending(),
                        action.error() != null && action.error().startsWith(UNCERTAIN)));
    }

    /**
     * Moves an outbox written as items onto the queue, once.
     *
     * <p>Older versions kept outgoing mail as items nothing syncs, which the
     * first roster replace would cascade away: they are enqueued and dropped. A
     * store with none pays one indexed read.
     */
    void migrateOutbox(String accountEmail) {
        String collection = outboxOf(accountEmail);

        List<Stranded> stranded = new ArrayList<>();
        try (Cursor cursor =
                PimdirDb.query(
                        items.readable(),
                        "LIST_LIVE_MAIL",
                        PimdirSql.values("collection", collection))) {
            while (cursor.moveToNext()) {
                stranded.add(
                        new Stranded(
                                cursor.getString(0),
                                cursor.getString(1),
                                cursor.isNull(2) ? "" : cursor.getString(2)));
            }
        }
        if (stranded.isEmpty()) {
            return;
        }

        for (Stranded message : stranded) {
            byte[] source = items.objectBytes(collection, message.linkId);
            if (source == null) {
                Log.w("pimalaya", "an outbox message had no body, dropped: " + message.linkId);
                continue;
            }
            queueSubmission(accountEmail, source);
        }

        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            for (Stranded message : stranded) {
                items.remove(db, collection, message.linkId);
            }
            items.collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        items.unlinkCollected();
    }

    /** One message the version before this one left in an outbox of items. */
    private static final class Stranded {
        final String linkId;
        final String sortKey;
        final String subject;

        Stranded(String linkId, String sortKey, String subject) {
            this.linkId = linkId;
            this.sortKey = sortKey;
            this.subject = subject;
        }
    }

    /** One message waiting to be sent: what the drain hands over. */
    public static final class Outgoing {
        /** The queue row, which is what acknowledging it addresses. */
        public final long id;

        /** The {@code Message-ID} it was stamped with, bare. */
        public final String messageId;

        /** The message itself, as handed over. */
        public final byte[] source;

        /** The {@code submit} payload, whose envelope it is sent with. */
        public final String payload;

        Outgoing(long id, String messageId, byte[] source, String payload) {
            this.id = id;
            this.messageId = messageId;
            this.source = source;
            this.payload = payload;
        }
    }

    /**
     * The messages waiting in one account's outbox, oldest first.
     *
     * <p>Append order, which is the order the queue owes a drain: the
     * sender wrote them in it, and a server may well hold two messages of
     * one conversation apart by nothing else.
     */
    public List<Outgoing> outgoing(String accountEmail) {
        migrateOutbox(accountEmail);

        // NOTE: the account's rows wherever anchored: its sent mailbox, or the
        // outbox an account marking none, or a version before this one,
        // anchored them on.
        List<Outgoing> waiting = new ArrayList<>();
        for (PimdirQueue.Action action : queue.pending()) {
            if (!anchoredIn(accountEmail, action)) {
                continue;
            }
            if (handingSet().contains(String.valueOf(action.id()))) {
                parkUncertain(action.id(), "the app stopped while sending it");
                continue;
            }
            if (!PimdirQueue.SUBMIT.equals(action.kind())) {
                // NOTE: skipped, not parked, and not the end of the drain
                // either: an action of a kind this app does not carry out
                // is left exactly as it is for whatever does (STORAGE
                // section 15.2).
                continue;
            }
            // NOTE: read strictly by its schema before anything is sent: a
            // malformed row is parked whole, never handed over in part, and
            // one stating a later payload version is skipped, left pending
            // and untouched for a later owner (STORAGE sections 15.2, 15.3).
            JSONObject read;
            try {
                read = PimdirSql.action(action.kind(), action.payload(), action.objectHash());
            } catch (PimalayaException malformed) {
                Log.w("pimalaya", "malformed submission parked: " + malformed.getMessage());
                queue.park(action.id(), malformed.getMessage());
                continue;
            }
            if (read.has("skipped")) {
                continue;
            }
            byte[] source = queue.body(action.objectHash());
            if (source != null) {
                waiting.add(
                        new Outgoing(
                                action.id(),
                                submission(action).messageId(),
                                source,
                                action.payload()));
            }
        }
        return waiting;
    }

    /**
     * Acknowledges one submission, which is what finishes it.
     *
     * <p>Cancelled rather than applied: it happened at a server, not in the
     * store (STORAGE section 15.5). It releases the body's pin with it.
     */
    public void acknowledge(long queued) {
        queue.acknowledge(queued);
        handed(queued);
    }

    /** Records why a submission will not be tried again. */
    public void parkOutgoing(long queued, String reason) {
        queue.park(queued, reason);
        handed(queued);
    }

    /**
     * Parks a submission that may have gone: handed over, no verdict back.
     *
     * <p>Never sent again on its own, which could deliver it twice, only once
     * the user asks ({@link #resendOutgoing}).
     */
    public void parkUncertain(long queued, String reason) {
        queue.park(queued, UNCERTAIN + reason);
        handed(queued);
    }

    /**
     * Queues a parked submission again at the end, dropping the parked row.
     *
     * <p>The user checked it did not go. The new row is appended first, so its
     * body stays pinned throughout.
     */
    void resendOutgoing(long queued) {
        for (PimdirQueue.Action action : queue.parked()) {
            if (action.id() != queued || !PimdirQueue.SUBMIT.equals(action.kind())) {
                continue;
            }
            byte[] source = queue.body(action.objectHash());
            if (source == null) {
                throw new IllegalStateException("The message to send again is gone");
            }
            queue.enqueue(
                    new PimdirQueue.Enqueued(
                            action.collection(),
                            submission(action).from(),
                            PimdirQueue.SUBMIT,
                            action.payload(),
                            source));
            queue.acknowledge(action.id());
            return;
        }
    }

    /** Notes {@code queued} as handed over until its verdict is recorded. */
    public void handing(long queued) {
        synchronized (HANDING) {
            java.util.Set<String> handing = new java.util.HashSet<>(handingSet());
            handing.add(String.valueOf(queued));
            prefs().edit().putStringSet(HANDING, handing).commit();
        }
    }

    private void handed(long queued) {
        synchronized (HANDING) {
            java.util.Set<String> handing = new java.util.HashSet<>(handingSet());
            if (handing.remove(String.valueOf(queued))) {
                prefs().edit().putStringSet(HANDING, handing).commit();
            }
        }
    }

    private java.util.Set<String> handingSet() {
        return prefs().getStringSet(HANDING, java.util.Set.of());
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences("outbox", Context.MODE_PRIVATE);
    }

    /** Counts one failed attempt, leaving the message where it is. */
    public void retryOutgoing(long queued) {
        queue.bumpAttempts(queued);
        handed(queued);
    }

    /**
     * The outbox's name in the list and the filter, though no server holds it.
     */
    String outboxName() {
        return context.getString(R.string.mail_outbox);
    }
}
