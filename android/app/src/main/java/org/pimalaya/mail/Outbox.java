package org.pimalaya.mail;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.pimalaya.R;
import org.pimalaya.client.Flags;
import org.pimalaya.mail.MailStore.StoredMessage;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirItems;
import org.pimalaya.pimdir.PimdirQueue;
import org.pimalaya.pimdir.PimdirSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * The outbox: each account's pending submissions on the store's queue.
 *
 * <p>Not a mailbox ({@link PimdirQueue}): what is queued, what the drain hands
 * over, and what was parked.
 */
public final class Outbox {
    /**
     * The collection the outbox's queue rows are filed against.
     *
     * <p>A control character no server hands out and no user types, so it never
     * collides with a real mailbox. It holds no items.
     */
    private static final String OUTBOX = "\u0001outbox";

    /** A parked reason's prefix when the message may have gone, not refused. */
    static final String UNCERTAIN = "uncertain: ";


    private final Context context;
    private final PimdirItems items;
    private final PimdirAccount accounts;
    private final PimdirQueue queue;

    Outbox(Context context, PimdirItems items, PimdirAccount accounts, PimdirQueue queue) {
        this.context = context;
        this.items = items;
        this.accounts = accounts;
        this.queue = queue;
    }

    /**
     * Whether a submission of the message is still waiting or parked.
     *
     * <p>The sent copy staged for it is then not to be filed yet.
     */
    boolean submitting(String messageId) {
        for (PimdirQueue.Action action : submissions()) {
            if (messageId.equals(Submission.from(action).messageId())) {
                return true;
            }
        }
        return false;
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

    /** Where one account's outgoing messages wait. */
    public String outboxOf(String accountEmail) {
        return PimdirAccount.collectionId(accounts.idOf(accountEmail), OUTBOX);
    }

    /**
     * Stages one composed message for the next drain to hand over.
     *
     * <p>The sort key rather than the date it was written from: it is what
     * a row is ordered and dated by everywhere else in this store, and
     * deriving it once here keeps the listing from parsing anything.
     */
    void queueSubmission(Submission submission, byte[] source) {
        queue.enqueue(
                new PimdirQueue.Enqueued(
                        outboxOf(submission.from()),
                        submission.from(),
                        PimdirQueue.SUBMIT,
                        submission.text(),
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
        Submission submission = Submission.from(action);
        String from = submission.from();

        return StoredMessage.outgoing(
                new StoredMessage.MessageHeader(
                        from,
                        action.collection(),
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
                items.readable()
                        .rawQuery(
                                "SELECT i.link_id, i.sort_key, s.subject FROM items i"
                                        + " LEFT JOIN mail_summary s ON s.collection = i.collection"
                                        + " AND s.link_id = i.link_id"
                                        + " WHERE i.collection = ? AND i.deleted = 0"
                                        + " ORDER BY i.sort_key ASC",
                                new String[] {collection})) {
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
            queueSubmission(
                    Submission.of(accountEmail, message.linkId, message.subject, message.sortKey),
                    source);
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

        Outgoing(long id, String messageId, byte[] source) {
            this.id = id;
            this.messageId = messageId;
            this.source = source;
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
        String collection = outboxOf(accountEmail);
        migrateOutbox(accountEmail);

        List<Outgoing> waiting = new ArrayList<>();
        for (PimdirQueue.Action action : queue.pending(collection)) {
            if (!PimdirQueue.SUBMIT.equals(action.kind())) {
                // NOTE: skipped, not parked, and not the end of the drain
                // either: an action of a kind this app does not carry out
                // is left exactly as it is for whatever does (STORAGE
                // section 15.2).
                continue;
            }
            byte[] source = queue.body(action.objectHash());
            if (source != null) {
                waiting.add(new Outgoing(action.id(), Submission.from(action).messageId(), source));
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
    }

    /** Records why a submission will not be tried again. */
    public void parkOutgoing(long queued, String reason) {
        queue.park(queued, reason);
    }

    /**
     * Parks a submission that may have gone: handed over, no verdict back.
     *
     * <p>Never sent again on its own, which could deliver it twice, only once
     * the user asks ({@link #resendOutgoing}).
     */
    public void parkUncertain(long queued, String reason) {
        queue.park(queued, UNCERTAIN + reason);
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
                            Submission.from(action).from(),
                            PimdirQueue.SUBMIT,
                            action.payload(),
                            source));
            queue.acknowledge(action.id());
            return;
        }
    }

    /** Counts one failed attempt, leaving the message where it is. */
    public void retryOutgoing(long queued) {
        queue.bumpAttempts(queued);
    }

    /**
     * The outbox's name in the list and the filter, though no server holds it.
     */
    String outboxName() {
        return context.getString(R.string.mail_outbox);
    }
}
