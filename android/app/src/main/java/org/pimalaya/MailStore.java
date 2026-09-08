package org.pimalaya;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.pimalaya.client.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mail side of the pimdir store: mailboxes as collections of kind
 * {@code message/rfc822}, envelopes as items.
 *
 * <p>Spines only, so nothing here stores a body: a message is an item with a
 * summary and no object, which is exactly what pimdir's detail ladder calls the
 * {@code meta} level. Hydrating one later is a body write on the same row
 * rather than a second table.
 *
 * <p>A mailbox id is namespaced by the account ({@link PimdirAccount}), because
 * two accounts both having an INBOX is the normal case and their messages must
 * not collide. A calendar or an address book uses its URL instead, which is
 * already unique; a mailbox name is not.
 *
 * <p>The merged list is one descending scan of {@code sort_key} across every
 * mail collection. That is the column's whole purpose: the ordering is written
 * once, at sync time, so a listing never parses a date.
 */
final class MailStore {
    /** The IMAP {@code \Seen} flag, as the store's JSON array spells it. */
    static final String SEEN = "\\Seen";

    /** The IMAP {@code \Answered} flag: the message was replied to. */
    static final String ANSWERED = "\\Answered";

    /** The IMAP {@code \Flagged} flag: the message was marked important. */
    static final String FLAGGED = "\\Flagged";

    /**
     * The IMAP {@code \Deleted} flag: the message is marked for removal by a
     * later expunge, which is what deleting means on a server naming no trash
     * to move it into.
     */
    static final String DELETED = "\\Deleted";

    private final PimdirItems items;
    private final PimdirCollections collections;
    private final PimdirAccount accounts;

    MailStore(Context context, PimdirDb store) {
        this.items = new PimdirItems(store);
        this.collections = new PimdirCollections(store, context);
        this.accounts = new PimdirAccount(context);
    }

    /**
     * Replaces an account's messages with what the walk just returned,
     * mailbox by mailbox.
     *
     * <p>The roster comes from the messages themselves, since the walk lists
     * the mailboxes it visited by visiting them: a mailbox that returned
     * nothing this round is one the account no longer has, or one that is
     * empty, and both are the same to a spine mirror.
     */
    void replaceMessages(String accountEmail, List<Message> messages) {
        String account = accounts.idOf(accountEmail);

        Map<String, List<PimdirItems.Row>> byMailbox = new LinkedHashMap<>();
        for (Message message : messages) {
            byMailbox
                    .computeIfAbsent(message.mailbox, mailbox -> new ArrayList<>())
                    .add(rowOf(message));
        }

        List<PimdirCollections.Stored> listed = new ArrayList<>(byMailbox.size());
        for (String mailbox : byMailbox.keySet()) {
            listed.add(
                    new PimdirCollections.Stored(
                            PimdirAccount.collectionId(account, mailbox),
                            accountEmail,
                            mailbox,
                            null,
                            null));
        }
        collections.replace(accountEmail, PimdirSummary.MAIL, listed);

        for (Map.Entry<String, List<PimdirItems.Row>> entry : byMailbox.entrySet()) {
            items.replace(
                    PimdirAccount.collectionId(account, entry.getKey()), entry.getValue());
        }
    }

    /**
     * One envelope as an item: no body, the summary an inbox row renders, and
     * the date as the key it is ordered by.
     *
     * <p>The message id is the link id. It is unique within its mailbox, which
     * is what a link id has to be, and it is what the server addresses the
     * message by, so a later fetch needs nothing else to name it. It crosses
     * as text: an IMAP UID is a number, a JMAP {@code Email} id is not, and
     * the store keys items by string anyway.
     */
    private static PimdirItems.Row rowOf(Message message) {
        JSONArray flags = new JSONArray();
        if (message.seen) {
            flags.put(SEEN);
        }
        if (message.answered) {
            flags.put(ANSWERED);
        }
        if (message.flagged) {
            flags.put(FLAGGED);
        }
        return new PimdirItems.Row(
                message.id,
                null,
                PimdirSummary.mail(
                        null,
                        message.subject,
                        message.from,
                        message.fromAddress,
                        null,
                        message.date,
                        0,
                        message.hasAttachment),
                PimdirSummary.mailSortKey(message.date),
                flags.toString(),
                null);
    }

    /** One stored envelope, with the account it came from. */
    static final class StoredMessage {
        final String accountEmail;
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

        StoredMessage(
                String accountEmail,
                String mailbox,
                String id,
                String subject,
                String fromName,
                String fromAddress,
                long stamp,
                boolean seen,
                boolean answered,
                boolean flagged,
                boolean hasAttachment) {
            this.accountEmail = accountEmail;
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
                PimdirCollections.Stored mailbox = mailboxes.get(cursor.getString(0));
                if (mailbox == null) {
                    continue;
                }
                String flags = cursor.isNull(2) ? null : cursor.getString(2);
                messages.add(
                        new StoredMessage(
                                mailbox.accountEmail,
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
                                has(flags, SEEN),
                                has(flags, ANSWERED),
                                has(flags, FLAGGED),
                                !cursor.isNull(7) && cursor.getInt(7) == 1));
            }
        }
        return messages;
    }

    /**
     * Adds or removes one marker on one stored envelope, so the list
     * reflects a write the server has just accepted without waiting for the
     * next sync.
     *
     * <p>Called after the server, never instead of it: the mirror holds what
     * the server holds, and a marker written here that the server refused
     * would be a lie the next sync silently corrects.
     */
    void setFlag(String accountEmail, String mailbox, String id, String flag, boolean add) {
        String collection =
                PimdirAccount.collectionId(accounts.idOf(accountEmail), mailbox);

        SQLiteDatabase db = items.writable();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT flags FROM items WHERE collection = ? AND link_id = ?",
                        new String[] {collection, id})) {
            if (!cursor.moveToFirst()) {
                return;
            }

            JSONArray updated = withFlag(cursor.isNull(0) ? null : cursor.getString(0), flag, add);
            db.execSQL(
                    "UPDATE items SET flags = ? WHERE collection = ? AND link_id = ?",
                    new Object[] {updated.toString(), collection, id});
        }
    }

    /** Retires one stored envelope, for a message the server no longer files here. */
    void removeMessage(String accountEmail, String mailbox, String id) {
        String collection =
                PimdirAccount.collectionId(accounts.idOf(accountEmail), mailbox);

        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            items.remove(db, collection, id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** The stored marker set with one marker added or removed. */
    private static JSONArray withFlag(String flags, String flag, boolean add) {
        JSONArray kept = new JSONArray();
        try {
            JSONArray parsed = flags == null || flags.isEmpty()
                    ? new JSONArray()
                    : new JSONArray(flags);
            for (int index = 0; index < parsed.length(); index++) {
                if (!flag.equals(parsed.optString(index))) {
                    kept.put(parsed.optString(index));
                }
            }
        } catch (JSONException error) {
            // An unreadable marker set is rewritten rather than patched: it
            // says nothing, so there is nothing in it to keep.
        }
        if (add) {
            kept.put(flag);
        }
        return kept;
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
            JSONArray parsed = new JSONArray(flags);
            for (int index = 0; index < parsed.length(); index++) {
                if (flag.equals(parsed.optString(index))) {
                    return true;
                }
            }
        } catch (JSONException error) {
            // An unreadable flag set is an unknown one, and unknown reads as
            // unset: an unread message shows rather than hides, and no icon
            // claims a state the store cannot back up.
        }
        return false;
    }
}
