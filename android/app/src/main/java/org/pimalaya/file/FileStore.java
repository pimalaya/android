package org.pimalaya.file;

import android.content.Context;
import android.database.Cursor;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.R;
import org.pimalaya.account.LocalBook;
import org.pimalaya.client.MessageBody;
import org.pimalaya.client.PimdirSql;
import org.pimalaya.pimdir.ItemLinks;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirHash;
import org.pimalaya.pimdir.PimdirItems;
import org.pimalaya.pimdir.PimdirSummary;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The file side of the pimdir store (STORAGE section 14.3).
 *
 * <p>Attachments are body-less stand-ins their message references; folders hold
 * bodies. An account's stand-ins share one {@code attachments} collection, id
 * the account namespace plus a control character: no mailbox collides.
 */
public final class FileStore {
    /** The prefix of a folder's collection name, before its random part. */
    private static final String FOLDER = "folder:";

    /** How many files a read of a folder takes at a time. */
    private static final int PAGE = 500;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Context context;
    private final PimdirDb store;
    private final PimdirItems items;
    private final PimdirCollections collections;
    private final PimdirAccount accounts;

    /** The file store over the shared pimdir store. */
    public FileStore(Context context, PimdirDb store) {
        this.context = context;
        this.store = store;
        this.items = new PimdirItems(store);
        this.collections = new PimdirCollections(store, context);
        this.accounts = new PimdirAccount(context);
    }

    /** A file's placement: a stand-in, or a folder's copy with its body. */
    public static final class StoredFile {
        /** The collection holding this placement. */
        final String collection;

        /** Its key: {@code part:} attachment, {@code file:} import. */
        public final String linkId;

        /** Its public id, shared by its stand-in and its saved copies. */
        public final long seq;

        /** The file name, empty when none is known. */
        public final String name;

        /** The media type, null when none is stated. */
        final String mediaType;

        /** The size in octets, null when unknown. */
        public final Long size;

        /** An attachment's IMAP section; null for any other file. */
        public final String part;

        /** The body a placement of the file holds, null while none does. */
        final String objectHash;

        StoredFile(
                String collection, String linkId, long seq, String name, String mediaType,
                Long size, String part, String objectHash) {
            this.collection = collection;
            this.linkId = linkId;
            this.seq = seq;
            this.name = name;
            this.mediaType = mediaType;
            this.size = size;
            this.part = part;
            this.objectHash = objectHash;
        }
    }

    /** The links endpoint of a file. */
    public static ItemLinks.Endpoint endpointOf(StoredFile file) {
        return ItemLinks.of(PimdirSummary.FILE, file.linkId);
    }

    /** Where a file came from: the message referencing it, and who sent it. */
    public static final class Origin {
        /** The message's mailbox, a live placement of it. */
        public final String collection;

        /** The message's link id. */
        public final String linkId;

        /** The sender's address, empty when unknown. */
        final String sender;

        /** The sender's name, empty when none. */
        final String senderName;

        Origin(String collection, String linkId, String sender, String senderName) {
            this.collection = collection;
            this.linkId = linkId;
            this.sender = sender;
            this.senderName = senderName;
        }
    }

    /** The collection one account's stand-ins are filed in. */
    public static String attachmentsOf(String accountId) {
        return PimdirAccount.collectionId(accountId, PimdirCollections.ATTACHMENTS);
    }

    /** Whether a file collection is an account's attachments, not a folder. */
    static boolean isAttachments(PimdirCollections.Stored collection) {
        return PimdirCollections.ATTACHMENTS_ROLE.equals(collection.role);
    }

    /**
     * A message part's key (Annex A.7), named alike by every writer.
     *
     * <p>Null for a message under a writer-derived key, which names no identity
     * a part could be keyed on (section 9.1).
     */
    public static String partKey(String messageLinkId, String section) {
        return ItemLinks.derived(messageLinkId) ? null : "part:" + messageLinkId + "#" + section;
    }

    /** What a file collection is called: a folder's name, or Attachments. */
    public String label(PimdirCollections.Stored collection) {
        return isAttachments(collection)
                ? context.getString(R.string.files_attachments)
                : collection.name;
    }

