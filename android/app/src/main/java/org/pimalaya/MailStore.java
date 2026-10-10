package org.pimalaya;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQuery;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimdirSql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

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
 * <p>The merged list is one descending scan of {@code sort_key} across the
 * mail collections a filter lets through, read a page at a time around the
 * scroll position and sized by a count ({@link Query}): io-pimdir's canonical
 * readers ({@code count_mail}, {@code count_mail_by_day}, {@code count_unread},
 * {@code list_mail_page_filtered}, {@code search_mail}) run here over
 * Android's SQLite, the statements crossing the bridge rather than being
 * transcribed. The ordering is written once, at sync time, so a listing never
 * parses a date.
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

    /** The mail roles pimdir's schema takes (STORAGE section 14). */
    private static final java.util.Set<String> ROLES =
            java.util.Set.of(
                    "inbox", "sent", "drafts", "trash", "junk", "archive", "all", "flagged",
                    "important");

    /** Where each account's trash mailbox is remembered, by address. */
    private static final String TRASH_PREFS = "mail-trash";

    /** Which accounts are listed account-wide, by account id. */
    private static final String ACCOUNT_WIDE_PREFS = "mail-account-wide";

    /** Which accounts' servers erase no single message (no UIDPLUS), by account id. */
    private static final String MARKING_PREFS = "mail-marks-deleted";

    /** Which sent copies a provider filed under its own Message-ID, by that id. */
    private static final String SENT_ALIAS_PREFS = "mail-sent-alias";

    private final PimdirItems items;
    private final PimdirDb store;
    private final PimdirCollections collections;
    private final PimdirAccount accounts;
    private final PimdirQueue queue;
    private final PimdirStorage storage;
    private final Context context;

    MailStore(Context context, PimdirDb store) {
        this.items = new PimdirItems(store);
        this.storage = new PimdirStorage(store);
        this.store = store;
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

        // NOTE: what the source states and nothing guessed (pimdir STORAGE
        // section 14): a mailbox that says nothing any more loses its role,
        // and a role moving to another mailbox leaves the first in the same
        // statement.
        SQLiteDatabase db = store.getWritableDatabase();
        for (Mailbox mailbox : mailboxes) {
            Map<String, Object> values = new HashMap<>();
            values.put("collection", PimdirAccount.collectionId(account, mailbox.name));
            values.put("role", ROLES.contains(mailbox.role) ? mailbox.role : null);
            PimdirSql.Bound bound = PimdirSql.bind("SET_COLLECTION_ROLE", values);
            db.execSQL(bound.sql, bound.args);
        }

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
     * Drops everything one account's mail left in the store: its mailboxes,
     * their messages, their attachments, and whatever was still waiting to
     * go out.
     */
    void forget(String accountEmail) {
        String outbox = outboxOf(accountEmail);
        List<PimdirQueue.Action> waiting = new ArrayList<>(queue.pending(outbox));
        for (PimdirQueue.Action action : queue.parked()) {
            if (action.collection.equals(outbox)) {
                waiting.add(action);
            }
        }
        for (PimdirQueue.Action action : waiting) {
            queue.acknowledge(action.id);
        }

        collections.replace(accountEmail, PimdirSummary.MAIL, List.of());
        new FileStore(context, store).forget(accounts.idOf(accountEmail));
        context.getSharedPreferences(TRASH_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(accountEmail)
                .apply();
        MailScope.forget(context, accounts.idOf(accountEmail));
        MailOffline.forget(context, accounts.idOf(accountEmail));
        MailWindow.forget(context, accounts.idOf(accountEmail));
        markAccountWide(context, accounts.idOf(accountEmail), false);
        markExpungesOne(context, accounts.idOf(accountEmail), true);
    }

    /**
     * Remembers whether an account's mailboxes are listed account-wide: one
     * listing of the whole account, each mailbox a projection of it (Gmail's
     * labels), so they share one floor and widen together.
     */
    static void markAccountWide(Context context, String accountId, boolean accountWide) {
        SharedPreferences.Editor prefs =
                context.getSharedPreferences(ACCOUNT_WIDE_PREFS, Context.MODE_PRIVATE).edit();
        if (accountWide) {
            prefs.putBoolean(accountId, true);
        } else {
            prefs.remove(accountId);
        }
        prefs.apply();
    }

    /** Whether an account's mailboxes are listed account-wide ({@link #markAccountWide}). */
    static boolean accountWide(Context context, String accountId) {
        return context.getSharedPreferences(ACCOUNT_WIDE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(accountId, false);
    }

    /**
     * Remembers whether an account's server erases one message alone, as
     * its last session said ({@code MailSession.expungesOne}).
     *
     * <p>Kept beside the trash for the same reason: what it decides is what
     * a delete in the trash stages, which a delete with no network has to
     * know.
     */
    static void markExpungesOne(Context context, String accountId, boolean expungesOne) {
        SharedPreferences.Editor prefs =
                context.getSharedPreferences(MARKING_PREFS, Context.MODE_PRIVATE).edit();
        if (expungesOne) {
            prefs.remove(accountId);
        } else {
            prefs.putBoolean(accountId, true);
        }
        prefs.apply();
    }

    /**
     * Whether a delete in one account's trash erases the message, else only
     * marks it {@code \Deleted} ({@link #markExpungesOne}). An account no
     * session recorded yet reads as erasing, which most servers do.
     */
    boolean expungesOne(String accountEmail) {
        return !context.getSharedPreferences(MARKING_PREFS, Context.MODE_PRIVATE)
                .getBoolean(accounts.idOf(accountEmail), false);
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

    /**
     * The collection one account files sent mail in, null when it marks
     * none (RFC 6154 {@code \Sent}, JMAP's {@code sent} role).
     */
    String sentOf(String accountEmail) {
        String prefix = PimdirAccount.collectionId(accounts.idOf(accountEmail), "");
        for (Map.Entry<String, String> role : roles().entrySet()) {
            if ("sent".equals(role.getValue()) && role.getKey().startsWith(prefix)) {
                return role.getKey();
            }
        }
        return null;
    }

    /**
     * Whether a submission of the message is still waiting, or was refused
     * and parked: the sent copy staged for it is not to be filed yet.
     */
    boolean submitting(String messageId) {
        for (PimdirQueue.Action action : submissions()) {
            if (messageId.equals(action.payload.optString("messageId"))) {
                return true;
            }
        }
        return false;
    }

    /** Every submission still in an outbox, waiting or parked. */
    private List<PimdirQueue.Action> submissions() {
        List<PimdirQueue.Action> actions = new ArrayList<>(queue.pending());
        actions.addAll(queue.parked());
        actions.removeIf(action -> !PimdirQueue.SUBMIT.equals(action.kind));
        return actions;
    }

    /** The body the store holds of one message, null when it holds none. */
    byte[] storedSource(String collection, String linkId) {
        return items.objectBytes(collection, linkId);
    }

    /** Whether the store holds one message's body, without reading it. */
    boolean holdsBody(StoredMessage message) {
        return items.objectOf(items.readable(), message.collection, message.id) != null;
    }

    /**
     * Whether deleting one message removes it for good: in its account's
     * trash, where the server erases one message alone (the rule
     * {@code MailEngine.stageDelete} stages by).
     */
    boolean erases(StoredMessage message) {
        String trash = trashOf(message.accountEmail);
        return !message.pending
                && !trash.isEmpty()
                && trash.equals(message.mailbox)
                && expungesOne(message.accountEmail);
    }

    /** The id one account's collections are namespaced under. */
    String accountIdOf(String accountEmail) {
        return accounts.idOf(accountEmail);
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

        /** The mailbox as the reader is shown it ({@link MailStore#mailboxLabel}). */
        final String mailboxLabel;

        final String id;
        final String subject;

        /** The sender's display name, empty when they sent none. */
        final String fromName;

        /** The sender's address, what the row's avatar is derived from. */
        final String fromAddress;

        final long stamp;

        /**
         * The row's place in the merged order, {@code (sortKey, seq)} with
         * the collection: what the next page is read after. Empty and 0 on a
         * message waiting to go out, which is no item.
         */
        final String sortKey;

        final long seq;

        final boolean seen;
        final boolean answered;
        final boolean flagged;
        final boolean hasAttachment;

        /** The attachment mark: 1 with, 0 without, null never examined. */
        final Integer attachment;

        /** Its size in bytes, null where the store does not know it. */
        final Long size;

        /** Whether the store holds its body: what a row not on the phone is dimmed by. */
        final boolean bodied;

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

        /**
         * Whether the store holds a change of it the server has not taken
         * yet: a move's target, a sent copy, a marker. Never a message
         * waiting to go out, which says so on its own.
         */
        final boolean unsynced;

        /** Whether the server refused that change for good. */
        final boolean refused;

        /**
         * Whether it carries {@code \Deleted}: deleted where the server
         * expunges no single message (no UIDPLUS), and so still listed.
         */
        final boolean deleted;

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
            this.mailboxLabel = mailbox;
            this.id = id;
            this.subject = subject;
            this.fromName = fromName;
            this.fromAddress = fromAddress;
            this.stamp = stamp;
            this.seen = seen;
            this.answered = answered;
            this.flagged = flagged;
            this.hasAttachment = hasAttachment;
            this.attachment = hasAttachment ? 1 : 0;
            this.size = null;
            this.bodied = true;
            this.queued = queued;
            this.objectHash = objectHash;
            this.failed = failed;
            this.pending = queued != 0;
            this.sortKey = "";
            this.seq = 0;
            this.unsynced = false;
            this.refused = false;
            this.deleted = false;
        }

        /** One synced message, as a page of the merged list reads it. */
        StoredMessage(
                String accountEmail,
                String collection,
                String mailbox,
                String mailboxLabel,
                String id,
                String subject,
                String fromName,
                String fromAddress,
                String sortKey,
                long seq,
                String flags,
                Integer attachment,
                Long size,
                boolean bodied,
                boolean unsynced,
                boolean refused) {
            this.accountEmail = accountEmail;
            this.collection = collection;
            this.mailbox = mailbox;
            this.mailboxLabel = mailboxLabel;
            this.id = id;
            this.subject = subject;
            this.fromName = fromName;
            this.fromAddress = fromAddress;
            this.sortKey = sortKey == null ? "" : sortKey;
            this.seq = seq;
            // NOTE: from the key rather than the summary's date, since the
            // key is what the row was ordered by: a label disagreeing with
            // the order it appears in reads as a bug.
            this.stamp = PimdirSummary.stampOf(this.sortKey);
            this.seen = has(flags, MailEngine.SEEN);
            this.answered = has(flags, MailEngine.ANSWERED);
            this.flagged = has(flags, MailEngine.FLAGGED);
            this.hasAttachment = attachment != null && attachment == 1;
            this.attachment = attachment;
            this.size = size;
            this.bodied = bodied;
            this.queued = 0;
            this.objectHash = null;
            this.failed = false;
            this.pending = false;
            this.unsynced = unsynced;
            this.refused = refused;
            this.deleted = has(flags, MailEngine.DELETED);
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
        messages.addAll(page(query((account, mailbox) -> true, false, false, ""), null, 0, limit));
        return messages;
    }

    /**
     * Everything waiting on the queue, across every account, newest first.
     *
     * <p>Parked rows among them: a refused message is still a message the
     * sender wrote, and the row it shows says so rather than disappearing.
     */
    List<StoredMessage> outgoing() {
        List<StoredMessage> messages = new ArrayList<>();
        for (PimdirQueue.Action action : submissions()) {
            messages.add(outgoing(action));
        }
        java.util.Collections.sort(messages, (left, right) -> Long.compare(right.queued, left.queued));
        return messages;
    }

    /** One queued submission as a row, read off the payload it was written with. */
    private StoredMessage outgoing(PimdirQueue.Action action) {
        String from = action.payload.optString("from");

        return new StoredMessage(
                from,
                action.collection,
                outboxName(),
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

    /**
     * The `References` a reply to one message carries, oldest first and
     * ending with that message's own `Message-ID`, each in angle brackets;
     * empty when the store holds no identifier for it.
     *
     * <p>The parent's `In-Reply-To` stands in for its own `References`,
     * which the summary does not keep: RFC 5322 section 3.6.4 allows it
     * where the parent names a single parent of its own, and a reply
     * threads under its parent either way.
     */
    List<String> threadOf(String collection, String linkId) {
        List<String> thread = new ArrayList<>();
        try (Cursor cursor =
                items.readable()
                        .rawQuery(
                                "SELECT message_id, in_reply_to FROM mail_summary"
                                        + " WHERE collection = ? AND link_id = ?",
                                new String[] {collection, linkId})) {
            if (!cursor.moveToNext() || cursor.isNull(0)) {
                return thread;
            }
            try {
                JSONArray before = new JSONArray(cursor.getString(1));
                for (int index = 0; index < before.length(); index++) {
                    thread.add("<" + before.getString(index) + ">");
                }
            } catch (JSONException error) {
                Log.w("pimalaya", "unreadable in_reply_to: " + linkId, error);
            }
            thread.add("<" + cursor.getString(0) + ">");
        }
        return thread;
    }

    // ---- the merged list's reads -----------------------------------------

    /**
     * What the merged list shows: the mail collections a filter lets
     * through, the two chips, and the words searched for in the sender and
     * the subject. Every read below answers under it, so the count that
     * sizes the list, the per-day counts that place its headers and the
     * pages it loads agree.
     */
    static final class Query {
        /** The collection ids, as the JSON array the readers bind. */
        final String collections;

        /** How many collections it covers; none answers nothing. */
        final int size;

        /** 1 read only, 0 unread only, null either (the unread chip). */
        final Integer seen;

        /** 1 with an attachment only, null either (the attachment chip). */
        final Integer attachment;

        /** The {@code LIKE} pattern searched, null when nothing is. */
        final String pattern;

        /**
         * How far down the list reaches, on the sort key (the {@code Date}),
         * null for all of it: the list's floor ({@link #floorOf}), or a
         * window. The canonical statements apply it themselves.
         */
        final String since;

        /**
         * The rows it leaves out as {@code [collection, link_id]} pairs, a
         * JSON array, null for none: the sent copies staged beside a
         * submission still in an outbox, whose outbox row stands for them.
         */
        final String hidden;

        /** How many rows {@link #hidden} names. */
        final int hiding;

        private final java.util.Set<String> held;

        Query(List<String> collections, Integer seen, Integer attachment, String pattern,
                List<String[]> hidden) {
            this.collections = new JSONArray(collections).toString();
            this.size = collections.size();
            this.seen = seen;
            this.attachment = attachment;
            this.pattern = pattern;
            this.since = null;
            this.held = new HashSet<>(collections);
            JSONArray pairs = new JSONArray();
            for (String[] pair : hidden) {
                pairs.put(new JSONArray().put(pair[0]).put(pair[1]));
            }
            this.hidden = hidden.isEmpty() ? null : pairs.toString();
            this.hiding = hidden.size();
        }

        /** The same query narrowed to unread mail, for the unread line. */
        Query unread() {
            return new Query(this, 0, since);
        }

        /** The same query reaching down to {@code since} alone, null for all of it. */
        Query since(String since) {
            return new Query(this, seen, since);
        }

        private Query(Query query, Integer seen, String since) {
            this.collections = query.collections;
            this.size = query.size;
            this.seen = seen;
            this.attachment = query.attachment;
            this.pattern = query.pattern;
            this.since = since;
            this.hidden = query.hidden;
            this.hiding = query.hiding;
            this.held = query.held;
        }

        /** Whether the query lets a collection through. */
        boolean holds(String collection) {
            return held.contains(collection);
        }

        /** The same query over the collections {@code keep} lets through alone. */
        Query only(java.util.function.Predicate<String> keep) {
            List<String> kept = new ArrayList<>();
            for (String collection : held) {
                if (keep.test(collection)) {
                    kept.add(collection);
                }
            }
            return new Query(kept, seen, attachment, pattern, List.of()).since(since);
        }

        /** The canonical statement's values, with a page's cursor and size. */
        Map<String, Object> values(StoredMessage after, long limit) {
            Map<String, Object> values = new HashMap<>();
            values.put("collections", collections);
            values.put("seen", seen);
            values.put("attachment", attachment);
            values.put("pattern", pattern);
            values.put("since", since);
            values.put("after_key", after == null ? null : after.sortKey);
            values.put("after_seq", after == null ? null : after.seq);
            values.put("after_collection", after == null ? null : after.collection);
            values.put("limit", limit);
            return values;
        }
    }

    /**
     * The query a list asks for: the mail collections {@code accepts} lets
     * through by account and collection id, the chips, and the words
     * searched for (empty for none).
     */
    Query query(
            BiPredicate<String, String> accepts,
            boolean unreadOnly,
            boolean attachmentsOnly,
            String words) {
        List<String> ids = new ArrayList<>();
        for (PimdirCollections.Stored stored : collections.list(PimdirSummary.MAIL)) {
            if (accepts.test(stored.accountEmail, stored.id)) {
                ids.add(stored.id);
            }
        }
        // NOTE: a submission's sent copy is filed under its Message-ID, the
        // key the submission names, until a listing lands it. Hidden only
        // where the outbox shows, whose row then stands for it.
        List<String[]> hidden = new ArrayList<>();
        for (PimdirQueue.Action action : submissions()) {
            String from = action.payload.optString("from");
            String sent = sentOf(from);
            if (sent != null && ids.contains(sent) && accepts.test(from, outboxOf(from))) {
                hidden.add(new String[] {sent, action.payload.optString("messageId")});
            }
        }
        String trimmed = words == null ? "" : words.trim();
        return new Query(
                ids,
                unreadOnly ? 0 : null,
                attachmentsOnly ? 1 : null,
                trimmed.isEmpty() ? null : likePattern(trimmed),
                hidden);
    }

    /**
     * The {@code LIKE} pattern {@code search_mail} matches: the words as
     * typed, {@code %} around them, a literal {@code %}, {@code _} or
     * {@code \} escaped with {@code \}, as io-pimdir's own reader builds it
     * ({@code reader::like_pattern}).
     */
    static String likePattern(String words) {
        StringBuilder pattern = new StringBuilder("%");
        for (char character : words.trim().toCharArray()) {
            if (character == '%' || character == '_' || character == '\\') {
                pattern.append('\\');
            }
            pattern.append(character);
        }
        return pattern.append('%').toString();
    }

    /**
     * How many stored messages the query lets through: what sizes the list.
     *
     * <p>{@code count_mail} where nothing is searched; a search counts the
     * rows {@code search_mail} would page through, the statement read as a
     * subquery rather than restated, there being no count of its own.
     */
    long count(Query query) {
        if (query.size == 0) {
            return 0;
        }
        PimdirSql.Bound bound = query.pattern == null && query.hidden == null
                ? PimdirSql.bind("COUNT_MAIL", query.values(null, -1))
                : around("SELECT count(*) FROM (", listed(query), ")");
        try (Cursor cursor = typed(items.readable(), bound.sql, bound.args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    /** One day of the merged list and how many messages fall on it. */
    static final class Day {
        /** The day, {@code YYYY-MM-DD} on the reader's wall clock; null for undated mail. */
        final String day;

        final long count;

        Day(String day, long count) {
            this.day = day;
            this.count = count;
        }
    }

    /**
     * How many messages of the query fall on each day, newest day first and
     * undated mail last: what places the list's day headers without loading
     * a row.
     *
     * <p>{@code shift} is the SQLite modifier moving a UTC instant to the
     * reader's wall clock ({@code '+120 minutes'}). A search is grouped over
     * {@code search_mail} read as a subquery, by the same day expression
     * {@code count_mail_by_day} groups by.
     */
    List<Day> countByDay(Query query, String shift) {
        List<Day> days = new ArrayList<>();
        if (query.size == 0) {
            return days;
        }
        PimdirSql.Bound bound;
        if (query.pattern == null && query.hidden == null) {
            Map<String, Object> values = query.values(null, -1);
            values.put("shift", shift);
            bound = PimdirSql.bind("COUNT_MAIL_BY_DAY", values);
        } else {
            PimdirSql.Bound search = listed(query);
            Object[] args = new Object[search.args.length + 1];
            args[0] = shift;
            System.arraycopy(search.args, 0, args, 1, search.args.length);
            bound = new PimdirSql.Bound(
                    "SELECT date(nullif(sort_key, ''), coalesce(?, '+0 minutes')) AS day,"
                            + " count(*) FROM (" + search.sql + ")"
                            + " GROUP BY day ORDER BY day DESC",
                    args);
        }
        try (Cursor cursor = typed(items.readable(), bound.sql, bound.args)) {
            while (cursor.moveToNext()) {
                days.add(new Day(cursor.isNull(0) ? null : cursor.getString(0), cursor.getLong(1)));
            }
        }
        return days;
    }

    /**
     * The unread mail of the query's collections down to its floor, chips
     * and search aside: what the badge counts, one window at a time.
     */
    long unread(Query query) {
        if (query.size == 0) {
            return 0;
        }
        Map<String, Object> values = new HashMap<>();
        values.put("collections", query.collections);
        values.put("attachment", null);
        values.put("since", query.since);
        PimdirSql.Bound bound = PimdirSql.bind("COUNT_UNREAD", values);
        long unread = 0;
        try (Cursor cursor = typed(items.readable(), bound.sql, bound.args)) {
            while (cursor.moveToNext()) {
                unread += cursor.getLong(1);
            }
        }
        return unread;
    }

    /** What a range of the list weighs: its messages, their known bytes, how many weigh unknown. */
    static final class Sum {
        final long count;

        /** The bytes of the messages whose size is known. */
        final long size;

        /** How many have no known size (Graph before it reads one, a server without it). */
        final long unknown;

        Sum(long count, long size, long unknown) {
            this.count = count;
            this.size = size;
            this.unknown = unknown;
        }
    }

    /**
     * What the query's messages dated in {@code [since, until)} weigh, under
     * its chips, either bound null for open ({@code sum_mail}): what a
     * window moved back to {@code since} takes in.
     */
    Sum sum(Query query, String since, String until) {
        if (query.size == 0) {
            return new Sum(0, 0, 0);
        }
        Map<String, Object> values = query.values(null, -1);
        values.put("since", since);
        values.put("until", until);
        PimdirSql.Bound bound = PimdirSql.bind("SUM_MAIL", values);
        try (Cursor cursor = typed(items.readable(), bound.sql, bound.args)) {
            return cursor.moveToFirst()
                    ? new Sum(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
                    : new Sum(0, 0, 0);
        }
    }

    /**
     * The sort key of the query's newest message dated below {@code floor},
     * under its chips, null when none is stored: one row of
     * {@code list_mail_page_filtered} read after a cursor sorting before
     * every row dated at the floor.
     */
    String newestBelow(Query query, String floor) {
        if (query.size == 0 || floor == null) {
            return null;
        }
        Map<String, Object> values = query.values(null, 1);
        values.put("since", null);
        values.put("after_key", floor);
        values.put("after_seq", Long.MIN_VALUE);
        values.put("after_collection", "");
        PimdirSql.Bound bound = PimdirSql.bind("LIST_MAIL_PAGE_FILTERED", values);
        try (Cursor cursor = typed(items.readable(), bound.sql, bound.args)) {
            return cursor.moveToFirst() && !cursor.isNull(5) ? cursor.getString(5) : null;
        }
    }

    /**
     * One page of the query, newest first: after {@code after} when given
     * (the keyset, what a scroll reads next), else from {@code offset} rows
     * in (what a fling lands on).
     *
     * <p>The keyset is {@code list_mail_page_filtered}'s and
     * {@code search_mail}'s own; an offset reads the same statement as a
     * subquery, which costs the rows it skips and is taken only where no
     * neighbouring page is loaded to read after.
     */
    List<StoredMessage> page(Query query, StoredMessage after, long offset, int limit) {
        List<StoredMessage> messages = new ArrayList<>(limit);
        if (query.size == 0 || limit <= 0) {
            return messages;
        }
        String statement = query.pattern == null ? "LIST_MAIL_PAGE_FILTERED" : "SEARCH_MAIL";
        PimdirSql.Bound bound;
        if (after != null || offset <= 0) {
            // NOTE: read past the page by the rows it hides, so the page
            // cut out of it is still a whole one.
            PimdirSql.Bound read =
                    PimdirSql.bind(statement, query.values(after, limit + query.hiding));
            bound = hiding(read, query, limit);
        } else {
            PimdirSql.Bound inner = listed(query);
            Object[] args = new Object[inner.args.length + 2];
            System.arraycopy(inner.args, 0, args, 0, inner.args.length);
            args[inner.args.length] = limit;
            args[inner.args.length + 1] = offset;
            bound = new PimdirSql.Bound(
                    "SELECT * FROM (" + inner.sql + ") LIMIT ? OFFSET ?", args);
        }

        Map<String, PimdirCollections.Stored> mailboxes = mailboxes();
        try (Cursor cursor = typed(items.readable(), bound.sql, bound.args)) {
            while (cursor.moveToNext()) {
                StoredMessage message = row(cursor, mailboxes);
                if (message != null) {
                    messages.add(message);
                }
            }
        }
        return messages;
    }

    /**
     * One stored message by its mailbox and key, as a list row carries it,
     * null when the mailbox holds it no more: what a file's origin opens.
     * Read as the one row of the mailbox's page keyed just past it, the
     * cursor's collection a hair above the row's.
     */
    StoredMessage message(String collection, String linkId) {
        Map<String, Object> key = new HashMap<>();
        key.put("collection", collection);
        key.put("link_id", linkId);
        PimdirSql.Bound seqOf = PimdirSql.bind("SEQ_BY_LINK", key);
        Long seq = null;
        try (Cursor cursor = typed(items.readable(), seqOf.sql, seqOf.args)) {
            if (cursor.moveToFirst()) {
                seq = cursor.getLong(0);
            }
        }
        if (seq == null) {
            return null;
        }
        key.put("seq", seq);
        PimdirSql.Bound item = PimdirSql.bind("GET_MAIL", key);
        String sortKey;
        try (Cursor cursor = typed(items.readable(), item.sql, item.args)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            sortKey = cursor.isNull(4) ? "" : cursor.getString(4);
        }
        Map<String, Object> values =
                new Query(List.of(collection), null, null, null, List.of()).values(null, 1);
        values.put("after_key", sortKey);
        values.put("after_seq", seq);
        values.put("after_collection", collection + "\u0000");
        PimdirSql.Bound bound = PimdirSql.bind("LIST_MAIL_PAGE_FILTERED", values);
        try (Cursor cursor = typed(items.readable(), bound.sql, bound.args)) {
            return cursor.moveToFirst() ? row(cursor, mailboxes()) : null;
        }
    }

    /** One row of a mail page as a list shows it, null when its mailbox is unknown. */
    private StoredMessage row(Cursor cursor, Map<String, PimdirCollections.Stored> mailboxes) {
        PimdirCollections.Stored mailbox = mailboxes.get(cursor.getString(0));
        if (mailbox == null) {
            return null;
        }
        boolean unsynced = storage.unsynced(cursor.getString(0), cursor.getString(2));
        return new StoredMessage(
                mailbox.accountEmail,
                cursor.getString(0),
                mailbox.name,
                mailboxLabel(mailbox),
                cursor.getString(2),
                cursor.isNull(9) ? "" : cursor.getString(9),
                cursor.isNull(11) ? "" : cursor.getString(11),
                cursor.isNull(10) ? "" : cursor.getString(10),
                cursor.isNull(5) ? "" : cursor.getString(5),
                cursor.getLong(1),
                cursor.isNull(3) ? null : cursor.getString(3),
                cursor.isNull(14) ? null : cursor.getInt(14),
                cursor.isNull(13) ? null : cursor.getLong(13),
                !cursor.isNull(4) && cursor.getInt(6) >= PimdirItems.FULL,
                unsynced,
                unsynced && refused(cursor.getString(0), cursor.getString(2)));
    }

    /**
     * Every stored message of some mailboxes dated from {@code since} on
     * (null for all of them), newest first, with whether the store holds its
     * body and what it weighs: what the body step plans from
     * ({@link MailBodies}). Read a page of 500 at a time on
     * {@code list_mail_page_filtered}'s keyset.
     */
    List<MailBodies.Row> bodyRows(List<String> ids, String since) {
        List<MailBodies.Row> rows = new ArrayList<>();
        if (ids.isEmpty()) {
            return rows;
        }
        Map<String, Object> values = new HashMap<>();
        values.put("collections", new JSONArray(ids).toString());
        values.put("seen", null);
        values.put("attachment", null);
        values.put("since", since);
        values.put("after_key", null);
        values.put("after_seq", null);
        values.put("after_collection", null);
        values.put("limit", 500);
        while (true) {
            PimdirSql.Bound bound = PimdirSql.bind("LIST_MAIL_PAGE_FILTERED", values);
            int read = 0;
            try (Cursor cursor = typed(items.readable(), bound.sql, bound.args)) {
                while (cursor.moveToNext()) {
                    read++;
                    String sortKey = cursor.isNull(5) ? "" : cursor.getString(5);
                    rows.add(
                            new MailBodies.Row(
                                    cursor.getString(0),
                                    cursor.getString(2),
                                    sortKey,
                                    !cursor.isNull(4) && cursor.getInt(6) >= PimdirItems.FULL,
                                    cursor.isNull(13) ? null : cursor.getLong(13),
                                    cursor.isNull(14) ? null : cursor.getInt(14)));
                    values.put("after_key", sortKey);
                    values.put("after_seq", cursor.getLong(1));
                    values.put("after_collection", cursor.getString(0));
                }
            }
            if (read < 500) {
                return rows;
            }
        }
    }

    /** Whether the server refused a change of one message for good. */
    private boolean refused(String collection, String linkId) {
        return Refusals.refused(context, collection, linkId);
    }

    /** Every message of the query, for a selection made of all of it. */
    List<StoredMessage> all(Query query) {
        List<StoredMessage> messages = new ArrayList<>();
        StoredMessage after = null;
        while (true) {
            List<StoredMessage> page = page(query, after, 0, 500);
            messages.addAll(page);
            if (page.size() < 500) {
                return messages;
            }
            after = page.get(page.size() - 1);
        }
    }

    /**
     * The name a mailbox is shown under: its role's in the reader's
     * language where the source states one, since servers name those in
     * their own words (IMAP's INBOX, Gmail's SENT or SPAM labels), its own
     * name otherwise.
     */
    String mailboxLabel(PimdirCollections.Stored mailbox) {
        int label = MailList.roleLabel(mailbox.role);
        return label == 0 ? mailbox.name : context.getString(label);
    }

    /** The mail collections, by id, for the account and name a row shows. */
    private Map<String, PimdirCollections.Stored> mailboxes() {
        Map<String, PimdirCollections.Stored> mailboxes = new LinkedHashMap<>();
        for (PimdirCollections.Stored stored : collections.list(PimdirSummary.MAIL)) {
            mailboxes.put(stored.id, stored);
        }
        return mailboxes;
    }

    /**
     * Every row a query lets through, newest first, as one statement: the
     * page or search statement read whole, down to the query's floor.
     */
    private static PimdirSql.Bound listed(Query query) {
        String statement = query.pattern == null ? "LIST_MAIL_PAGE_FILTERED" : "SEARCH_MAIL";
        return hiding(wrapped("", statement, "", query), query, -1);
    }

    /**
     * A newest-first read with the query's hidden rows cut out, at most
     * {@code limit} of what is left ({@code -1} for all); the read itself
     * when it hides none. The order is restated, a subquery's own being no
     * promise.
     */
    private static PimdirSql.Bound hiding(PimdirSql.Bound inner, Query query, long limit) {
        if (query.hidden == null) {
            return inner;
        }
        Object[] args = new Object[inner.args.length + 2];
        System.arraycopy(inner.args, 0, args, 0, inner.args.length);
        args[inner.args.length] = query.hidden;
        args[inner.args.length + 1] = limit;
        return new PimdirSql.Bound(
                "SELECT * FROM (" + inner.sql + ") WHERE NOT EXISTS (SELECT 1 FROM json_each(?) h"
                        + " WHERE json_extract(h.value, '$[0]') = collection"
                        + " AND json_extract(h.value, '$[1]') = link_id)"
                        + " ORDER BY sort_key DESC, seq DESC, collection DESC LIMIT ?",
                args);
    }

    /** A bound statement between a prefix and a suffix. */
    private static PimdirSql.Bound around(String prefix, PimdirSql.Bound inner, String suffix) {
        return new PimdirSql.Bound(prefix + inner.sql + suffix, inner.args);
    }

    /**
     * A canonical statement read whole, between a prefix and a suffix: no
     * cursor, no limit ({@code -1}), its values bound in place.
     */
    private static PimdirSql.Bound wrapped(
            String prefix, String statement, String suffix, Query query) {
        PimdirSql.Bound inner = PimdirSql.bind(statement, query.values(null, -1));
        return new PimdirSql.Bound(prefix + inner.sql + suffix, inner.args);
    }

    /**
     * Runs a read binding each value as its own type.
     *
     * <p>{@code rawQuery} binds every argument as text, and the readers
     * compare a chip against an expression with no column affinity
     * ({@code :seen = EXISTS (...)}), where the text {@code '1'} is never the
     * integer {@code 1}: the unread chip would match nothing.
     */
    static Cursor typed(SQLiteDatabase db, String sql, Object[] args) {
        return db.rawQueryWithFactory(
                (database, driver, table, query) -> {
                    bind(query, args);
                    return new SQLiteCursor(driver, table, query);
                },
                sql,
                null,
                null);
    }

    private static void bind(SQLiteQuery query, Object[] args) {
        for (int index = 0; index < args.length; index++) {
            Object value = args[index];
            int position = index + 1;
            if (value == null) {
                query.bindNull(position);
            } else if (value instanceof Number) {
                query.bindLong(position, ((Number) value).longValue());
            } else if (value instanceof Boolean) {
                query.bindLong(position, ((Boolean) value) ? 1 : 0);
            } else if (value instanceof byte[]) {
                query.bindBlob(position, (byte[]) value);
            } else {
                query.bindString(position, value.toString());
            }
        }
    }

    /**
     * Corrects one message's attachment mark from the walk of its parts,
     * once its body is in (pimdir STORAGE Annex A.1): the listing read it
     * off the top-level {@code Content-Type} alone.
     */
    void markAttachment(String collection, String linkId, boolean attachment) {
        items.writable()
                .execSQL(
                        "UPDATE mail_summary SET attachment = ? WHERE collection = ?"
                                + " AND link_id = ? AND attachment IS NOT ?",
                        new Object[] {attachment ? 1 : 0, collection, linkId, attachment ? 1 : 0});
    }

    /** What a mailbox's last complete listing covered (pimdir STORAGE section 4.3). */
    static final class Coverage {
        /** The floor on the {@code Date} header, null for all mail. */
        final String since;

        /** When the listing closed; null while none ever has. */
        final String at;

        /** Whether a listing is under way, its first pass filling in. */
        final boolean filling;

        /**
         * The floor the listing under way lists from, null for all mail or
         * none under way: a widening's band, or a first pass's chunk.
         */
        final String roundSince;

        Coverage(String since, String at, boolean filling, String roundSince) {
            this.since = since;
            this.at = at;
            this.filling = filling;
            this.roundSince = roundSince;
        }

        /**
         * How far down the list may show this mailbox, null where nothing
         * limits it: the floor of what it covers when that floor is above
         * the account's bound, the floor of its first pass while that pass
         * is still filling in, nothing for a mailbox never listed.
         */
        String limit(String bound) {
            String floor;
            if (at != null) {
                floor = since;
            } else if (filling) {
                floor = roundSince;
            } else {
                return null;
            }
            return MailScope.limits(floor, bound) ? floor : null;
        }
    }

    /**
     * What one mailbox's server source covers: the scope of its last complete
     * listing, and whether one is under way. What says "mail since" of an
     * account, or that a search over it is not exhaustive yet.
     */
    Coverage coverage(String collection) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        PimdirSql.Bound bound = PimdirSql.bind("LIST_COVERAGE", values);
        try (Cursor cursor = typed(items.readable(), bound.sql, bound.args)) {
            while (cursor.moveToNext()) {
                if (!PimdirStorage.SERVER.equals(cursor.getString(0))) {
                    continue;
                }
                return new Coverage(
                        cursor.isNull(1) ? null : cursor.getString(1),
                        cursor.isNull(3) ? null : cursor.getString(3),
                        !cursor.isNull(4),
                        cursor.isNull(5) ? null : cursor.getString(5));
            }
        }
        return new Coverage(null, null, false, null);
    }

    /**
     * How far down the merged list may show the collections of a query, null
     * when nothing limits it: the most recent of their floors, every older
     * message of one mailbox waiting on the others' chunks to reach it, and
     * of the windows of the accounts they belong to, so the list stays
     * continuous by date across accounts. A mailbox kept whole brings no
     * window. Over {@code edges}, as {@link #edges} reads them.
     */
    String floorOf(Query query, List<Edge> edges) {
        List<String> floors = new ArrayList<>();
        Map<String, String> windows = new HashMap<>();
        for (Edge edge : edges) {
            if (!query.holds(edge.collection)) {
                continue;
            }
            floors.add(edge.limit);
            if (!MailOffline.whole(context, edge.collection)) {
                floors.add(windows.computeIfAbsent(edge.accountEmail, this::windowOf));
            }
        }
        return MailWindow.latest(floors);
    }

    /**
     * The query's mailboxes whose bodies download ({@link
     * MailBodies#downloads}): what moving a window back would take in.
     */
    Query downloading(Query query) {
        Map<String, String> roles = roles();
        return query.only(
                collection ->
                        MailBodies.downloads(
                                roles.get(collection), MailOffline.whole(context, collection)));
    }

    /** Whether a mailbox of the query still has headers to list below its floor. */
    static boolean listing(Query query, List<Edge> edges) {
        for (Edge edge : edges) {
            if (query.holds(edge.collection) && edge.limit != null) {
                return true;
            }
        }
        return false;
    }

    /** An account's window today, null for all mail ({@link MailWindow#since}). */
    String windowOf(String accountEmail) {
        return MailWindow.since(context, accounts.idOf(accountEmail));
    }

    /**
     * The window an account's first mail sync leaves ({@link
     * MailWindow#initial}): its inbox's floor, else (an account listed
     * account-wide, or one with no inbox) the most recent of its mailboxes'.
     */
    String firstWindow(String accountEmail) {
        String account = accounts.idOf(accountEmail);
        boolean accountWide = accountWide(context, account);
        String inbox = null;
        List<String> floors = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (PimdirCollections.Stored stored : collections.list(PimdirSummary.MAIL)) {
            if (!stored.accountEmail.equals(accountEmail)) {
                continue;
            }
            String since = coverage(stored.id).since;
            floors.add(since);
            ids.add(stored.id);
            if ("inbox".equals(stored.role) && !accountWide) {
                inbox = since;
            }
        }
        boolean empty = count(new Query(ids, null, null, null, List.of())) == 0;
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        return MailWindow.initial(empty, inbox, floors, java.time.LocalDate.now(zone), zone);
    }

    /**
     * The window of an account set up before windows existed: the date of
     * its inbox's {@link MailEngine#FIRST_CHUNK}th newest stored message,
     * what its first chunk reached, or {@link #firstWindow} where the inbox
     * holds fewer.
     */
    String storedWindow(String accountEmail) {
        List<String> inbox = new ArrayList<>();
        for (PimdirCollections.Stored stored : collections.list(PimdirSummary.MAIL)) {
            if (stored.accountEmail.equals(accountEmail) && "inbox".equals(stored.role)) {
                inbox.add(stored.id);
            }
        }
        List<StoredMessage> newest =
                page(
                        new Query(inbox, null, null, null, List.of()),
                        null,
                        0,
                        MailEngine.FIRST_CHUNK);
        if (newest.size() < MailEngine.FIRST_CHUNK) {
            return firstWindow(accountEmail);
        }
        String oldest = newest.get(newest.size() - 1).sortKey;
        return oldest.isEmpty() ? firstWindow(accountEmail) : oldest;
    }

    /** One mailbox, and how far down its chunks have reached. */
    static final class Edge {
        final String accountEmail;
        final String mailbox;
        final String collection;

        /** What the source says the mailbox is for (pimdir's role), or null. */
        final String role;

        /** Whether a round ever listed it, closed or under way. */
        final boolean listed;

        /** The floor the list stops at for it, null where it limits nothing. */
        final String limit;

        /** Whether its account is listed account-wide ({@link MailStore#markAccountWide}). */
        final boolean accountWide;

        Edge(
                String accountEmail,
                String mailbox,
                String collection,
                String role,
                boolean listed,
                String limit) {
            this(accountEmail, mailbox, collection, role, listed, limit, false);
        }

        Edge(
                String accountEmail,
                String mailbox,
                String collection,
                String role,
                boolean listed,
                String limit,
                boolean accountWide) {
            this.accountEmail = accountEmail;
            this.mailbox = mailbox;
            this.collection = collection;
            this.role = role;
            this.listed = listed;
            this.limit = limit;
            this.accountWide = accountWide;
        }
    }

    /** Every mailbox of every account and how far its chunks have reached. */
    List<Edge> edges() {
        Map<String, String> roles = roles();
        Map<String, String> bounds = new HashMap<>();
        Map<String, Boolean> wide = new HashMap<>();
        List<Edge> edges = new ArrayList<>();
        for (PimdirCollections.Stored stored : collections.list(PimdirSummary.MAIL)) {
            String bound =
                    MailOffline.whole(context, stored.id)
                            ? null
                            : bounds.computeIfAbsent(
                                    stored.accountEmail,
                                    email -> MailScope.sinceOf(context, accounts.idOf(email)));
            boolean accountWide =
                    wide.computeIfAbsent(
                            stored.accountEmail,
                            email -> accountWide(context, accounts.idOf(email)));
            Coverage coverage = coverage(stored.id);
            edges.add(
                    new Edge(
                            stored.accountEmail,
                            stored.name,
                            stored.id,
                            roles.get(stored.id),
                            coverage.at != null || coverage.filling,
                            coverage.limit(bound),
                            accountWide));
        }
        return edges;
    }

    /** The role every mail collection carries, by collection id. */
    Map<String, String> roles() {
        Map<String, String> roles = new HashMap<>();
        String sql = PimdirSql.split(PimdirSql.of("LIST_COLLECTIONS"))[0];
        try (Cursor cursor = typed(items.readable(), sql, new Object[0])) {
            while (cursor.moveToNext()) {
                if (!cursor.isNull(9)) {
                    roles.put(cursor.getString(0), cursor.getString(9));
                }
            }
        }
        return roles;
    }

    /** How many months of mail an account keeps, 0 for all of it. */
    int monthsOf(String accountEmail) {
        return MailScope.months(context, accounts.idOf(accountEmail));
    }

    /**
     * Bounds an account's mail to its last {@code months} months, 0 for all
     * of it, answering how many stored messages the change collected.
     *
     * <p>A wider bound collects nothing: the next sync lists the band it now
     * lacks. A narrower one collects what falls below its floor, since no
     * sync deletes what lies outside its scope (pimdir SYNC section 5): the
     * messages stay on the server, and a later widening lists them again. It
     * raises a window left below its floor ({@link MailWindow#raise}).
     */
    int bound(String accountEmail, int months) {
        String account = accounts.idOf(accountEmail);
        int held = MailScope.months(context, account);
        MailScope.set(context, account, months);

        boolean narrower = months > 0 && (held == 0 || months < held);
        if (!narrower) {
            return 0;
        }
        String floor = MailScope.since(months);
        MailWindow.raise(context, account, floor);
        return collectBefore(accountEmail, floor);
    }

    /**
     * The owner's collection of one account's mail below a date (pimdir
     * STORAGE section 11.3): every message dated before {@code before} that
     * owes nothing to any server, removed with its bindings, summary and
     * body, its mailboxes keeping it on the server. What a narrowed bound
     * frees; a later widening relists and refetches them. Answers how many
     * went.
     */
    int collectBefore(String accountEmail, String before) {
        int collected = 0;
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            for (PimdirCollections.Stored stored : collections.list(PimdirSummary.MAIL)) {
                // NOTE: a mailbox kept whole is past its account's bound.
                if (!stored.accountEmail.equals(accountEmail)
                        || MailOffline.whole(context, stored.id)) {
                    continue;
                }
                Map<String, Object> values = new HashMap<>();
                values.put("collection", stored.id);
                values.put("before", before);
                PimdirSql.Bound bound = PimdirSql.bind("COLLECT_BEFORE", values);
                try (Cursor cursor = typed(db, bound.sql, bound.args)) {
                    while (cursor.moveToNext()) {
                        collected++;
                    }
                }
            }
            // NOTE: the cascade drops pins no statement returns, so the
            // counts are recomputed before the collector reads them.
            db.execSQL(PimdirSql.split(PimdirSql.of("RECOMPUTE_REFCOUNTS"))[0]);
            items.collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        items.unlinkCollected();
        return collected;
    }

    /**
     * Frees the bodies of one account's mail dated before {@code until}
     * (pimdir STORAGE section 11.4), its headers, flags and bindings kept,
     * so a search still finds them: what a window moved later lets go. A
     * mailbox kept whole keeps all of it, and so does a message the store
     * still needs the body of (a conflict, a pending create, a local edit,
     * one a source does not bind yet). The bases first, the items, the
     * counts, then the collector, in one transaction. Answers how many were
     * freed.
     */
    int release(String accountEmail, String until) {
        List<String> ids = new ArrayList<>();
        for (PimdirCollections.Stored stored : collections.list(PimdirSummary.MAIL)) {
            if (stored.accountEmail.equals(accountEmail)
                    && !MailOffline.whole(context, stored.id)) {
                ids.add(stored.id);
            }
        }
        if (ids.isEmpty()) {
            return 0;
        }
        Map<String, Object> values = new HashMap<>();
        values.put("collections", new JSONArray(ids).toString());
        values.put("until", until);
        int released = 0;
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            PimdirSql.Bound bases = PimdirSql.bind("RELEASE_BASES_BEFORE", values);
            db.execSQL(bases.sql, bases.args);
            PimdirSql.Bound bodies = PimdirSql.bind("RELEASE_BEFORE", values);
            try (Cursor cursor = typed(db, bodies.sql, bodies.args)) {
                while (cursor.moveToNext()) {
                    released++;
                }
            }
            db.execSQL(PimdirSql.split(PimdirSql.of("RECOMPUTE_REFCOUNTS"))[0]);
            items.collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        items.unlinkCollected();
        return released;
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

        /** The {@code Message-ID} it was stamped with, bare. */
        final String messageId;

        final byte[] source;

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
                waiting.add(
                        new Outgoing(action.id, action.payload.optString("messageId"), source));
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

    /**
     * Remembers that the sent copy staged for {@code messageId} arrives
     * under {@code filed}, the {@code Message-ID} the provider stamped on
     * what it sent instead (Gmail), so the sync lands the staged copy on
     * the provider's rather than listing a second.
     */
    void aliasSentCopy(String messageId, String filed) {
        if (filed.equals(messageId)) {
            return;
        }
        context.getSharedPreferences(SENT_ALIAS_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(filed, messageId)
                .apply();
    }

    /**
     * The {@code Message-ID} of the sent copy staged for a message the
     * provider filed under {@code filed} ({@link #aliasSentCopy}), else
     * null.
     */
    String stagedSentCopy(String filed) {
        return context.getSharedPreferences(SENT_ALIAS_PREFS, Context.MODE_PRIVATE)
                .getString(filed, null);
    }

    /** Forgets an alias once its staged copy landed on the provider's. */
    void forgetSentAlias(String filed) {
        context.getSharedPreferences(SENT_ALIAS_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(filed)
                .apply();
    }

    /** Records why a submission will not be tried again. */
    void parkOutgoing(long queued, String reason) {
        queue.park(queued, reason);
    }

    /** Counts one failed attempt, leaving the message where it is. */
    void retryOutgoing(long queued) {
        queue.bumpAttempts(queued);
    }

    /**
     * The name the outbox goes by in the list and the filter: a mailbox like
     * any other to the reader, though no server holds it.
     */
    String outboxName() {
        return context.getString(R.string.mail_outbox);
    }

    /**
     * Every account's mailboxes as the store holds them, each with its role,
     * for the filter page, the outbox of each mail account in {@code emails}
     * among them under its own collection.
     */
    List<PimdirCollections.Stored> loadMailboxes(List<String> emails) {
        List<PimdirCollections.Stored> mailboxes = new ArrayList<>();
        for (String email : emails) {
            mailboxes.add(
                    new PimdirCollections.Stored(
                            outboxOf(email), email, outboxName(), null, null));
        }
        mailboxes.addAll(collections.list(PimdirSummary.MAIL));
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
