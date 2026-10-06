package org.pimalaya;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.pimalaya.client.Mailbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mail side of the pimdir store: mailboxes as collections of kind
 * {@code message/rfc822}, envelopes as items.
 *
 * <p>A sync stores spines: a message arrives as an item with a summary and no
 * object, which is exactly what pimdir's detail ladder calls the {@code meta}
 * level. Opening one hydrates it, the message filed as that same row's object
 * so the row reaches {@code full} and the next open reads it from here. The
 * ladder is the whole bookkeeping: no second table, and nothing to invalidate,
 * a message being immutable once sent.
 *
 * <p>A mailbox id is namespaced by the account ({@link PimdirAccount}), because
 * two accounts both having an INBOX is the normal case and their messages must
 * not collide. A calendar or an address book uses its URL instead, which is
 * already unique; a mailbox name is not.
 *
 * <p>The merged list is one descending scan of {@code sort_key} across every
 * mail collection. That is the column's whole purpose: the ordering is written
 * once, at sync time, so a listing never parses a date.
 *
 * <p>The items themselves are written by {@link MailEngine} and never here: a
 * mailbox is reconciled rather than replaced, which is what lets a staged
 * marker or a staged delete survive a refresh. What is left here is the roster,
 * the reads a list and a reader do, and the outbox, which is not a mailbox at
 * all but this account's pending submissions on the store's action queue
 * ({@link PimdirQueue}).
 */
final class MailStore {
    /**
     * The collection the outbox's queue rows are filed against.
     *
     * <p>A control character, which no server hands out and no user types, so
     * it cannot collide with a mailbox the account really holds. It holds no
     * items: an action addresses a collection, and this is the one a submission
     * addresses.
     */
    private static final String OUTBOX = "\u0001outbox";

    /** Where each account's trash mailbox is remembered, by address. */
    private static final String TRASH_PREFS = "mail-trash";

    private final PimdirItems items;
    private final PimdirCollections collections;
    private final PimdirAccount accounts;
    private final PimdirQueue queue;
    private final Context context;

    MailStore(Context context, PimdirDb store) {
        this.items = new PimdirItems(store);
        this.collections = new PimdirCollections(store, context);
        this.accounts = new PimdirAccount(context);
        this.queue = new PimdirQueue(store, accounts);
        this.context = context;
    }

