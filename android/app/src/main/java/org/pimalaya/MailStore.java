package org.pimalaya;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

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
 * the reads a list and a reader do, and the outbox, which is a collection of
 * this app's own that no server ever enumerates.
 */
final class MailStore {
    /**
     * The mailbox name the outbox is keyed under.
     *
     * <p>A control character, which no server hands out and no user types, so
     * the collection cannot collide with a mailbox the account really holds.
     * What is shown beside it is the collection's name, which is a word.
     */
    private static final String OUTBOX = "\u0001outbox";

    /** Where each account's trash mailbox is remembered, by address. */
    private static final String TRASH_PREFS = "mail-trash";

    private final PimdirItems items;
    private final PimdirCollections collections;
    private final PimdirAccount accounts;
    private final Context context;

    MailStore(Context context, PimdirDb store) {
        this.items = new PimdirItems(store);
        this.collections = new PimdirCollections(store, context);
        this.accounts = new PimdirAccount(context);
        this.context = context;
    }

    /**
     * Replaces an account's mailbox roster with what the walk just listed,
     * keeping the outbox and remembering where the trash is.
     *
     * <p>The outbox is kept explicitly, because a roster replace drops every
     * collection of the kind the account no longer lists and the outbox is by
     * definition one no server lists. Dropping it would take the messages
     * waiting in it with it, which is the one thing in the whole store nothing
     * could re-fetch.
     */
    void replaceMailboxes(String accountEmail, List<Mailbox> mailboxes) {
        String account = accounts.idOf(accountEmail);

        List<PimdirCollections.Stored> listed =
                MailEngine.collectionsOf(accountEmail, account, mailboxes);
        listed.add(
                new PimdirCollections.Stored(
                        outboxOf(accountEmail),
                        accountEmail,
                        context.getString(R.string.mail_outbox),
                        null,
                        null));

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

    /** Whether a collection id names an outbox rather than a mailbox. */
    static boolean isOutbox(String collection) {
        return collection.endsWith(PimdirAccount.SEPARATOR + OUTBOX);
    }

    /**
     * Creates the outbox of an account that has never synced, so a message
     * can be written before the first walk has listed anything.
     */
    void ensureOutbox(String accountEmail) {
        collections.ensure(
                outboxOf(accountEmail),
                accountEmail,
                PimdirSummary.MAIL,
                context.getString(R.string.mail_outbox));
    }

    /** One stored envelope, with the account it came from. */
    static final class StoredMessage {
        final String accountEmail;

        /**
         * The collection holding it, which every store read addresses it by.
         *
         * <p>Carried rather than derived from the mailbox name beside it: the
         * outbox is a collection whose name is a word and whose id is not, so
         * deriving one from the other is right for every mailbox and wrong for
         * the one that matters most.
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
         * Whether the message is waiting in the outbox, which is what a row
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
                boolean pending) {
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
            this.pending = pending;
        }

        /** The sender as a row shows them: the name, else the address. */
        String sender() {
            return fromName.isEmpty() ? fromAddress : fromName;
        }
    }

    /** Every account's messages, newest first: the merged list itself. */
    List<StoredMessage> loadMerged(int limit) {
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
                                isOutbox(collection)));
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
     * and a message gains its body the first time someone opens it.
     */
    byte[] source(String collection, String id) {
        return items.objectBytes(collection, id);
    }

    /** Files the message a reader just fetched, against its envelope. */
    void saveSource(String collection, String id, byte[] source) {
        items.putObject(collection, id, source);
    }

    /** One message waiting to be sent: what the drain hands over. */
    static final class Outgoing {
        final String id;
        final byte[] source;

        Outgoing(String id, byte[] source) {
            this.id = id;
            this.source = source;
        }
    }

    /** The messages waiting in one account's outbox, oldest first. */
    List<Outgoing> outgoing(String accountEmail) {
        String collection = outboxOf(accountEmail);

        List<String> ids = new ArrayList<>();
        try (Cursor cursor =
                items.readable()
                        .rawQuery(
                                "SELECT link_id FROM items WHERE collection = ?"
                                        + " AND deleted = 0 AND retained_at IS NULL"
                                        + " ORDER BY sort_key ASC",
                                new String[] {collection})) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(0));
            }
        }

        List<Outgoing> waiting = new ArrayList<>(ids.size());
        for (String id : ids) {
            byte[] source = items.objectBytes(collection, id);
            if (source != null) {
                waiting.add(new Outgoing(id, source));
            }
        }
        return waiting;
    }

    /**
     * Drops one outgoing message once it has been handed over.
     *
     * <p>Outright rather than as a tombstone: the outbox has no remote, so
     * there is nobody a removal would ever be pushed to, and a tombstone
     * would sit there being nothing forever.
     */
    void dropOutgoing(String accountEmail, String id) {
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            items.remove(db, outboxOf(accountEmail), id);
            items.collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
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
