package org.pimalaya.mail;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

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
import org.pimalaya.pimdir.PimdirIndexDb;
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
 * The mail side of the pimdir store: mailboxes of kind {@code message/rfc822}.
 *
 * <p>A sync stores {@code meta} items, an open raises one to {@code full}.
 * Mailbox ids are namespaced by account ({@link PimdirAccount}). Only
 * {@link MailEngine} writes items, so staged changes survive a refresh.
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

    /** The mail side of {@code store}. */
    public MailStore(Context context, PimdirDb store) {
        this.items = new PimdirItems(store);
        this.store = store;
        this.collections = new PimdirCollections(store, context);
        this.accounts = new PimdirAccount(context);
        this.queue = new PimdirQueue(store, accounts);
        this.outbox = new Outbox(context, items, accounts, queue, this::sentOf);
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
     * Replaces an account's mailbox roster with the walk's, noting the trash.
     *
     * <p>The outbox is a queue collection of no kind, so the replace, which
     * drops collections of the kind no longer listed, never touches it.
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
        // NOTE: the mailboxes the setup ticked, when it asked, stand in for
        // that default: they alone show and sync, junk and trash included.
        java.util.Set<String> chosen = policy.chosen(accountEmail);
        if (fresh && chosen == null && !MailPolicy.keepsAllMail(context, account)) {
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
            PimdirDb.changed(db, bound);
        }

        // NOTE: the junk and the trash are hidden the first time a roster
        // lists them, so neither shows nor syncs until the user ticks it:
        // Gmail's step leaves both out, and a delete reads as gone. On an
        // account that keeps its inbox alone, so is every other mailbox.
        List<String> quiet = new ArrayList<>();
        for (Mailbox mailbox : mailboxes) {
            boolean off =
                    chosen != null
                            ? !chosen.contains(mailbox.name())
                            : !MailBodies.downloads(mailbox.role(), false)
                                    || (inboxOnly && !"inbox".equals(mailbox.role()));
            if (off) {
                quiet.add(PimdirAccount.collectionId(account, mailbox.name()));
            }
        }
        MergedFilter.of(context, PimDomain.MAIL).quiet(quiet);
        if (chosen != null) {
            policy.forgetChosen(accountEmail);
        }

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
     * Keeps the mailboxes the setup ticked, by name, for the account's first
     * roster to show and sync alone.
     */
    public void chooseMailboxes(String accountEmail, java.util.Set<String> names) {
        policy.markChosen(accountEmail, names);
    }

    /** Drops all of one account's mail, its outbox included. */
    public void forget(String accountEmail) {
        // NOTE: before its collections go, which a row anchored on one of
        // them would refuse (collections_keep_queued).
        for (PimdirQueue.Action action : outbox.submissions(accountEmail)) {
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
        policy.forgetChosen(accountEmail);
        MailPolicy.markExpungesOne(context, accounts.idOf(accountEmail), true);
    }

    /** An account's All Mail collection, held only if it keeps one. */
    public static String allMailOf(String accountId) {
        return PimdirAccount.collectionId(accountId, PimdirCollections.ALL_MAIL);
    }

    /**
     * Makes sure a Gmail account keeps its All Mail, before a roster re-read.
     */
    public void ensureAllMail(String accountEmail) {
        String account = accounts.idOf(accountEmail);
        MailPolicy.markAllMail(context, account, true);
        collections.ensure(allMailOf(account), accountEmail, PimdirSummary.MAIL, PimdirCollections.ALL_MAIL);
    }

    /** An account's sent collection (RFC 6154 {@code \Sent}), or null. */
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
     * Whether deleting one message removes it for good.
     *
     * <p>In its trash, on a server erasing one message alone: the rule
     * {@link MailStager#stageDelete} stages by.
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
        /** The account it came from. */
        public final String accountEmail;

        /**
         * The collection it belongs to, which every store read addresses.
         *
         * <p>Carried, not derived from the mailbox name: a waiting message has
         * none, its collection being the one its queue row is filed against.
         */
        public final String collection;

        final String mailbox;

        /** The mailbox as the reader is shown it. */
        final String mailboxLabel;

        /** Its link id in the collection. */
        public final String id;
        /** Its subject. */
        public final String subject;

        /** The sender's display name, empty when they sent none. */
        public final String fromName;

        /** The sender's address, what the row's avatar is derived from. */
        public final String fromAddress;

        /** Its date, in epoch milliseconds. */
        public final long stamp;

        /**
         * The row's merged-order place with {@code seq}: what pages follow.
         *
         * <p>Empty and 0 on a waiting message, which is no item.
         */
        public final String sortKey;

        /** The row's sequence, the second part of its place. */
        public final long seq;

        /** The markers it carries, what the marks a row shows are read off. */
        final Flags flags;

        /** Whether it carries an attachment. */
        public final boolean hasAttachment;

        /** The attachment mark: 1 with, 0 without, null never examined. */
        public final Integer attachment;

        /** Its size in bytes, null where the store does not know it. */
        public final Long size;

        /** Whether the store holds its body: rows without are dimmed. */
        public final boolean bodied;

        /**
         * The queue row this message is, null for a synced message.
         *
         * <p>A waiting message is a queue action carrying its bytes, not an
         * item: every read and discard of one addresses it by this.
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
         * Whether its row was parked as possibly gone: no verdict came back.
         *
         * <p>Sent again only when asked ({@link Outbox#resendOutgoing}).
         */
        final boolean uncertain;

        /**
         * What every stored message carries: account, collection, key, row.
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
         * @param sortKey the row's sort key, null read as empty
         * @param attachment 1 with, 0 without, null never examined
         * @param size its size in bytes, null where the store does not know it
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
         * @param objectHash the body that row pins
         * @param failed whether the server refused it, which parked its row
         * @param uncertain whether it was parked as possibly gone
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

        /** Whether it carries {@code \Seen}. */
        public boolean seen() {
            return flags.has(Flags.Marker.SEEN);
        }

        boolean answered() {
            return flags.has(Flags.Marker.ANSWERED);
        }

        /** Whether it carries {@code \Flagged}. */
        public boolean flagged() {
            return flags.has(Flags.Marker.FLAGGED);
        }

        /**
         * Whether the message is waiting to go out, held by no server.
         */
        public boolean pending() {
            return queued != null;
        }

        /**
         * Whether it carries {@code \Deleted}, still listed (no UIDPLUS).
         */
        public boolean deleted() {
            return flags.has(Flags.Marker.DELETED);
        }

        /** The sender as a row shows them: the name, else the address. */
        String sender() {
            return fromName.isEmpty() ? fromAddress : fromName;
        }
    }

    /** A message's endpoint; null for one in an outbox, no item. */
    public static ItemLinks.Endpoint endpointOf(StoredMessage message) {
        return message.pending() ? null : ItemLinks.of(PimdirSummary.MAIL, message.id);
    }

    /**
     * Messages holding {@code words}, at most {@code limit}, each once.
     *
     * <p>Through the mail search, listed first by {@link ItemLinks#search}.
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

    /** A message's own `Message-ID`, bare; null when the store has none. */
    public String messageIdOf(String collection, String linkId) {
        List<String> thread = threadOf(collection, linkId);
        return thread.isEmpty() ? null : PimdirSummary.bare(thread.get(thread.size() - 1));
    }

    /**
     * A reply's `References`, oldest first, ending with the parent's id.
     *
     * <p>Angle-bracketed, empty with no id. The parent's `In-Reply-To` stands
     * in for its `References`, unkept (RFC 5322 3.6.4 allows it).
     */

    List<String> threadOf(String collection, String linkId) {
        List<String> thread = new ArrayList<>();
        String messageId;
        Map<String, Object> key = PimdirSql.values("collection", collection, "link_id", linkId);
        try (Cursor cursor = PimdirDb.query(items.readable(), "MESSAGE_ID_OF", key)) {
            if (!cursor.moveToNext() || cursor.isNull(0)) {
                return thread;
            }
            messageId = cursor.getString(0);
        }
        // NOTE: the reply ids are rows of their own (Annex A.1), in order.
        try (Cursor cursor = PimdirDb.query(items.readable(), "IN_REPLY_TO_OF", key)) {
            while (cursor.moveToNext()) {
                thread.add("<" + cursor.getString(0) + ">");
            }
        }
        thread.add("<" + messageId + ">");
        return thread;
    }

    /**
     * What the merged list shows: collections, the two chips, searched words.
     *
     * <p>Every read below answers under it, so the count, per-day counts and
     * pages agree.
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
         * The seqs the search index answers the words with (SEARCH §8), null
         * when nothing is searched or the index answers nothing: the sender
         * and subject {@link #pattern} matches are kept beside them.
         */
        final List<Long> hits;

        /** Why the index refused the words (SEARCH §8.4), null when it did not. */
        final String refusal;

        /**
         * How far down the list reaches on the sort key, null for all.
         *
         * <p>Its floor ({@link #floorOf}) or a window; canonical statements
         * apply it themselves.
         */
        final String since;

        /**
         * Rows left out, each {@code [collection, link_id]}.
         *
         * <p>Null for none. Sent copies staged beside a waiting submission,
         * whose outbox row stands for them.
         */
        final List<String[]> hidden;

        /** How many rows {@link #hidden} names. */
        final int hiding;

        private final java.util.Set<String> held;

        /** A query over {@code collections}, chips null when off. */
        public Query(List<String> collections, Integer seen, Integer attachment, String pattern,
                List<String[]> hidden) {
            this(collections, seen, attachment, pattern, null, null, hidden);
        }

        /** A query searching {@code pattern} and the index's {@code hits}. */
        Query(List<String> collections, Integer seen, Integer attachment, String pattern,
                List<Long> hits, String refusal, List<String[]> hidden) {
            this.collections = new JSONArray(collections).toString();
            this.size = collections.size();
            this.seen = seen;
            this.attachment = attachment;
            this.pattern = pattern;
            this.hits = hits;
            this.refusal = refusal;
            this.since = null;
            this.held = new HashSet<>(collections);
            this.hidden = hidden.isEmpty() ? null : new ArrayList<>(hidden);
            this.hiding = hidden.size();
        }

        /** The same query narrowed to unread mail, for the unread line. */
        Query unread() {
            return new Query(this, 0, since);
        }

        /** The same query down to {@code since} alone, null for all. */
        public Query since(String since) {
            return new Query(this, seen, since);
        }

        private Query(Query query, Integer seen, String since) {
            this.collections = query.collections;
            this.size = query.size;
            this.seen = seen;
            this.attachment = query.attachment;
            this.pattern = query.pattern;
            this.hits = query.hits;
            this.refusal = query.refusal;
            this.since = since;
            this.hidden = query.hidden;
            this.hiding = query.hiding;
            this.held = query.held;
        }

        /** Whether the query lets a collection through. */
        boolean holds(String collection) {
            return held.contains(collection);
        }

        /** The same query over the collections {@code keep} lets through. */
        Query only(java.util.function.Predicate<String> keep) {
            List<String> kept = new ArrayList<>();
            for (String collection : held) {
                if (keep.test(collection)) {
                    kept.add(collection);
                }
            }
            return new Query(kept, seen, attachment, pattern, hits, refusal, List.of()).since(since);
        }

        /** The canonical statement's values, with a page's cursor and size. */
        Map<String, Object> values(StoredMessage after, long limit) {
            Map<String, Object> values = new HashMap<>();
            values.put("collections", collections);
            values.put("seen", seen);
            values.put("attachment", attachment);
            values.put("pattern", pattern);
            values.put("hits", hits);
            values.put("since", since);
            values.put("after_key", after == null ? null : after.sortKey);
            values.put("after_seq", after == null ? null : after.seq);
            values.put("after_collection", after == null ? null : after.collection);
            values.put("limit", limit);
            return values;
        }
    }

    /**
     * The query a list asks for: collections {@code accepts}, chips, words.
     *
     * <p>By account and collection id; empty words for no search.
     */
    public Query query(
            BiPredicate<String, String> accepts,
            boolean unreadOnly,
            boolean attachmentsOnly,
            String words) {
        return query(accepts, unreadOnly ? Integer.valueOf(0) : null, attachmentsOnly, words);
    }

    /**
     * The query a list asks for, {@code seen} 0 for unread mail alone, 1 for
     * read mail alone, null for both.
     */
    public Query query(
            BiPredicate<String, String> accepts,
            Integer seen,
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
            Submission submission = outbox.submission(action);
            String from = submission.from();
            String sent = sentOf(from);
            if (sent != null && ids.contains(sent) && accepts.test(from, outbox.outboxOf(from))) {
                hidden.add(new String[] {sent, submission.messageId()});
            }
        }
        String trimmed = words == null ? "" : words.trim();
        if (trimmed.isEmpty()) {
            return new Query(ids, seen, attachmentsOnly ? 1 : null, null, hidden);
        }
        // NOTE: the index answers the words as a query of SEARCH §8, bodies
        // included; the sender and subject match stays beside it, so a word
        // inside another, a query the index refuses, or mail not indexed yet
        // still finds what it found before.
        List<Long> hits = null;
        String refusal = null;
        PimdirIndexDb index = store.index();
        JSONObject answer = null;
        try {
            answer = index == null ? null : index.hits(trimmed, "date");
        } catch (RuntimeException failed) {
            Log.w("pimalaya", "search index unanswered", failed);
        }
        if (answer != null && answer.has("refused")) {
            refusal = answer.optJSONObject("refused").optString("message");
        } else if (answer != null) {
            JSONArray found = answer.optJSONArray("hits");
            hits = new ArrayList<>();
            for (int at = 0; found != null && at < found.length(); at++) {
                hits.add(found.optJSONObject(at).optLong("seq"));
            }
        }
        return new Query(
                ids,
                seen,
                attachmentsOnly ? 1 : null,
                PimdirDb.likePattern(trimmed),
                hits,
                refusal,
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
                : PimdirSql.wrap("MAIL_COUNT", listed(query), Map.of());
        try (Cursor cursor = PimdirDb.query(items.readable(), bound)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    /** One day of the merged list and how many messages fall on it. */
    static final class Day {
        /** The day, {@code YYYY-MM-DD} on the wall clock; null if undated. */
        final String day;

        final long count;

        Day(String day, long count) {
            this.day = day;
            this.count = count;
        }
    }

    /**
     * The query's messages per day, newest day first, undated last.
     *
     * <p>{@code minutes} is the wall clock's offset from UTC, east positive
     * (STORAGE {@code count_mail_by_day}). A search groups {@code search_mail} as a
     * subquery by {@code count_mail_by_day}'s day expression.
     */
    List<Day> countByDay(Query query, long minutes) {
        List<Day> days = new ArrayList<>();
        if (query.size == 0) {
            return days;
        }
        PimdirSql.Bound bound;
        if (query.pattern == null && query.hidden == null) {
            Map<String, Object> values = query.values(null, -1);
            values.put("shift", minutes);
            bound = unjoined(PimdirSql.bind("COUNT_MAIL_BY_DAY", values), query.attachment);
        } else {
            bound = PimdirSql.wrap(
                    "MAIL_COUNT_BY_DAY", listed(query), PimdirSql.values("shift", minutes));
        }
        try (Cursor cursor = PimdirDb.query(items.readable(), bound)) {
            while (cursor.moveToNext()) {
                days.add(new Day(cursor.isNull(0) ? null : cursor.getString(0), cursor.getLong(1)));
            }
        }
        return days;
    }

    /**
     * The badge: unread mail of the query's collections down to its floor.
     *
     * <p>Chips and search aside, one window at a time.
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
        try (Cursor cursor = PimdirDb.query(items.readable(), bound)) {
            while (cursor.moveToNext()) {
                unread += cursor.getLong(1);
            }
        }
        return unread;
    }

    /** A range's weight: messages, known bytes, how many weigh unknown. */
    static final class Sum {
        final long count;

        /** The bytes of the messages whose size is known. */
        final long size;

        /** How many have no known size (Graph before reading, some servers). */
        final long unknown;

        Sum(long count, long size, long unknown) {
            this.count = count;
            this.size = size;
            this.unknown = unknown;
        }
    }

    /**
     * The weight of the query's messages in {@code [since, until)}, each once.
     *
     * <p>Null bounds are open ({@code sum_mail}). {@code held} 0: no body held,
     * what moving back downloads; 1: held, what moving later frees; null both.
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
        try (Cursor cursor = PimdirDb.query(items.readable(), bound)) {
            return cursor.moveToFirst()
                    ? new Sum(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
                    : new Sum(0, 0, 0);
        }
    }

    /**
     * Sort keys of the query's {@code count} newest dated below {@code floor}.
     *
     * <p>Newest first, undated out, under its chips: what {@link WidenStep}
     * moves a frontier by.
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
        PimdirSql.Bound bound = PimdirSql.bind("LIST_MAIL_PAGE_FILTERED", values);
        try (Cursor cursor = PimdirDb.query(items.readable(), bound)) {
            while (cursor.moveToNext()) {
                if (!cursor.isNull(5) && !cursor.getString(5).isEmpty()) {
                    dates.add(cursor.getString(5));
                }
            }
        }
        return dates;
    }

    /**
     * One page newest first: after {@code after}, else at {@code offset}.
     *
     * <p>The keyset statement seeks its bound on the sort key (STORAGE
     * keyset-reads-seek-their-bound), so a deep page
     * costs its own rows; an offset costs those it skips, so a fling reads from
     * its day ({@link #pageBelow}).
     */
    public List<StoredMessage> page(Query query, StoredMessage after, long offset, int limit) {
        if (query.size == 0 || limit <= 0) {
            return new ArrayList<>();
        }
        String statement = statement(query);
        PimdirSql.Bound bound;
        if (after != null || offset <= 0) {
            // NOTE: read past the page by the rows it hides, so the page
            // cut out of it is still a whole one.
            PimdirSql.Bound read =
                    PimdirSql.bind(statement, query.values(after, limit + query.hiding));
            bound = hiding(read, query, limit);
        } else {
            bound = skipping(listed(query), offset, limit);
        }
        return rows(bound, limit);
    }

    /**
     * One page, {@code skip} rows into those below {@code key} (null for top).
     *
     * <p>What a fling lands on from its day's start, costing that day's skipped
     * rows rather than every row above.
     */
    List<StoredMessage> pageBelow(Query query, String key, long skip, int limit) {
        if (query.size == 0 || limit <= 0) {
            return new ArrayList<>();
        }
        String statement = statement(query);
        Map<String, Object> values = query.values(null, skip + limit + query.hiding);
        if (key != null) {
            values.put("after_key", key);
            values.put("after_seq", Long.MIN_VALUE);
            values.put("after_collection", "");
        }
        PimdirSql.Bound read = hiding(PimdirSql.bind(statement, values), query, skip + limit);
        return rows(skipping(read, skip, limit), limit);
    }

    /** A read narrowed to {@code limit} rows past the first {@code skip}. */
    private static PimdirSql.Bound skipping(PimdirSql.Bound inner, long skip, int limit) {
        return PimdirSql.wrap(
                "MAIL_SKIPPING", inner, PimdirSql.values("page_limit", limit, "skip", skip));
    }

    /** The rows of a page read as a list shows them. */
    private List<StoredMessage> rows(PimdirSql.Bound bound, int limit) {
        List<StoredMessage> messages = new ArrayList<>(limit);
        Map<String, PimdirCollections.Stored> mailboxes = mailboxes();
        try (Cursor cursor = PimdirDb.query(items.readable(), bound)) {
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
     * A count read without the summary join, chip off.
     *
     * <p>The counts read {@code mail_summary} for the attachment chip alone, so
     * an empty relation of that name stands in (MailStoreTest pins this).
     */
    static PimdirSql.Bound unjoined(PimdirSql.Bound inner, Integer attachment) {
        if (attachment != null) {
            return inner;
        }
        return PimdirSql.wrap("MAIL_UNJOINED", inner, Map.of());
    }

    /**
     * One stored message as a list row ({@code get_mail_row}), or null if gone.
     *
     * <p>What a file's origin and a link open.
     */
    public StoredMessage message(String collection, String linkId) {
        Map<String, Object> key = new HashMap<>();
        key.put("collection", collection);
        key.put("link_id", linkId);
        PimdirSql.Bound bound = PimdirSql.bind("GET_MAIL_ROW", key);
        try (Cursor cursor = PimdirDb.query(items.readable(), bound)) {
            return cursor.moveToFirst() ? row(cursor, mailboxes()) : null;
        }
    }

    /** One mail page row as listed, null when its mailbox is unknown. */
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
                        cursor.isNull(8) ? "" : cursor.getString(8),
                        cursor.isNull(10) ? "" : cursor.getString(10),
                        cursor.isNull(9) ? "" : cursor.getString(9)),
                new StoredMessage.Listing(
                        mailboxLabel(mailbox),
                        cursor.isNull(5) ? "" : cursor.getString(5),
                        cursor.getLong(1),
                        flags(cursor.isNull(3) ? null : cursor.getString(3)),
                        cursor.isNull(13) ? null : cursor.getInt(13),
                        cursor.isNull(12) ? null : cursor.getLong(12),
                        !cursor.isNull(4) && cursor.getInt(6) >= PimdirItems.FULL));
    }

    /**
     * Some mailboxes' messages from {@code since} (null all), newest first.
     *
     * <p>With body held and weight, what {@link MailBodies} plans from; read
     * 500 at a time on {@code list_mail_page_filtered}'s keyset.
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
            try (Cursor cursor = PimdirDb.query(items.readable(), bound)) {
                while (cursor.moveToNext()) {
                    read++;
                    String sortKey = cursor.isNull(5) ? "" : cursor.getString(5);
                    rows.add(
                            new MailBodies.Row(
                                    cursor.getString(0),
                                    cursor.getString(2),
                                    sortKey,
                                    !cursor.isNull(4) && cursor.getInt(6) >= PimdirItems.FULL,
                                    cursor.isNull(12) ? null : cursor.getLong(12),
                                    cursor.isNull(13) ? null : cursor.getInt(13)));
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
     * The name a mailbox is shown under: its role's, localised, else its own.
     *
     * <p>Servers name roles their own way (INBOX, Gmail's SENT or SPAM).
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
     * The read a query pages: every message, the sender and subject search,
     * or that search with the index's hits beside it (SEARCH §8).
     */
    private static String statement(Query query) {
        if (query.pattern == null) {
            return "LIST_MAIL_PAGE_FILTERED";
        }
        return query.hits == null ? "SEARCH_MAIL" : "SEARCH_MAIL_INDEXED";
    }

    /**
     * Every row a query lets through, newest first, down to its floor.
     */
    private static PimdirSql.Bound listed(Query query) {
        String statement = statement(query);
        return hiding(PimdirSql.bind(statement, query.values(null, -1)), query, -1);
    }

    /**
     * A read with the query's hidden rows cut, at most {@code limit} (-1 all).
     *
     * <p>The read itself when none hidden; the order restated, a subquery's own
     * being no promise.
     */
    private static PimdirSql.Bound hiding(PimdirSql.Bound inner, Query query, long limit) {
        if (query.hidden == null) {
            return inner;
        }
        List<String> collections = new ArrayList<>();
        List<String> links = new ArrayList<>();
        for (String[] pair : query.hidden) {
            collections.add(pair[0]);
            links.add(pair[1]);
        }
        return PimdirSql.wrap(
                "MAIL_HIDING",
                inner,
                PimdirSql.values(
                        "hidden_collections", collections,
                        "hidden_links", links,
                        "hiding_limit", limit));
    }

    /**
     * Restates a message's summary from its body (pimdir STORAGE Annex A.1).
     *
     * <p>The body's attachment mark and size replace the listing's, its
     * invitation tied to its calendar item ({@code link_invitations_of}).
     * {@code summary} is {@code PimdirSql.derive}'s; null changes nothing.
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

    /** A mailbox's last complete listing's coverage (STORAGE section 4.3). */
    public static final class Coverage {
        /** The floor on the {@code Date} header, null for all mail. */
        public final String since;

        /** When the listing closed; null while none ever has. */
        public final String at;

        /** Whether a listing is under way, its first pass filling in. */
        public final boolean filling;

        /**
         * The floor a listing under way lists from, else null.
         *
         * <p>Also null for all mail: a widening's band, or a first chunk.
         */
        final String roundSince;

        Coverage(String since, String at, boolean filling, String roundSince) {
            this.since = since;
            this.at = at;
            this.filling = filling;
            this.roundSince = roundSince;
        }

        /**
         * How far down the list may show this mailbox, null where unlimited.
         *
         * <p>Its coverage floor above the bound, or its first pass's while it
         * fills; nothing for one never listed.
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
     * A mailbox's coverage: last complete scope, and any round under way.
     *
     * <p>What says "mail since", or that a search is not exhaustive yet.
     */
    public Coverage coverage(String collection) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        PimdirSql.Bound bound = PimdirSql.bind("LIST_COVERAGE", values);
        try (Cursor cursor = PimdirDb.query(items.readable(), bound)) {
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
     * How far down the merged list may show a query's collections, or null.
     *
     * <p>The most recent of their floors and account windows (a mailbox's own,
     * {@link #mailboxWindow}), so the list stays continuous by date. Whole
     * mailboxes bring no window, account-wide (Gmail) ones no floor.
     */
    String floorOf(Query query, List<Edge> edges) {
        List<String> floors = new ArrayList<>();
        for (Edge edge : edges) {
            if (!query.holds(edge.collection)) {
                continue;
            }
            if (!edge.allMail) {
                floors.add(edge.limit);
            }
            // NOTE: no frontier before the first sync sets one: its first
            // chunks show as they land, down to the merged floor, and the
            // frontier then hides what lies below the account's newest.
            String account = PimdirAccount.accountIdOf(edge.collection);
            if (!MailOffline.whole(context, edge.collection)
                    && MailWindow.held(context, account, edge.collection)) {
                floors.add(mailboxWindow(edge.collection));
            }
        }
        return MailWindow.latest(floors);
    }

    /** An account's window today, null for all mail. */
    public String windowOf(String accountEmail) {
        return MailWindow.since(context, accounts.idOf(accountEmail));
    }

    /**
     * A mailbox's window today, the older of its own and its account's.
     */
    String mailboxWindow(String collection) {
        return MailWindow.since(context, PimdirAccount.accountIdOf(collection), collection);
    }

    /**
     * The window an account's first sync leaves ({@link MailWindow#initial}).
     *
     * <p>Its inbox's floor, else (account-wide, no inbox) the most recent one.
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
     * Whether a Load more step of an account (or of {@code only}) lists
     * nothing: the store holds a step below the frontier above every stepped
     * mailbox's floor, or holds every one whole, as {@link WidenStep} reads
     * them. Gmail steps on its own, never waiting for a fill.
     */
    public boolean stepHeld(String accountEmail, String only) {
        String accountId = accounts.idOf(accountEmail);
        if (MailPolicy.keepsAllMail(context, accountId)) {
            return true;
        }
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);
        String outbox = outbox().outboxOf(accountEmail);
        Map<String, String> roles = roles();
        List<String> stepped = new ArrayList<>();
        for (PimdirCollections.Stored stored : collections.list(PimdirSummary.MAIL)) {
            if (!stored.accountEmail.equals(accountEmail)
                    || stored.id.equals(outbox)
                    || (only != null ? !only.equals(stored.id) : !filter.chosen(stored.id))
                    || MailOffline.whole(context, stored.id)
                    || !MailBodies.downloads(roles.get(stored.id), false)) {
                continue;
            }
            stepped.add(stored.id);
        }
        String window =
                only == null
                        ? MailWindow.since(context, accountId)
                        : MailWindow.since(context, accountId, only);
        Edge limiting = WidenStep.limiting(edges(), stepped);
        if (window == null || limiting == null) {
            return true;
        }
        if (!limiting.listed) {
            return false;
        }
        List<String> below =
                datesBelow(new Query(stepped, null, null, null, List.of()), window, MailStep.COUNT);
        return WidenStep.within(below, limiting.limit) >= MailStep.COUNT;
    }

    /**
     * The frontier an account listed mailbox by mailbox leaves its first sync
     * at: the {@link MailStep#COUNT}th newest {@code Date} of the mailboxes it
     * listed, once each holds its first chunk.
     *
     * <p>Exact: any of the account's newest is among its own mailbox's
     * newest, which that mailbox's first chunk holds. Junk, trash and a
     * mailbox kept whole stay out, as they do of a step; one never listed (a
     * refusal) holds nothing yet. All mail when they hold fewer and every one
     * is listed to its bound.
     */
    public MailStep.Outcome firstFrontier(List<String> listed) {
        Map<String, String> roles = roles();
        List<String> stepped = new ArrayList<>();
        boolean whole = true;
        for (String collection : listed) {
            if (MailOffline.whole(context, collection)
                    || !MailBodies.downloads(roles.get(collection), false)) {
                continue;
            }
            stepped.add(collection);
            Coverage coverage = coverage(collection);
            String bound =
                    MailScope.sinceOf(context, PimdirAccount.accountIdOf(collection), collection);
            if (coverage.at == null || MailScope.limits(coverage.since, bound)) {
                whole = false;
            }
        }
        List<StoredMessage> newest =
                page(new Query(stepped, null, null, null, List.of()), null, 0, MailStep.COUNT);
        String frontier = null;
        for (StoredMessage message : newest) {
            if (!message.sortKey.isEmpty()) {
                frontier = message.sortKey;
            }
        }
        boolean exhausted = whole && newest.size() < MailStep.COUNT;
        return new MailStep.Outcome(exhausted ? null : frontier, exhausted, newest.size());
    }

    /**
     * The window of an account set up before windows existed.
     *
     * <p>Its inbox's {@link MailEngine#FIRST_CHUNK}th newest stored date, else
     * {@link #firstWindow} where the inbox holds fewer.
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
        /** The account the mailbox belongs to. */
        public final String accountEmail;
        final String mailbox;
        /** Its collection id. */
        public final String collection;

        /** What the source says the mailbox is for (pimdir's role), or null. */
        final String role;

        /** Whether a round ever listed it, closed or under way. */
        final boolean listed;

        /** The floor the list stops at for it, null where it limits nothing. */
        final String limit;

        /**
         * Whether its account steps through All Mail (Gmail): neither chunked
         * nor filled, its steps bringing what it holds.
         */
        public final boolean allMail;

        Edge(
                String accountEmail,
                String mailbox,
                String collection,
                String role,
                boolean listed,
                String limit,
                boolean allMail) {
            this.accountEmail = accountEmail;
            this.mailbox = mailbox;
            this.collection = collection;
            this.role = role;
            this.listed = listed;
            this.limit = limit;
            this.allMail = allMail;
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
            boolean allMail =
                    wide.computeIfAbsent(
                            stored.accountEmail,
                            email -> MailPolicy.keepsAllMail(context, accounts.idOf(email)));
            Coverage coverage = coverage(stored.id);
            edges.add(
                    new Edge(
                            stored.accountEmail,
                            stored.name,
                            stored.id,
                            roles.get(stored.id),
                            coverage.at != null || coverage.filling,
                            coverage.limit(bound),
                            allMail));
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
     * Bounds an account's mail to {@code months} (0 all); answers how many.
     *
     * <p>Wider collects nothing. Narrower collects below its floor, since no
     * sync deletes outside its scope (pimdir SYNC section 5), and raises the
     * window ({@link MailWindow#raise}); the server keeps them.
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
     * Collects an account's mail dated before {@code before} (STORAGE 11.3).
     *
     * <p>Messages owing nothing to a server go with bindings, summary and body,
     * kept on the server; a widening refetches. Answers how many went.
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
            PimdirDb.exec(db, "RECOMPUTE_REFCOUNTS", Map.of());
            items.collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        items.unlinkCollected();
        return collected;
    }

    /**
     * Frees bodies of an account's mail before {@code until} (STORAGE 11.4).
     *
     * <p>Headers kept for search; whole mailboxes and needed bodies (conflict,
     * pending, local edit, unbound) stay. One transaction; answers how many.
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
            PimdirDb.changed(db, PimdirSql.bind("RELEASE_BASES_BEFORE", values));
            released = PimdirDb.changed(db, PimdirSql.bind("RELEASE_BEFORE", values));
            PimdirDb.exec(db, "RECOMPUTE_REFCOUNTS", Map.of());
            items.collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        items.unlinkCollected();
        return released;
    }

    /**
     * The markers one stored envelope carries, the base of a staged change.
     *
     * <p>Read back, not taken from the row, which carries only the three the
     * app renders: a toggle leaves the others alone.
     */
    Flags flagsOf(String collection, String id) {
        try (Cursor cursor =
                PimdirDb.query(
                        items.readable(),
                        "ITEM_FLAGS",
                        PimdirSql.values("collection", collection, "link_id", id))) {
            if (!cursor.moveToFirst() || cursor.isNull(0)) {
                return Flags.none();
            }
            try {
                return Flags.parse(cursor.getString(0));
            } catch (JSONException error) {
                // NOTE: an unreadable set has nothing to keep: the staged
                // set replaces it whole.
                return Flags.none();
            }
        }
    }

    /**
     * The message as the server sent it, or null with only the envelope.
     *
     * <p>A waiting message is nothing but a body, read from its queue row.
     */
    public byte[] source(StoredMessage message) {
        if (message.pending()) {
            return queue.body(message.objectHash);
        }
        return items.objectBytes(message.collection, message.id);
    }

    /**
     * The file one message's body is in, or null; never loaded whole here.
     */
    java.io.File sourceFile(StoredMessage message) {
        return message.pending() ? null : items.objectFile(message.collection, message.id);
    }

    /**
     * Files a fetched message's {@code file}, hashed {@code hash}.
     *
     * <p>Moved into the blob store, never read on this side.
     */
    void saveSource(String collection, String id, String hash, java.io.File file) {
        items.putObject(collection, id, hash, file);
    }

    /**
     * The mailboxes of the accounts in {@code emails}, with their roles.
     *
     * <p>Each account's outbox among them, ahead of every mailbox.
     */
    public List<PimdirCollections.Stored> loadMailboxes(List<String> emails) {
        List<PimdirCollections.Stored> mailboxes = new ArrayList<>();
        for (String email : emails) {
            mailboxes.add(
                    new PimdirCollections.Stored(
                            outbox.outboxOf(email), email, outbox.outboxName(), null, null));
        }
        for (PimdirCollections.Stored stored : collections.list(PimdirSummary.MAIL)) {
            if (emails.contains(stored.accountEmail)) {
                mailboxes.add(stored);
            }
        }
        return mailboxes;
    }

    /** The stored flag set, as a row reads its marks off it. */
    private static Flags flags(String flags) {
        try {
            return Flags.parse(flags);
        } catch (JSONException error) {
            // NOTE: an unreadable flag set reads as unset: an unread message
            // shows, and no icon claims a state the store cannot back.
            return Flags.none();
        }
    }
}