    /**
     * Replaces an account's mailbox roster with what the walk just listed,
     * and remembers where the trash is.
     *
     * <p>Nothing has to be held back from the replace any more. A roster
     * replace drops every collection of the kind the account no longer lists,
     * and the outbox is a queue collection of no kind, so it is not one of
     * them: the messages waiting in it are rows the replace never looks at.
     */
    void replaceMailboxes(String accountEmail, List<Mailbox> mailboxes) {
        String account = accounts.idOf(accountEmail);

        // NOTE: before the replace, which is the moment an outbox written by
        // the version before this one would be dropped: it was a mail
        // collection, so a roster that does not list it takes it and the
        // messages in it.
        migrateOutbox(accountEmail);

        List<PimdirCollections.Stored> listed =
                MailEngine.collectionsOf(accountEmail, account, mailboxes);

        collections.replace(accountEmail, PimdirSummary.MAIL, listed);

        String trash = "";
        for (Mailbox mailbox : mailboxes) {
            if (Mailbox.TRASH.equals(mailbox.role)) {
                trash = mailbox.name;
                break;
            }
        }
        context.getSharedPreferences(TRASH_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(accountEmail, trash)
                .apply();
    }

    /**
     * The mailbox one account deletes into, empty when it marks none.
     *
     * <p>Kept beside the roster rather than asked for at the moment of a
     * delete, which is the whole difference between a delete that works with
     * no network and one that does not: what it decides is whether the message
     * moves, and leaves the list, or is only marked and stays in it.
     */
    String trashOf(String accountEmail) {
        return context.getSharedPreferences(TRASH_PREFS, Context.MODE_PRIVATE)
                .getString(accountEmail, "");
    }

    /** Where one account's mailbox is stored. */
    String collectionOf(String accountEmail, String mailbox) {
        return PimdirAccount.collectionId(accounts.idOf(accountEmail), mailbox);
    }

    /** Where one account's outgoing messages wait. */
    String outboxOf(String accountEmail) {
        return PimdirAccount.collectionId(accounts.idOf(accountEmail), OUTBOX);
    }

    /**
     * Stages one composed message for the next drain to hand over.
     *
     * <p>The sort key rather than the date it was written from: it is what
     * a row is ordered and dated by everywhere else in this store, and
     * deriving it once here keeps the listing from parsing anything.
     */
    void queueSubmission(String accountEmail, String messageId, String subject, String sortKey,
            byte[] source) {
        queue.enqueue(
                outboxOf(accountEmail),
                accountEmail,
                PimdirQueue.SUBMIT,
                PimdirQueue.submission(accountEmail, messageId, subject, sortKey),
                source);
    }

    /** One stored envelope, with the account it came from. */
    static final class StoredMessage {
        final String accountEmail;

        /**
         * The collection it belongs to, which every store read addresses
         * it by.
         *
         * <p>Carried rather than derived from the mailbox name beside it,
         * which a message waiting to go out does not have: its collection
         * is the one its queue row is filed against, and no mailbox name
         * would lead back to it.
         */
        final String collection;

        final String mailbox;
        final String id;
        final String subject;

        /** The sender's display name, empty when they sent none. */
        final String fromName;

        /** The sender's address, what the row's avatar is derived from. */
        final String fromAddress;

        final long stamp;
        final boolean seen;
        final boolean answered;
        final boolean flagged;
        final boolean hasAttachment;

        /**
         * The queue row this message is, or 0 when it is a message the
         * store synced rather than one waiting to go out.
         *
         * <p>A waiting message is not an item: it is an action on the
         * store's queue carrying the bytes to hand over, so this is the
         * only handle there is to it, and every read and discard of one
         * addresses it by this rather than by a collection and a link id.
         */
        final long queued;

        /**
         * The body that row pins, or null when the message is an item.
         *
         * <p>Carried on the row because it is the only address a queued
         * message's bytes have: there is no item to look them up from,
         * and the read that wants them happens long after the listing.
         */
        final String objectHash;

        /**
         * Whether the server refused it, which parked its row.
         *
         * <p>Still shown, and still holding its message: a parked action
         * is the one thing a drain leaves behind for somebody to look at,
         * and hiding it would be losing the message quietly.
         */
        final boolean failed;

        /**
         * Whether the message is waiting to go out, which is what a row
         * says of itself rather than something a caller works out: it is the
         * one state where the store holds a message no server has.
         */
        final boolean pending;

        StoredMessage(
                String accountEmail,
                String collection,
                String mailbox,
                String id,
                String subject,
                String fromName,
                String fromAddress,
                long stamp,
                boolean seen,
                boolean answered,
                boolean flagged,
                boolean hasAttachment,
                long queued,
                String objectHash,
                boolean failed) {
            this.accountEmail = accountEmail;
            this.collection = collection;
            this.mailbox = mailbox;
            this.id = id;
            this.subject = subject;
            this.fromName = fromName;
            this.fromAddress = fromAddress;
            this.stamp = stamp;
            this.seen = seen;
            this.answered = answered;
            this.flagged = flagged;
            this.hasAttachment = hasAttachment;
            this.queued = queued;
            this.objectHash = objectHash;
            this.failed = failed;
            this.pending = queued != 0;
        }

        /** The sender as a row shows them: the name, else the address. */
        String sender() {
            return fromName.isEmpty() ? fromAddress : fromName;
        }
    }

    /**
     * Every account's messages, newest first: the merged list itself.
     *
     * <p>Two reads, because a message waiting to go out is not an item: the
     * descending scan of the sort key answers for everything the store synced,
     * and the queue answers for what has not left yet (STORAGE section 15.4,
     * a reader overlaying the pending actions on what it shows). They go on
     * top rather than in date order, which is where a sender looks for a
     * message they have just written and where a refusal has to be seen.
     */
    List<StoredMessage> loadMerged(int limit) {
        List<StoredMessage> messages = new ArrayList<>(outgoing());
        messages.addAll(synced(limit));
        return messages;
    }

    /**
     * Everything waiting on the queue, across every account, newest first.
     *
     * <p>Parked rows among them: a refused message is still a message the
     * sender wrote, and the row it shows says so rather than disappearing.
     */
    private List<StoredMessage> outgoing() {
        List<PimdirQueue.Action> actions = new ArrayList<>(queue.pending());
        actions.addAll(queue.parked());

        List<StoredMessage> messages = new ArrayList<>();
        for (PimdirQueue.Action action : actions) {
            if (PimdirQueue.SUBMIT.equals(action.kind)) {
                messages.add(outgoing(action));
            }
        }
        java.util.Collections.sort(messages, (left, right) -> Long.compare(right.queued, left.queued));
        return messages;
    }

    /** One queued submission as a row, read off the payload it was written with. */
    private static StoredMessage outgoing(PimdirQueue.Action action) {
        String from = action.payload.optString("from");

        return new StoredMessage(
                from,
                action.collection,
                "",
                action.payload.optString("messageId"),
                action.payload.optString("subject"),
                "",
                from,
                PimdirSummary.stampOf(action.payload.optString("sortKey")),
                // NOTE: read, because the sender wrote it. The copy the
                // submission files carries \\Seen for the same reason.
                true,
                false,
                false,
                false,
                action.id,
                action.objectHash,
                !action.pending());
    }

    /**
     * Moves an outbox written as items onto the queue, once.
     *
     * <p>The version before this one held outgoing messages as items in a
     * mail collection of their own. Nothing syncs those rows and nothing
     * drains them any more, and the first roster replace after the upgrade
     * would delete the collection and cascade them away, so they are
     * enqueued as the submissions they always were and the rows are
     * dropped. A store with none of them pays one indexed read.
     */
    private void migrateOutbox(String accountEmail) {
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
                    accountEmail, message.linkId, message.subject, message.sortKey, source);
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

    /** Every synced message, newest first. */
    private List<StoredMessage> synced(int limit) {
        Map<String, PimdirCollections.Stored> mailboxes = new LinkedHashMap<>();
        for (PimdirCollections.Stored stored : collections.list(PimdirSummary.MAIL)) {
            mailboxes.put(stored.id, stored);
        }

        List<StoredMessage> messages = new ArrayList<>();
        try (Cursor cursor =
                items.readable()
                        .rawQuery(
                                "SELECT i.collection, i.link_id, i.flags, i.sort_key,"
                                        + " s.subject, s.sender_name, s.sender, s.attachment"
                                        + " FROM items i"
                                        + " JOIN collections c ON c.id = i.collection"
                                        + " LEFT JOIN mail_summary s ON s.collection = i.collection"
                                        + " AND s.link_id = i.link_id"
                                        + " WHERE c.kind = ? AND i.deleted = 0"
                                        + " AND i.retained_at IS NULL"
                                        + " ORDER BY i.sort_key DESC LIMIT ?",
                                new String[] {PimdirSummary.MAIL, String.valueOf(limit)})) {
            while (cursor.moveToNext()) {
                String collection = cursor.getString(0);
                PimdirCollections.Stored mailbox = mailboxes.get(collection);
                if (mailbox == null) {
                    continue;
                }
                String flags = cursor.isNull(2) ? null : cursor.getString(2);
                messages.add(
                        new StoredMessage(
                                mailbox.accountEmail,
                                collection,
                                mailbox.name,
                                cursor.getString(1),
                                cursor.isNull(4) ? "" : cursor.getString(4),
                                cursor.isNull(5) ? "" : cursor.getString(5),
                                cursor.isNull(6) ? "" : cursor.getString(6),
                                // NOTE: from the key rather than the summary's
                                // date, since the key is what the row was
                                // ordered by: a label disagreeing with the
                                // order it appears in reads as a bug.
                                PimdirSummary.stampOf(cursor.getString(3)),
                                has(flags, MailEngine.SEEN),
                                has(flags, MailEngine.ANSWERED),
                                has(flags, MailEngine.FLAGGED),
                                !cursor.isNull(7) && cursor.getInt(7) == 1,
                                0,
                                null,
                                false));
            }
        }
        return messages;
    }

    /**
     * The markers one stored envelope carries, as the set a staged change
     * is derived from.
     *
     * <p>Read back rather than reasoned about from the row a list handed
     * over: the row carries the three the app renders, and the set carries
     * whatever else the server marked. A toggle has to leave those alone.
     */
    JSONArray flagsOf(String collection, String id) {
        try (Cursor cursor =
                items.readable()
                        .rawQuery(
                                "SELECT flags FROM items WHERE collection = ? AND link_id = ?",
                                new String[] {collection, id})) {
            if (!cursor.moveToFirst() || cursor.isNull(0)) {
                return new JSONArray();
            }
            try {
                return new JSONArray(cursor.getString(0));
            } catch (JSONException error) {
                // An unreadable marker set says nothing, so there is nothing
                // in it to keep: the staged set replaces it whole.
                return new JSONArray();
            }
        }
    }

    /**
     * The message itself, as the server sent it, or null when the store
     * holds its envelope and not the message.
     *
     * <p>Which is the ordinary state of a mailbox: a sync stores the spine,
     * and a message gains its body the first time someone opens it. A
     * message that has not gone out yet is the other way round: it is
     * nothing <em>but</em> a body, the one its queue row carries, so that
     * is where it is read from.
     */
    byte[] source(StoredMessage message) {
        if (message.pending) {
            return queue.body(message.objectHash);
        }
        return items.objectBytes(message.collection, message.id);
    }

    /** Files the message a reader just fetched, against its envelope. */
    void saveSource(String collection, String id, byte[] source) {
        items.putObject(collection, id, source);
    }

    /** One message waiting to be sent: what the drain hands over. */
    static final class Outgoing {
        /** The queue row, which is what acknowledging it addresses. */
        final long id;

        final byte[] source;

        Outgoing(long id, byte[] source) {
            this.id = id;
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
    List<Outgoing> outgoing(String accountEmail) {
        String collection = outboxOf(accountEmail);
        migrateOutbox(accountEmail);

        List<Outgoing> waiting = new ArrayList<>();
        for (PimdirQueue.Action action : queue.pending(collection)) {
            if (!PimdirQueue.SUBMIT.equals(action.kind)) {
                // NOTE: skipped, not parked, and not the end of the drain
                // either: an action of a kind this app does not carry out
                // is left exactly as it is for whatever does (STORAGE
                // section 15.2).
                continue;
            }
            byte[] source = queue.body(action.objectHash);
            if (source != null) {
                waiting.add(new Outgoing(action.id, source));
            }
        }
        return waiting;
    }

    /**
     * Acknowledges one submission, which is what finishes it.
     *
     * <p>Cancelling rather than applying: what the action asked for
     * happened at a server and not in the store, so there is nothing here
     * to apply and the row is removed by the process that carried it out
     * (STORAGE section 15.5). It releases the body's pin with it.
     */
    void acknowledge(long queued) {
        queue.acknowledge(queued);
    }

    /** Records why a submission will not be tried again. */
    void parkOutgoing(long queued, String reason) {
        queue.park(queued, reason);
    }

    /** Counts one failed attempt, leaving the message where it is. */
    void retryOutgoing(long queued) {
        queue.bumpAttempts(queued);
    }

    /** The distinct mailbox names seen, for the filter's collection axis. */
    List<String> loadMailboxes() {
        List<String> mailboxes = new ArrayList<>();
        for (PimdirCollections.Stored stored : collections.list(PimdirSummary.MAIL)) {
            if (!mailboxes.contains(stored.name)) {
                mailboxes.add(stored.name);
            }
        }
        return mailboxes;
    }

    /** Whether the stored flag set carries one flag. */
    private static boolean has(String flags, String flag) {
        if (flags == null || flags.isEmpty()) {
            return false;
        }
        try {
            return MailEngine.has(new JSONArray(flags), flag);
        } catch (JSONException error) {
            // An unreadable flag set is an unknown one, and unknown reads as
            // unset: an unread message shows rather than hides, and no icon
            // claims a state the store cannot back up.
            return false;
        }
    }
}
