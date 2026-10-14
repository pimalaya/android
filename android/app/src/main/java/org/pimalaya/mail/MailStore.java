package org.pimalaya.mail;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import io.requery.android.database.sqlite.SQLiteDatabase;
import io.requery.android.database.sqlite.SQLiteQuery;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Flags;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimdirSql;
import org.pimalaya.file.FileStore;
import org.pimalaya.pimdir.ItemLinks;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirItems;
import org.pimalaya.pimdir.PimdirQueue;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;

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
 * the bundled SQLite, the statements crossing the bridge rather than being
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
public final class MailStore {
    /** The mail roles pimdir's schema takes (STORAGE section 14). */
    private static final java.util.Set<String> ROLES =
            java.util.Set.of(
                    "inbox", "sent", "drafts", "trash", "junk", "archive", "all", "flagged",
                    "important");

    private final PimdirItems items;
    private final PimdirDb store;
    private final PimdirCollections collections;
    private final PimdirAccount accounts;
    private final PimdirQueue queue;
    private final Outbox outbox;
    private final MailPolicy policy;
    private final Context context;

    public MailStore(Context context, PimdirDb store) {
        this.items = new PimdirItems(store);
        this.store = store;
        this.collections = new PimdirCollections(store, context);
        this.accounts = new PimdirAccount(context);
        this.queue = new PimdirQueue(store, accounts);
        this.outbox = new Outbox(context, items, accounts, queue);
        this.policy = new MailPolicy(context, accounts);
        this.context = context;
    }

    /** The outbox: each account's submissions waiting on the store's queue. */
    public Outbox outbox() {
        return outbox;
    }