    /**
     * Records the attachments of a message whose body was just stored.
     *
     * <p>Per new part, a stand-in at {@code Meta} and the message's
     * {@code attachment} reference, in one transaction: no stand-in is left
     * unreferenced. A removed reference of a known part comes back.
     */
    public void recordAttachments(
            String accountId, String messageLinkId, List<MessageBody.Attachment> attachments) {
        // NOTE: a message under a derived key gets no stand-in (section 14.3).
        if (attachments.isEmpty() || ItemLinks.derived(messageLinkId)) {
            return;
        }
        String collection = attachmentsOf(accountId);
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            if (kindOf(db, collection) == null) {
                declare(db, collection, accountId, PimdirCollections.ATTACHMENTS.substring(1));
                PimdirDb.setRole(db, collection);
            }
            for (MessageBody.Attachment attachment : attachments) {
                String linkId = partKey(messageLinkId, attachment.part());
                if (items.knows(db, collection, linkId)) {
                    // NOTE: a reference unlinked by hand comes back with the
                    // body, the stand-in being the attachment.
                    reference(db, messageLinkId, linkId);
                    continue;
                }
                insert(db, collection, linkId, attachment.name(), null, PimdirItems.META);
                PimdirSummary.write(
                        db,
                        collection,
                        linkId,
                        summary(
                                attachment.name(),
                                attachment.mime().isEmpty() ? null : attachment.mime(),
                                attachment.size(),
                                attachment.part()),
                        true);
                reference(db, messageLinkId, linkId);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** A message's attached files, in document order, from its references. */
    public List<StoredFile> attachments(String accountId, String messageLinkId) {
        Map<String, Object> values = new HashMap<>();
        values.put("account", accountId);
        values.put("link_id", messageLinkId);
        PimdirSql.Bound bound = PimdirSql.bind("LIST_ATTACHMENTS", values);
        List<StoredFile> attachments = new ArrayList<>();
        try (Cursor cursor = PimdirDb.typed(items.readable(), bound.sql, bound.args)) {
            while (cursor.moveToNext()) {
                attachments.add(
                        new StoredFile(
                                cursor.getString(1),
                                cursor.getString(0),
                                cursor.getLong(2),
                                cursor.isNull(3) ? "" : cursor.getString(3),
                                cursor.isNull(4) ? null : cursor.getString(4),
                                cursor.isNull(5) ? null : cursor.getLong(5),
                                cursor.isNull(6) ? null : cursor.getString(6),
                                cursor.isNull(7) ? null : cursor.getString(7)));
            }
        }
        return attachments;
    }

    /** One stand-in with the message attaching it. */
    static final class Attached {
        final StoredFile file;
        final Origin origin;

        Attached(StoredFile file, Origin origin) {
            this.file = file;
            this.origin = origin;
        }
    }

    /**
     * An attachments collection's files with their message, newest first.
     *
     * <p>{@code list_attachments_by_account}, read a page at a time.
     */
    List<Attached> attachmentsIn(String collection) {
        SQLiteDatabase db = items.readable();
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        values.put("account", text(db, "LOAD_ACCOUNT", values));
        values.put("after_key", null);
        values.put("after_seq", null);
        values.put("limit", PAGE);
        List<Attached> attached = new ArrayList<>();
        while (true) {
            PimdirSql.Bound bound = PimdirSql.bind("LIST_ATTACHMENTS_BY_ACCOUNT", values);
            int read = 0;
            try (Cursor cursor = PimdirDb.typed(db, bound.sql, bound.args)) {
                while (cursor.moveToNext()) {
                    read++;
                    values.put("after_key", cursor.getString(10));
                    values.put("after_seq", cursor.getLong(1));
                    if (!collection.equals(cursor.getString(0))) {
                        continue;
                    }
                    attached.add(
                            new Attached(
                                    new StoredFile(
                                            cursor.getString(0),
                                            cursor.getString(2),
                                            cursor.getLong(1),
                                            cursor.isNull(3) ? "" : cursor.getString(3),
                                            cursor.isNull(4) ? null : cursor.getString(4),
                                            cursor.isNull(5) ? null : cursor.getLong(5),
                                            cursor.isNull(6) ? null : cursor.getString(6),
                                            null),
                                    new Origin(
                                            cursor.getString(8),
                                            cursor.getString(7),
                                            cursor.isNull(11) ? "" : cursor.getString(11),
                                            cursor.isNull(12) ? "" : cursor.getString(12))));
                }
            }
            if (read < PAGE) {
                return attached;
            }
        }
    }

    /** The one text column a statement answers, null for no row or NULL. */
    private static String text(SQLiteDatabase db, String name, Map<String, Object> values) {
        PimdirSql.Bound bound = PimdirSql.bind(name, values);
        try (Cursor cursor = PimdirDb.typed(db, bound.sql, bound.args)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    /** Every live file of one collection, A to Z, read a page at a time. */
    List<StoredFile> files(String collection) {
        List<StoredFile> files = new ArrayList<>();
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        values.put("after_key", "");
        values.put("after_seq", -1);
        values.put("limit", PAGE);
        while (true) {
            PimdirSql.Bound bound = PimdirSql.bind("LIST_FILES_PAGE_ASC", values);
            int read = 0;
            try (Cursor cursor = PimdirDb.typed(items.readable(), bound.sql, bound.args)) {
                while (cursor.moveToNext()) {
                    read++;
                    files.add(
                            new StoredFile(
                                    collection,
                                    cursor.getString(1),
                                    cursor.getLong(0),
                                    cursor.isNull(6) ? "" : cursor.getString(6),
                                    cursor.isNull(7) ? null : cursor.getString(7),
                                    cursor.isNull(8) ? null : cursor.getLong(8),
                                    cursor.isNull(9) ? null : cursor.getString(9),
                                    cursor.isNull(3) ? null : cursor.getString(3)));
                    values.put("after_key", cursor.isNull(4) ? "" : cursor.getString(4));
                    values.put("after_seq", cursor.getLong(0));
                }
            }
            if (read < PAGE) {
                return files;
            }
        }
    }

    /**
     * The message a file came from, with its sender; null for an import.
     *
     * <p>The first referencing it as an attachment that still has a live
     * placement.
     */
    Origin origin(String fileLinkId) {
        SQLiteDatabase db = items.readable();
        Map<String, Object> values = new HashMap<>();
        values.put("kind", PimdirSummary.FILE);
        values.put("link_id", fileLinkId);
        List<String> messages = new ArrayList<>();
        PimdirSql.Bound bound = PimdirSql.bind("REFERENCES_TO", values);
        try (Cursor cursor = PimdirDb.typed(db, bound.sql, bound.args)) {
            while (cursor.moveToNext()) {
                if (PimdirSummary.MAIL.equals(cursor.getString(0))
                        && "attachment".equals(cursor.getString(4))) {
                    messages.add(cursor.getString(1));
                }
            }
        }
        for (String message : messages) {
            Origin origin = placement(db, message);
            if (origin != null) {
                return origin;
            }
        }
        return null;
    }

    /** A live mail placement of a message, with its sender; else null. */
    private static Origin placement(SQLiteDatabase db, String messageLinkId) {
        Map<String, Object> values = new HashMap<>();
        values.put("link_id", messageLinkId);
        PimdirSql.Bound bound = PimdirSql.bind("LIST_LINK_PLACEMENTS", values);
        try (Cursor cursor = PimdirDb.typed(db, bound.sql, bound.args)) {
            while (cursor.moveToNext()) {
                String collection = cursor.getString(0);
                if (!PimdirSummary.MAIL.equals(kindOf(db, collection))) {
                    continue;
                }
                Map<String, Object> key = new HashMap<>();
                key.put("collection", collection);
                key.put("seq", cursor.getLong(2));
                PimdirSql.Bound mail = PimdirSql.bind("GET_MAIL", key);
                try (Cursor row = PimdirDb.typed(db, mail.sql, mail.args)) {
                    if (row.moveToFirst()) {
                        return new Origin(
                                collection,
                                messageLinkId,
                                row.isNull(9) ? "" : row.getString(9),
                                row.isNull(10) ? "" : row.getString(10));
                    }
                }
            }
        }
        return null;
    }

    /**
     * The file a placement's body is stored in.
     *
     * <p>Null when it holds none or its blob is gone.
     */
    public java.io.File saved(StoredFile file) {
        return items.blobFile(file.objectHash);
    }

    /** The device's folders, by name. */
    public List<PimdirCollections.Stored> folders() {
        List<PimdirCollections.Stored> folders = new ArrayList<>();
        for (PimdirCollections.Stored collection : collections.list(PimdirSummary.FILE)) {
            if (LocalBook.ACCOUNT.equals(collection.accountEmail)
                    && !isAttachments(collection)) {
                folders.add(collection);
            }
        }
        return folders;
    }

    /** Every account's attachments collection, by account. */
    public List<PimdirCollections.Stored> attachmentCollections() {
        List<PimdirCollections.Stored> held = new ArrayList<>();
        for (PimdirCollections.Stored collection : collections.list(PimdirSummary.FILE)) {
            if (isAttachments(collection)) {
                held.add(collection);
            }
        }
        return held;
    }

    /** Whether a folder other than {@code except} already goes by a name. */
    private boolean taken(String name, String except) {
        for (PimdirCollections.Stored folder : folders()) {
            if (folder.name.equals(name) && !folder.id.equals(except)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates a device folder, answering its collection id.
     *
     * <p>Null when a folder already goes by the name: one card stands for each
     * name.
     */
    String createFolder(String name) {
        if (taken(name, null)) {
            return null;
        }
        String account = accounts.idOf(LocalBook.ACCOUNT);
        String id = PimdirAccount.collectionId(account, FOLDER + random());
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            declare(db, id, account, name);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return id;
    }

    /** Renames a folder, moving its label only; false if the name is taken. */
    boolean renameFolder(String folder, String name) {
        if (taken(name, folder)) {
            return false;
        }
        Map<String, Object> values = new HashMap<>();
        values.put("collection", folder);
        values.put("account", accounts.idOf(LocalBook.ACCOUNT));
        values.put("name", name);
        exec(items.writable(), "SET_COLLECTION_NAME", values);
        return true;
    }

    /** Deletes a folder and its files; a body no other file holds goes too. */
    void deleteFolder(String folder) {
        dropCollection(folder);
    }

    /**
     * Saves a file into a folder; false when the folder holds it already.
     *
     * <p>Its bytes, read from {@code source}, become a blob shared with every
     * file holding the same ones; the folder places the same key with that
     * body, a stand-in staying (section 14.3).
     */
    boolean save(String folder, StoredFile file, java.io.File source) throws IOException {
        return place(folder, file.linkId, file.name, file.mediaType, source);
    }

    /**
     * Imports a file from outside the store into a folder.
     *
     * <p>A file of its own, keyed {@code file:} and 128 random bits (Annex
     * A.7).
     */
    void importFile(String folder, String name, String mediaType, java.io.File source)
            throws IOException {
        place(folder, "file:" + random(), name, mediaType, source);
    }

    /**
     * Deletes a folder's copy of a file, releasing its body.
     *
     * <p>By {@code delete_unbound_item}. A stand-in is never deleted by hand:
     * it goes with the last message referencing it.
     */
    void delete(StoredFile file) {
        if (file.objectHash == null) {
            return;
        }
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            Map<String, Object> key = new HashMap<>();
            key.put("collection", file.collection);
            key.put("link_id", file.linkId);
            PimdirSql.Bound bound = PimdirSql.bind("DELETE_UNBOUND_ITEM", key);
            List<String> released = new ArrayList<>();
            try (Cursor cursor = PimdirDb.typed(db, bound.sql, bound.args)) {
                while (cursor.moveToNext()) {
                    for (int column = 0; column < 2; column++) {
                        if (!cursor.isNull(column)) {
                            released.add(cursor.getString(column));
                        }
                    }
                }
            }
            for (String hash : released) {
                items.adjustRefcount(db, hash, null);
            }
            items.collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        items.unlinkCollected();
    }

    /**
     * Drops an account's stand-ins collection.
     *
     * <p>Its messages' removal already took the stand-ins one by one.
     */
    public void forget(String accountId) {
        dropCollection(attachmentsOf(accountId));
        OpenedCopies.clear(context);
    }

    /**
     * Places a key with a body in a folder, unless the folder holds it.
     *
     * <p>The blob is hashed and copied from the file a buffer at a time.
     */
    private boolean place(
            String folder, String linkId, String name, String mediaType, java.io.File source)
            throws IOException {
        String hash = PimdirHash.of(source);
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            boolean placed = !items.knows(db, folder, linkId);
            if (placed) {
                items.storeObject(db, hash, source);
                insert(db, folder, linkId, name, hash, PimdirItems.FULL);
                PimdirSummary.write(
                        db, folder, linkId, summary(name, mediaType, source.length(), null), true);
                items.adjustRefcount(db, null, hash);
            }
            db.setTransactionSuccessful();
            return placed;
        } finally {
            db.endTransaction();
        }
    }

    /** Deletes a collection and everything under it, settling its bodies. */
    private void dropCollection(String collection) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            boolean held;
            PimdirSql.Bound holds = PimdirSql.bind("COLLECTION_HOLDS_OBJECTS", values);
            try (Cursor cursor = PimdirDb.typed(db, holds.sql, holds.args)) {
                held = cursor.moveToFirst() && cursor.getInt(0) == 1;
            }
            exec(db, "DELETE_COLLECTION", values);
            // NOTE: a collection pointing at no object drops no pin, so the
            // store-wide recount is spared.
            if (held) {
                exec(db, "RECOMPUTE_REFCOUNTS", new HashMap<>());
                items.collectGarbage(db);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        items.unlinkCollected();
    }

    /** 128 random bits as 32 lowercase hexadecimal digits. */
    private static String random() {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        StringBuilder hex = new StringBuilder(32);
        for (byte octet : random) {
            hex.append(String.format(Locale.ROOT, "%02x", octet));
        }
        return hex.toString();
    }

    /** A collection's declared kind; null when the store lacks it. */
    private static String kindOf(SQLiteDatabase db, String collection) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        PimdirSql.Bound bound = PimdirSql.bind("LOAD_KIND", values);
        try (Cursor cursor = PimdirDb.typed(db, bound.sql, bound.args)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    /** Declares a file collection of an account, under a name. */
    private static void declare(SQLiteDatabase db, String id, String account, String name) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", id);
        values.put("account", account);
        values.put("kind", PimdirSummary.FILE);
        exec(db, "SET_COLLECTION_KIND", values);
        values.put("name", name);
        exec(db, "SET_COLLECTION_NAME", values);
    }

    /**
     * Places a file in a collection, sorted by name A to Z (Annex A.7).
     *
     * <p>It shares the public id its key already has elsewhere.
     */
    private void insert(
            SQLiteDatabase db, String collection, String linkId, String name, String hash,
            int level) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        values.put("link_id", linkId);
        values.put("seq", items.seqFor(db, linkId));
        values.put("flags", "[]");
        values.put("object_hash", hash);
        values.put("sort_key", name.toLowerCase(Locale.ROOT).trim());
        values.put("level", level);
        values.put("deleted", 0);
        values.put("conflicted", 0);
        exec(db, "INSERT_ITEM", values);
    }

    /** Records a message's {@code attachment} reference to one of its files. */
    private static void reference(SQLiteDatabase db, String messageLinkId, String fileLinkId) {
        Map<String, Object> values = new HashMap<>();
        values.put("from_kind", PimdirSummary.MAIL);
        values.put("from_link_id", messageLinkId);
        values.put("to_kind", PimdirSummary.FILE);
        values.put("to_link_id", fileLinkId);
        values.put("role", "attachment");
        values.put("origin", "auto");
        PimdirSql.Bound bound = PimdirSql.bind("ADD_REFERENCE", values);
        // NOTE: a query, not an exec: the statement answers the row it
        // recorded, and Android refuses a statement returning rows to execSQL.
        try (Cursor cursor = PimdirDb.typed(db, bound.sql, bound.args)) {
            cursor.moveToFirst();
        }
    }

    /**
     * One placement's {@code file_summary} row.
     *
     * <p>As {@link PimdirSummary#write} takes it: a null value is left out,
     * which binds NULL.
     */
    private static JSONObject summary(String name, String mediaType, long size, String part) {
        try {
            JSONObject row = new JSONObject();
            row.put("name", name);
            row.put("media_type", mediaType);
            row.put("size", size);
            row.put("part", part);
            return new JSONObject().put("file", row);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void exec(SQLiteDatabase db, String name, Map<String, Object> values) {
        PimdirSql.Bound bound = PimdirSql.bind(name, values);
        db.execSQL(bound.sql, bound.args);
    }
}
