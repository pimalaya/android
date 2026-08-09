package org.pimalaya;

import android.content.Context;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
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
    private static final String SEEN = "\\Seen";

    /** The IMAP {@code \Answered} flag: the message was replied to. */
    private static final String ANSWERED = "\\Answered";

    /** The IMAP {@code \Flagged} flag: the message was marked important. */
    private static final String FLAGGED = "\\Flagged";

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
        collections.replace(accountEmail, PimdirMeta.MAIL, listed);

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
                PimdirMeta.mail(
                        null,
                        message.subject,
                        message.from,
                        message.fromAddress,
                        null,
                        message.date,
                        0,
                        message.hasAttachment),
                PimdirMeta.mailSortKey(message.date),
                flags.toString());
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
        for (PimdirCollections.Stored stored : collections.list(PimdirMeta.MAIL)) {
            mailboxes.put(stored.id, stored);
        }

        List<StoredMessage> messages = new ArrayList<>();
        try (Cursor cursor =
                items.readable()
                        .rawQuery(
                                "SELECT i.collection, i.link_id, i.meta, i.flags, i.sort_key"
                                        + " FROM items i"
                                        + " JOIN collections c ON c.id = i.collection"
                                        + " WHERE c.kind = ? AND i.deleted = 0"
                                        + " AND i.retained_at IS NULL"
                                        + " ORDER BY i.sort_key DESC LIMIT ?",
                                new String[] {PimdirMeta.MAIL, String.valueOf(limit)})) {
            while (cursor.moveToNext()) {
                PimdirCollections.Stored mailbox = mailboxes.get(cursor.getString(0));
                if (mailbox == null) {
                    continue;
                }
                JSONObject meta = metaOf(cursor.getString(2));
                String flags = cursor.isNull(3) ? null : cursor.getString(3);
                messages.add(
                        new StoredMessage(
                                mailbox.accountEmail,
                                mailbox.name,
                                cursor.getString(1),
                                meta.optString("subject"),
                                meta.optString("from_name"),
                                meta.optString("from"),
                                // NOTE: from the key rather than the summary's
                                // date, since the key is what the row was
                                // ordered by: a label disagreeing with the
                                // order it appears in reads as a bug.
                                PimdirMeta.stampOf(cursor.getString(4)),
                                has(flags, SEEN),
                                has(flags, ANSWERED),
                                has(flags, FLAGGED),
                                meta.optBoolean("attachment")));
            }
        }
        return messages;
    }

    /** The distinct mailbox names seen, for the filter's collection axis. */
    List<String> loadMailboxes() {
        List<String> mailboxes = new ArrayList<>();
        for (PimdirCollections.Stored stored : collections.list(PimdirMeta.MAIL)) {
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

    private static JSONObject metaOf(String meta) {
        if (meta == null || meta.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(meta);
        } catch (JSONException error) {
            return new JSONObject();
        }
    }
}