    /** What mail remembers of each account outside the store. */
    public MailPolicy policy() {
        return policy;
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
    public void replaceMailboxes(String accountEmail, List<Mailbox> mailboxes) {
        String account = accounts.idOf(accountEmail);

        // NOTE: before the replace, which is the moment an outbox written by
        // the version before this one would be dropped: it was a mail
        // collection, so a roster that does not list it takes it and the
        // messages in it.
        outbox.migrateOutbox(accountEmail);

        // NOTE: an account's first roster off Gmail keeps its inbox alone
        // ticked: its mailboxes step one by one, and each costs a listing.
        boolean fresh = true;
        for (PimdirCollections.Stored stored : collections.list(PimdirSummary.MAIL)) {
            if (accountEmail.equals(stored.accountEmail)) {
                fresh = false;
                break;
            }
        }
        if (fresh && !MailPolicy.keepsAllMail(context, account)) {
            policy.markInboxOnly(account);
        }
        boolean inboxOnly = policy.inboxOnly(account);

        List<PimdirCollections.Stored> listed =
                MailEngine.collectionsOf(accountEmail, account, mailboxes);
        if (MailPolicy.keepsAllMail(context, account)) {
            listed.addAll(
                    MailEngine.collectionsOf(
                            accountEmail, account, List.of(new Mailbox(PimdirCollections.ALL_MAIL, null))));
        }

        collections.replace(accountEmail, PimdirSummary.MAIL, listed);

        // NOTE: what the source states and nothing guessed (pimdir STORAGE
        // section 14): a mailbox that says nothing any more loses its role,
        // and a role moving to another mailbox leaves the first in the same
        // statement.
        SQLiteDatabase db = store.getWritableDatabase();
        for (Mailbox mailbox : mailboxes) {
            Map<String, Object> values = new HashMap<>();
            values.put("collection", PimdirAccount.collectionId(account, mailbox.name()));
            values.put("role", ROLES.contains(mailbox.role()) ? mailbox.role() : null);
            PimdirSql.Bound bound = PimdirSql.bind("SET_COLLECTION_ROLE", values);
            db.execSQL(bound.sql, bound.args);
        }

        // NOTE: the junk and the trash are hidden the first time a roster
        // lists them, so neither shows nor syncs until the user ticks it:
        // Gmail's step leaves both out, and a delete reads as gone. On an
        // account that keeps its inbox alone, so is every other mailbox.
        List<String> quiet = new ArrayList<>();
        for (Mailbox mailbox : mailboxes) {
            if (!MailBodies.downloads(mailbox.role(), false)
                    || (inboxOnly && !"inbox".equals(mailbox.role()))) {
                quiet.add(PimdirAccount.collectionId(account, mailbox.name()));
            }
        }
        MergedFilter.of(context, PimDomain.MAIL).quiet(quiet);

        String trash = "";
        for (Mailbox mailbox : mailboxes) {
            if (Mailbox.TRASH.equals(mailbox.role())) {
                trash = mailbox.name();
                break;
            }
        }
        policy.markTrash(accountEmail, trash);
    }

    /**
     * Drops everything one account's mail left in the store: its mailboxes,
     * their messages, their attachments, and whatever was still waiting to
     * go out.
     */
    public void forget(String accountEmail) {
        String outbox = this.outbox.outboxOf(accountEmail);
        List<PimdirQueue.Action> waiting = new ArrayList<>(queue.pending(outbox));
        for (PimdirQueue.Action action : queue.parked()) {
            if (action.collection().equals(outbox)) {
                waiting.add(action);
            }
        }
        for (PimdirQueue.Action action : waiting) {
            queue.acknowledge(action.id());
        }

        collections.replace(accountEmail, PimdirSummary.MAIL, List.of());
        new FileStore(context, store).forget(accounts.idOf(accountEmail));
        policy.forgetTrash(accountEmail);
        MailScope.forget(context, accounts.idOf(accountEmail));
        MailOffline.forget(context, accounts.idOf(accountEmail));
        MailWindow.forget(context, accounts.idOf(accountEmail));
        MailPolicy.markAccountWide(context, accounts.idOf(accountEmail), false);
        MailPolicy.markAllMail(context, accounts.idOf(accountEmail), false);
        policy.forgetInboxOnly(accounts.idOf(accountEmail));
        MailPolicy.markExpungesOne(context, accounts.idOf(accountEmail), true);
    }

    /** An account's All Mail collection, which it holds only if it keeps one. */
    public static String allMailOf(String accountId) {
        return PimdirAccount.collectionId(accountId, PimdirCollections.ALL_MAIL);
    }

    /**
     * Makes sure a Gmail account keeps its All Mail, for a step that runs
     * before the account's roster was read again since it had none.
     */
    public void ensureAllMail(String accountEmail) {
        String account = accounts.idOf(accountEmail);
        MailPolicy.markAllMail(context, account, true);
        collections.ensure(allMailOf(account), accountEmail, PimdirSummary.MAIL, PimdirCollections.ALL_MAIL);
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

    /** The body the store holds of one message, null when it holds none. */
    public byte[] storedSource(String collection, String linkId) {
        return items.objectBytes(collection, linkId);
    }

    /** Whether the store holds one message's body, without reading it. */
    public boolean holdsBody(StoredMessage message) {
        return items.objectOf(items.readable(), message.collection, message.id) != null;
    }

    /**
     * Whether deleting one message removes it for good: in its account's
     * trash, where the server erases one message alone (the rule
     * {@code MailEngine.stageDelete} stages by).
     */
    boolean erases(StoredMessage message) {
        String trash = policy.trashOf(message.accountEmail);
        return !message.pending()
                && !trash.isEmpty()
                && trash.equals(message.mailbox)
                && policy.expungesOne(message.accountEmail);
    }

    /** The id one account's collections are namespaced under. */
    public String accountIdOf(String accountEmail) {
        return accounts.idOf(accountEmail);
    }

    /** Where one account's mailbox is stored. */
    public String collectionOf(String accountEmail, String mailbox) {
        return PimdirAccount.collectionId(accounts.idOf(accountEmail), mailbox);
    }

    /** One stored envelope, with the account it came from. */
    public static final class StoredMessage {
        public final String accountEmail;

        /**
         * The collection it belongs to, which every store read addresses
         * it by.
         *
         * <p>Carried rather than derived from the mailbox name beside it,
         * which a message waiting to go out does not have: its collection
         * is the one its queue row is filed against, and no mailbox name
         * would lead back to it.
         */
        public final String collection;

        final String mailbox;

        /** The mailbox as the reader is shown it ({@link MailStore#mailboxLabel}). */
        final String mailboxLabel;

        public final String id;
        public final String subject;

        /** The sender's display name, empty when they sent none. */
        public final String fromName;

        /** The sender's address, what the row's avatar is derived from. */
        public final String fromAddress;

        public final long stamp;

        /**
         * The row's place in the merged order, {@code (sortKey, seq)} with
         * the collection: what the next page is read after. Empty and 0 on a
         * message waiting to go out, which is no item.
         */
        public final String sortKey;

        public final long seq;

        /** The markers it carries, what the marks a row shows are read off. */
        final Flags flags;

        public final boolean hasAttachment;

        /** The attachment mark: 1 with, 0 without, null never examined. */
        public final Integer attachment;

        /** Its size in bytes, null where the store does not know it. */
        public final Long size;

        /** Whether the store holds its body: what a row not on the phone is dimmed by. */
        public final boolean bodied;

        /**
         * The queue row this message is, or null when it is a message the
         * store synced rather than one waiting to go out.
         *
         * <p>A waiting message is not an item: it is an action on the
         * store's queue carrying the bytes to hand over, so this is the
         * only handle there is to it, and every read and discard of one
         * addresses it by this rather than by a collection and a link id.
         */
        final Queued queued;

        /**
         * The body that row pins, or null when the message is an item.
         *
         * <p>Carried on the row because it is the only address a queued
         * message's bytes have: there is no item to look them up from,
         * and the read that wants them happens long after the listing.
         */
        public final String objectHash;

        /**
         * Whether the server refused it, which parked its row.
         *
         * <p>Still shown, and still holding its message: a parked action
         * is the one thing a drain leaves behind for somebody to look at,
         * and hiding it would be losing the message quietly.
         */
        final boolean failed;

        /**
         * Whether its row was parked because the message may have gone: the
         * whole of it was handed over and no verdict came back. Sent again
         * only when somebody says so ({@link Outbox#resendOutgoing}).
         */
        final boolean uncertain;

        /**
         * What every stored message carries: the account, the collection and
         * the mailbox it is in, its key there, and what a row draws of it.
         */
        public record MessageHeader(
                String accountEmail,
                String collection,
                String mailbox,
                String id,
                String subject,
                String fromName,
                String fromAddress) {}

        /**
         * What a synced message's list row carries beside its header.
         *
         * @param mailboxLabel the mailbox as the reader is shown it
         * @param sortKey the row's sort key, null read as empty
         * @param seq the row's sequence, the second part of its place
         * @param flags the markers it carries
         * @param attachment the attachment mark: 1 with, 0 without, null
         *     never examined
         * @param size its size in bytes, null where the store does not know it
         * @param bodied whether the store holds its body
         */
        public record Listing(
                String mailboxLabel,
                String sortKey,
                long seq,
                Flags flags,
                Integer attachment,
                Long size,
                boolean bodied) {}

        /**
         * The queue row a message waiting to go out is.
         *
         * @param id the queue row
         * @param objectHash the body that row pins
         * @param failed whether the server refused it, which parked its row
         * @param uncertain whether its row was parked because the message
         *     may have gone
         */
        record Queued(long id, String objectHash, boolean failed, boolean uncertain) {}

        /** One message waiting on the queue, as the outbox shows it. */
        static StoredMessage outgoing(MessageHeader header, long stamp, Flags flags, Queued queued) {
            return new StoredMessage(header, stamp, flags, queued);
        }

        /** One synced message, as a page of the merged list reads it. */
        public static StoredMessage synced(MessageHeader header, Listing listing) {
            return new StoredMessage(header, listing);
        }

        private StoredMessage(MessageHeader header, long stamp, Flags flags, Queued queued) {
            this.accountEmail = header.accountEmail();
            this.collection = header.collection();
            this.mailbox = header.mailbox();
            this.mailboxLabel = header.mailbox();
            this.id = header.id();
            this.subject = header.subject();
            this.fromName = header.fromName();
            this.fromAddress = header.fromAddress();
            this.stamp = stamp;
            this.flags = flags;
            this.hasAttachment = false;
            this.attachment = 0;
            this.size = null;
            this.bodied = true;
            this.queued = queued;
            this.objectHash = queued.objectHash();
            this.failed = queued.failed();
            this.uncertain = queued.uncertain();
            this.sortKey = "";
            this.seq = 0;
        }

        private StoredMessage(MessageHeader header, Listing listing) {
            this.accountEmail = header.accountEmail();
            this.collection = header.collection();
            this.mailbox = header.mailbox();
            this.mailboxLabel = listing.mailboxLabel();
            this.id = header.id();
            this.subject = header.subject();
            this.fromName = header.fromName();
            this.fromAddress = header.fromAddress();
            this.sortKey = listing.sortKey() == null ? "" : listing.sortKey();
            this.seq = listing.seq();
            // NOTE: from the key rather than the summary's date, since the
            // key is what the row was ordered by: a label disagreeing with
            // the order it appears in reads as a bug.
            this.stamp = PimdirSummary.stampOf(this.sortKey);
            this.flags = listing.flags();
            this.hasAttachment = listing.attachment() != null && listing.attachment() == 1;
            this.attachment = listing.attachment();
            this.size = listing.size();
            this.bodied = listing.bodied();
            this.queued = null;
            this.objectHash = null;
            this.failed = false;
            this.uncertain = false;
        }

        public boolean seen() {
            return flags.has(Flags.Marker.SEEN);
        }

        boolean answered() {
            return flags.has(Flags.Marker.ANSWERED);
        }

        public boolean flagged() {
            return flags.has(Flags.Marker.FLAGGED);
        }

        /**
         * Whether the message is waiting to go out, which is what a row
         * says of itself rather than something a caller works out: it is the
         * one state where the store holds a message no server has.
         */
        public boolean pending() {
            return queued != null;
        }

        /**
         * Whether it carries {@code \Deleted}: deleted where the server
         * expunges no single message (no UIDPLUS), and so still listed.
         */
        public boolean deleted() {
            return flags.has(Flags.Marker.DELETED);
        }

        /** The sender as a row shows them: the name, else the address. */
        String sender() {
            return fromName.isEmpty() ? fromAddress : fromName;
        }
    }

    /** A message's endpoint; null for one still in an outbox, which is no item. */
    public static ItemLinks.Endpoint endpointOf(StoredMessage message) {
        return message.pending() ? null : ItemLinks.of(PimdirSummary.MAIL, message.id);
    }

    /**
     * Messages whose words hold {@code words}, at most {@code limit}, as the
     * link search lists them ahead of the other kinds
     * ({@link ItemLinks#search}): through the mail search, each message once.
     */
    public List<ItemLinks.Placement> linkPlacements(String words, int limit) {
        Map<String, ItemLinks.Placement> found = new LinkedHashMap<>();
        if (words.trim().isEmpty()) {
            return new ArrayList<>();
        }
        Query query = query((account, collection) -> true, false, false, words);
        for (StoredMessage message : page(query, null, 0, limit)) {
            ItemLinks.Endpoint endpoint = endpointOf(message);
            if (endpoint != null) {
                found.putIfAbsent(
                        endpoint.linkId,
                        new ItemLinks.Placement(
                                endpoint,
                                message.collection,
                                message.seq,
                                message.subject,
                                message.fromName.isEmpty()
                                        ? message.fromAddress
                                        : message.fromName));
            }
        }
        return new ArrayList<>(found.values());
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
        List<StoredMessage> messages = new ArrayList<>(outbox.outgoing());
        messages.addAll(page(query((account, mailbox) -> true, false, false, ""), null, 0, limit));
        return messages;
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
    public static final class Query {
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

        public Query(List<String> collections, Integer seen, Integer attachment, String pattern,
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
        public Query since(String since) {
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
    public Query query(
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
        for (PimdirQueue.Action action : outbox.submissions()) {
            Submission submission = Submission.from(action);
            String from = submission.from();
            String sent = sentOf(from);
            if (sent != null && ids.contains(sent) && accepts.test(from, outbox.outboxOf(from))) {
                hidden.add(new String[] {sent, submission.messageId()});
            }
        }
        String trimmed = words == null ? "" : words.trim();
        return new Query(
                ids,
                unreadOnly ? 0 : null,
                attachmentsOnly ? 1 : null,
                trimmed.isEmpty() ? null : PimdirDb.likePattern(trimmed),
                hidden);
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
                ? unjoined(PimdirSql.bind("COUNT_MAIL", query.values(null, -1)), query.attachment)
                : around("SELECT count(*) FROM (", listed(query), ")");
        try (Cursor cursor = PimdirDb.typed(items.readable(), bound.sql, bound.args)) {
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
            bound = unjoined(PimdirSql.bind("COUNT_MAIL_BY_DAY", values), query.attachment);
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
        try (Cursor cursor = PimdirDb.typed(items.readable(), bound.sql, bound.args)) {
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
        PimdirSql.Bound bound = unjoined(PimdirSql.bind("COUNT_UNREAD", values), null);
        long unread = 0;
        try (Cursor cursor = PimdirDb.typed(items.readable(), bound.sql, bound.args)) {
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
     * its chips, either bound null for open ({@code sum_mail}), each message
     * once: with {@code held} 0 those the phone holds no body of, what a
     * window moved back to {@code since} downloads; 1 those it holds, what a
     * window moved later to {@code until} frees; null either.
     */
    Sum sum(Query query, String since, String until, Integer held) {
        if (query.size == 0) {
            return new Sum(0, 0, 0);
        }
        Map<String, Object> values = query.values(null, -1);
        values.put("since", since);
        values.put("until", until);
        values.put("held", held);
        PimdirSql.Bound bound = PimdirSql.bind("SUM_MAIL", values);
        try (Cursor cursor = PimdirDb.typed(items.readable(), bound.sql, bound.args)) {
            return cursor.moveToFirst()
                    ? new Sum(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
                    : new Sum(0, 0, 0);
        }
    }

    /**
     * The sort keys of the query's {@code count} newest messages dated below
     * {@code floor}, under its chips, newest first, an undated one left out:
     * {@code list_mail_page_filtered} read after a cursor sorting before
     * every row dated at the floor. What a step from the store moves a
     * frontier by ({@link WidenStep}).
     */
    List<String> datesBelow(Query query, String floor, int count) {
        List<String> dates = new ArrayList<>();
        if (query.size == 0 || floor == null) {
            return dates;
        }
        Map<String, Object> values = query.values(null, count);
        values.put("since", null);
        values.put("after_key", floor);
        values.put("after_seq", Long.MIN_VALUE);
        values.put("after_collection", "");
        PimdirSql.Bound bound = below(PimdirSql.bind("LIST_MAIL_PAGE_FILTERED", values), floor);
        try (Cursor cursor = PimdirDb.typed(items.readable(), bound.sql, bound.args)) {
            while (cursor.moveToNext()) {
                if (!cursor.isNull(5) && !cursor.getString(5).isEmpty()) {
                    dates.add(cursor.getString(5));
                }
            }
        }
        return dates;
    }

    /**
     * One page of the query, newest first: after {@code after} when given
     * (the keyset, what a scroll reads next), else from {@code offset} rows
     * in.
     *
     * <p>The keyset is {@code list_mail_page_filtered}'s and
     * {@code search_mail}'s own, bounded on the sort key ({@link #below}) so
     * a page deep in the list costs its own rows. An offset reads the same
     * statement as a subquery, which costs the rows it skips: a fling reads
     * from the day it lands on instead ({@link #pageBelow}).
     */
    public List<StoredMessage> page(Query query, StoredMessage after, long offset, int limit) {
        if (query.size == 0 || limit <= 0) {
            return new ArrayList<>();
        }
        String statement = query.pattern == null ? "LIST_MAIL_PAGE_FILTERED" : "SEARCH_MAIL";
        PimdirSql.Bound bound;
        if (after != null || offset <= 0) {
            // NOTE: read past the page by the rows it hides, so the page
            // cut out of it is still a whole one.
            PimdirSql.Bound read =
                    PimdirSql.bind(statement, query.values(after, limit + query.hiding));
            bound = below(hiding(read, query, limit), after == null ? null : after.sortKey);
        } else {
            bound = skipping(listed(query), offset, limit);
        }
        return rows(bound, limit);
    }

    /**
     * One page of the query, newest first, {@code skip} rows into those
     * sorting below {@code key} (a sort key, null for the top): what a fling
     * lands on, from the start of the day it reaches ({@link MailList}),
     * costing the rows of that day it skips rather than every row above it.
     */
    List<StoredMessage> pageBelow(Query query, String key, long skip, int limit) {
        if (query.size == 0 || limit <= 0) {
            return new ArrayList<>();
        }
        String statement = query.pattern == null ? "LIST_MAIL_PAGE_FILTERED" : "SEARCH_MAIL";
        Map<String, Object> values = query.values(null, skip + limit + query.hiding);
        if (key != null) {
            values.put("after_key", key);
            values.put("after_seq", Long.MIN_VALUE);
            values.put("after_collection", "");
        }
        PimdirSql.Bound read = hiding(PimdirSql.bind(statement, values), query, skip + limit);
        return rows(below(skipping(read, skip, limit), key), limit);
    }

    /** A read narrowed to {@code limit} of its rows past the first {@code skip}. */
    private static PimdirSql.Bound skipping(PimdirSql.Bound inner, long skip, int limit) {
        Object[] args = new Object[inner.args.length + 2];
        System.arraycopy(inner.args, 0, args, 0, inner.args.length);
        args[inner.args.length] = limit;
        args[inner.args.length + 1] = skip;
        return new PimdirSql.Bound("SELECT * FROM (" + inner.sql + ") LIMIT ? OFFSET ?", args);
    }

    /** The rows of a page read as a list shows them. */
    private List<StoredMessage> rows(PimdirSql.Bound bound, int limit) {
        List<StoredMessage> messages = new ArrayList<>(limit);
        Map<String, PimdirCollections.Stored> mailboxes = mailboxes();
        try (Cursor cursor = PimdirDb.typed(items.readable(), bound.sql, bound.args)) {
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
     * A newest-first keyset read with the bound its cursor implies stated on
     * the sort key, so the index serves it: {@code sort_key <= key} on
     * {@code items}, read through a common table expression of that name
     * which the canonical statement then reads in its place, and which
     * SQLite flattens into it. Without it the cursor's row comparison
     * stays out of {@code items_by_sort_global}, and every page walks from
     * the newest row down to its own (pimdir's statements are to state the
     * bound themselves; see cairn/changes/store-performance). A null key
     * reads the statement as it is.
     */
    static PimdirSql.Bound below(PimdirSql.Bound inner, String key) {
        if (key == null) {
            return inner;
        }
        Object[] args = new Object[inner.args.length + 1];
        args[0] = key;
        System.arraycopy(inner.args, 0, args, 1, inner.args.length);
        return new PimdirSql.Bound(
                "WITH items AS (SELECT * FROM main.items WHERE sort_key <= ?) " + inner.sql,
                args);
    }

    /**
     * A count read without the summary join it does not need: with the
     * attachment chip off, {@code count_mail}, {@code count_mail_by_day} and
     * {@code count_unread} read {@code mail_summary} for that chip alone, so
     * an empty relation of that name stands in for it and every row skips
     * the probe (MailStoreTest pins that they read nothing else of it).
     * With the chip on, the statement as it is.
     */
    static PimdirSql.Bound unjoined(PimdirSql.Bound inner, Integer attachment) {
        if (attachment != null) {
            return inner;
        }
        return new PimdirSql.Bound(
                "WITH mail_summary(collection, link_id, attachment)"
                        + " AS (SELECT NULL, NULL, NULL WHERE 0) " + inner.sql,
                inner.args);
    }

    /**
     * One stored message by its mailbox and key, as a list row carries it
     * ({@code get_mail_row}), null when the mailbox holds it no more: what a
     * file's origin and a link open.
     */
    public StoredMessage message(String collection, String linkId) {
        Map<String, Object> key = new HashMap<>();
        key.put("collection", collection);
        key.put("link_id", linkId);
        PimdirSql.Bound bound = PimdirSql.bind("GET_MAIL_ROW", key);
        try (Cursor cursor = PimdirDb.typed(items.readable(), bound.sql, bound.args)) {
            return cursor.moveToFirst() ? row(cursor, mailboxes()) : null;
        }
    }

    /** One row of a mail page as a list shows it, null when its mailbox is unknown. */
    private StoredMessage row(Cursor cursor, Map<String, PimdirCollections.Stored> mailboxes) {
        PimdirCollections.Stored mailbox = mailboxes.get(cursor.getString(0));
        if (mailbox == null) {
            return null;
        }
        return StoredMessage.synced(
                new StoredMessage.MessageHeader(
                        mailbox.accountEmail,
                        cursor.getString(0),
                        mailbox.name,
                        cursor.getString(2),
                        cursor.isNull(9) ? "" : cursor.getString(9),
                        cursor.isNull(11) ? "" : cursor.getString(11),
                        cursor.isNull(10) ? "" : cursor.getString(10)),
                new StoredMessage.Listing(
                        mailboxLabel(mailbox),
                        cursor.isNull(5) ? "" : cursor.getString(5),
                        cursor.getLong(1),
                        flags(cursor.isNull(3) ? null : cursor.getString(3)),
                        cursor.isNull(14) ? null : cursor.getInt(14),
                        cursor.isNull(13) ? null : cursor.getLong(13),
                        !cursor.isNull(4) && cursor.getInt(6) >= PimdirItems.FULL));
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
            PimdirSql.Bound bound =
                    below(
                            PimdirSql.bind("LIST_MAIL_PAGE_FILTERED", values),
                            (String) values.get("after_key"));
            int read = 0;
            try (Cursor cursor = PimdirDb.typed(items.readable(), bound.sql, bound.args)) {
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

    /** Every message of the query, for a selection made of all of it. */
    public List<StoredMessage> all(Query query) {
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
    public String mailboxLabel(PimdirCollections.Stored mailbox) {
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
     * Restates one message's summary from its body once the body is in
     * (pimdir STORAGE Annex A.1): the attachment mark and the size the
     * listing read without it are replaced by the body's own, and the
     * invitation it names is recorded, then tied to its calendar item
     * ({@code link_invitations_of}). {@code summary} is what
     * {@code PimdirSql.derive} gives of the body; null changes nothing.
     */
    public void restateFromBody(String collection, String linkId, JSONObject summary) {
        if (summary == null) {
            return;
        }
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            PimdirSummary.write(db, collection, linkId, summary);
            PimdirSummary.link(db, "LINK_INVITATIONS_OF", linkId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** What a mailbox's last complete listing covered (pimdir STORAGE section 4.3). */
    public static final class Coverage {
        /** The floor on the {@code Date} header, null for all mail. */
        public final String since;

        /** When the listing closed; null while none ever has. */
        public final String at;

        /** Whether a listing is under way, its first pass filling in. */
        public final boolean filling;

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
    public Coverage coverage(String collection) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        PimdirSql.Bound bound = PimdirSql.bind("LIST_COVERAGE", values);
        try (Cursor cursor = PimdirDb.typed(items.readable(), bound.sql, bound.args)) {
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
     * continuous by date across accounts, a mailbox stepped alone reaching
     * down to its own ({@link #mailboxWindow}). A mailbox kept whole brings
     * no window; a mailbox of an account stepped through account-wide
     * (Gmail) no floor, its steps alone deciding what it holds. Over
     * {@code edges}, as {@link #edges} reads them.
     */
    String floorOf(Query query, List<Edge> edges) {
        List<String> floors = new ArrayList<>();
        for (Edge edge : edges) {
            if (!query.holds(edge.collection)) {
                continue;
            }
            if (!edge.accountWide) {
                floors.add(edge.limit);
            }
            if (!MailOffline.whole(context, edge.collection)) {
                floors.add(mailboxWindow(edge.collection));
            }
        }
        return MailWindow.latest(floors);
    }

    /** An account's window today, null for all mail ({@link MailWindow#since}). */
    public String windowOf(String accountEmail) {
        return MailWindow.since(context, accounts.idOf(accountEmail));
    }

    /**
     * A mailbox's window today, null for all mail: the older of its own, a
     * step over it alone left, and its account's.
     */
    String mailboxWindow(String collection) {
        return MailWindow.since(context, PimdirAccount.accountIdOf(collection), collection);
    }

    /**
     * The window an account's first mail sync leaves ({@link
     * MailWindow#initial}): its inbox's floor, else (an account listed
     * account-wide, or one with no inbox) the most recent of its mailboxes'.
     */
    String firstWindow(String accountEmail) {
        String account = accounts.idOf(accountEmail);
        boolean accountWide = MailPolicy.accountWide(context, account);
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
    public static final class Edge {
        public final String accountEmail;
        final String mailbox;
        public final String collection;

        /** What the source says the mailbox is for (pimdir's role), or null. */
        final String role;

        /** Whether a round ever listed it, closed or under way. */
        final boolean listed;

        /** The floor the list stops at for it, null where it limits nothing. */
        final String limit;

        /** Whether its account is listed account-wide ({@link MailPolicy#markAccountWide}). */
        public final boolean accountWide;

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
    public List<Edge> edges() {
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
                            email -> MailPolicy.accountWide(context, accounts.idOf(email)));
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
    public Map<String, String> roles() {
        Map<String, String> roles = new HashMap<>();
        String sql = PimdirSql.split(PimdirSql.of("LIST_COLLECTIONS"))[0];
        try (Cursor cursor = PimdirDb.typed(items.readable(), sql, new Object[0])) {
            while (cursor.moveToNext()) {
                if (!cursor.isNull(9)) {
                    roles.put(cursor.getString(0), cursor.getString(9));
                }
            }
        }
        return roles;
    }

    /** How many months of mail an account keeps, 0 for all of it. */
    public int monthsOf(String accountEmail) {
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
    public int bound(String accountEmail, int months) {
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
            for (PimdirCollections.Stored stored : collections.listAll(PimdirSummary.MAIL)) {
                // NOTE: a mailbox kept whole is past its account's bound.
                if (!stored.accountEmail.equals(accountEmail)
                        || MailOffline.whole(context, stored.id)) {
                    continue;
                }
                Map<String, Object> values = new HashMap<>();
                values.put("collection", stored.id);
                values.put("before", before);
                collected += PimdirDb.changed(db, PimdirSql.bind("COLLECT_BEFORE", values));
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
    public int release(String accountEmail, String until) {
        List<String> ids = new ArrayList<>();
        for (PimdirCollections.Stored stored : collections.listAll(PimdirSummary.MAIL)) {
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
            released = PimdirDb.changed(db, PimdirSql.bind("RELEASE_BEFORE", values));
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
     * Frees the bodies of one account's mail that another collection holds
     * under the same link id, each item back to its summary for the body
     * step to read again: what the store-wide lookup linked across IMAP
     * mailboxes while it read a UID as an identity, another mailbox's
     * message of the same UID standing in for the one listed. Both sides
     * of a link go, which one is right being unknown without the bytes; a
     * message the store still needs the body of stays, on {@code
     * release_before}'s terms (pimdir STORAGE section 11.4). Answers how
     * many were freed.
     */
    int releaseCrossLinked(String accountId) {
        SQLiteDatabase db = items.writable();
        int released = 0;
        db.beginTransaction();
        try {
            List<String[]> linked = new ArrayList<>();
            try (Cursor cursor =
                    db.rawQuery(
                            "SELECT i.collection, i.link_id, i.object_hash FROM items i"
                                    + " JOIN collections c ON c.id = i.collection"
                                    + " WHERE c.account = ? AND c.kind = ?"
                                    + " AND i.deleted = 0 AND i.conflicted = 0"
                                    + " AND i.object_hash IS NOT NULL"
                                    + " AND EXISTS (SELECT 1 FROM items o"
                                    + " WHERE o.link_id = i.link_id AND o.collection <> i.collection"
                                    + " AND o.object_hash = i.object_hash)"
                                    + " AND EXISTS (SELECT 1 FROM bindings b"
                                    + " WHERE b.collection = i.collection AND b.link_id = i.link_id)"
                                    + " AND NOT EXISTS (SELECT 1 FROM bindings b"
                                    + " WHERE b.collection = i.collection AND b.link_id = i.link_id"
                                    + " AND (b.conflicted = 1"
                                    + " OR NOT (b.base_present = 1 OR b.base_flags IS NOT NULL"
                                    + " OR b.base_object IS NOT NULL OR b.base_revision IS NOT NULL)"
                                    + " OR (b.base_object IS NOT NULL"
                                    + " AND b.base_object != i.object_hash)))",
                            new String[] {accountId, PimdirSummary.MAIL})) {
                while (cursor.moveToNext()) {
                    linked.add(
                            new String[] {
                                cursor.getString(0), cursor.getString(1), cursor.getString(2)
                            });
                }
            }
            for (String[] item : linked) {
                db.execSQL(
                        "UPDATE bindings SET base_object = NULL, base_present = 1"
                                + " WHERE collection = ? AND link_id = ? AND base_object = ?",
                        item);
                db.execSQL(
                        "UPDATE items SET object_hash = NULL, level = 1"
                                + " WHERE collection = ? AND link_id = ?",
                        new String[] {item[0], item[1]});
                released++;
            }
            if (released > 0) {
                db.execSQL(PimdirSql.split(PimdirSql.of("RECOMPUTE_REFCOUNTS"))[0]);
                items.collectGarbage(db);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        items.unlinkCollected();
        return released;
    }

    /**
     * {@link #releaseCrossLinked} once per account, for an account whose
     * link ids name a message in its own mailbox alone; answers how many
     * were freed, 0 once done.
     */
    int repairLinks(String accountId) {
        if (policy.linksRepaired(accountId)) {
            return 0;
        }
        int released = releaseCrossLinked(accountId);
        policy.markLinksRepaired(accountId);
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
    Flags flagsOf(String collection, String id) {
        try (Cursor cursor =
                items.readable()
                        .rawQuery(
                                "SELECT flags FROM items WHERE collection = ? AND link_id = ?",
                                new String[] {collection, id})) {
            if (!cursor.moveToFirst() || cursor.isNull(0)) {
                return Flags.none();
            }
            try {
                return Flags.parse(cursor.getString(0));
            } catch (JSONException error) {
                // An unreadable marker set says nothing, so there is nothing
                // in it to keep: the staged set replaces it whole.
                return Flags.none();
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
    public byte[] source(StoredMessage message) {
        if (message.pending()) {
            return queue.body(message.objectHash);
        }
        return items.objectBytes(message.collection, message.id);
    }

    /**
     * The file one message's body is stored in, null when the store holds
     * none: what an attachment is read out of, the body never loaded whole on
     * this side.
     */
    java.io.File sourceFile(StoredMessage message) {
        return message.pending() ? null : items.objectFile(message.collection, message.id);
    }

    /** Files the message a reader just fetched, against its envelope. */
    public void saveSource(String collection, String id, byte[] source) {
        items.putObject(collection, id, source);
    }

    /**
     * Files the message a reader just fetched into {@code file}, hashed by the
     * bridge as {@code hash}, against its envelope: moved into the blob store,
     * never read on this side.
     */
    void saveSource(String collection, String id, String hash, java.io.File file) {
        items.putObject(collection, id, hash, file);
    }

    /**
     * Every account's mailboxes as the store holds them, each with its role,
     * for the filter page, the outbox of each mail account in {@code emails}
     * among them under its own collection.
     */
    public List<PimdirCollections.Stored> loadMailboxes(List<String> emails) {
        List<PimdirCollections.Stored> mailboxes = new ArrayList<>();
        for (String email : emails) {
            mailboxes.add(
                    new PimdirCollections.Stored(
                            outbox.outboxOf(email), email, outbox.outboxName(), null, null));
        }
        mailboxes.addAll(collections.list(PimdirSummary.MAIL));
        return mailboxes;
    }

    /** The stored flag set, as a row reads its marks off it. */
    private static Flags flags(String flags) {
        try {
            return Flags.parse(flags);
        } catch (JSONException error) {
            // An unreadable flag set is an unknown one, and unknown reads as
            // unset: an unread message shows rather than hides, and no icon
            // claims a state the store cannot back up.
            return Flags.none();
        }
    }
}
