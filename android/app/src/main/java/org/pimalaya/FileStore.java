package org.pimalaya;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.MessageBody;
import org.pimalaya.client.PimdirSql;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The file side of the pimdir store (STORAGE section 14.3): a message's
 * attachments as files holding no body, each referenced by its message, and
 * the folders a file is saved or imported into with a body of its own.
 *
 * <p>Both live in collections of the file kind that no source syncs, written
 * here directly with the canonical statements. An account's stand-ins sit in
 * one collection whose id is the account's namespace and a control character,
 * as the outbox's is: no server names a mailbox that way, so it never
 * collides, and it is found again from the account id alone, with no app
 * state to lose or keep in step. {@link #isAttachments} is the one place that
 * tells such a collection from a folder. A folder belongs to the device
 * ({@link LocalBook#ACCOUNT}) under a random id, so renaming it moves a label.
 */
final class FileStore {
    /** The collection name an account's stand-ins are filed under. */
    private static final String ATTACHMENTS = "\u0001attachments";

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

    FileStore(Context context, PimdirDb store) {
        this.context = context;
        this.store = store;
        this.items = new PimdirItems(store);
        this.collections = new PimdirCollections(store, context);
        this.accounts = new PimdirAccount(context);
    }

    /** One placement of a file: a stand-in, or a folder's copy with its body. */
    static final class StoredFile {
        /** The collection holding this placement. */
        final String collection;

        /** The file's key, {@code part:} for an attachment, {@code file:} for an import. */
        final String linkId;

        /** The file's public id, shared by its stand-in and its saved copies. */
        final long seq;

        /** The file name, empty when none is known. */
        final String name;

        /** The media type, null when none is stated. */
        final String mediaType;

        /** The size in octets, null when unknown. */
        final Long size;

        /** An attachment's IMAP section in its message, null for any other file. */
        final String part;

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

    /** Where a file came from: the message referencing it, and who sent it. */
    static final class Origin {
        /** The message's mailbox, a live placement of it. */
        final String collection;

        final String linkId;

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
    static String attachmentsOf(String accountId) {
        return PimdirAccount.collectionId(accountId, ATTACHMENTS);
    }

    /**
     * Whether a file collection holds an account's attachments rather than
     * being a folder: by the id scheme, until pimdir names the role.
     */
    static boolean isAttachments(String collection) {
        return collection.endsWith(PimdirAccount.SEPARATOR + ATTACHMENTS);
    }

    /** The key of one part of a message (Annex A.7): every writer names it alike. */
    static String partKey(String messageLinkId, String section) {
        return "part:" + messageLinkId + "#" + section;
    }

    /** What a file collection is called: a folder's name, or Attachments. */
    String label(PimdirCollections.Stored collection) {
        return isAttachments(collection.id)
                ? context.getString(R.string.files_attachments)
                : collection.name;
    }

    /**
     * Records the attachments of a message whose body was just stored: per
     * part the collection does not hold yet, a stand-in at {@code Meta} with
     * its summary and the {@code attachment} reference from the message, all
     * in one transaction, so no stand-in is ever left unreferenced. A part
     * already recorded is left as it is, so storing a body again writes
     * nothing.
     */
    void recordAttachments(
            String accountId, String messageLinkId, List<MessageBody.Attachment> attachments) {
        if (attachments.isEmpty()) {
            return;
        }
        String collection = attachmentsOf(accountId);
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            if (kindOf(db, collection) == null) {
                declare(db, collection, accountId, ATTACHMENTS.substring(1));
            }
            for (MessageBody.Attachment attachment : attachments) {
                String linkId = partKey(messageLinkId, attachment.part);
                if (items.knows(db, collection, linkId)) {
                    continue;
                }
                insert(db, collection, linkId, attachment.name, null, PimdirItems.META);
                PimdirSummary.write(
                        db,
                        collection,
                        linkId,
                        summary(
                                attachment.name,
                                attachment.mime.isEmpty() ? null : attachment.mime,
                                attachment.size,
                                attachment.part),
                        true);
                reference(db, messageLinkId, linkId);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** The files a message attaches, in document order, read from its references. */
    List<StoredFile> attachments(String accountId, String messageLinkId) {
        Map<String, Object> values = new HashMap<>();
        values.put("account", accountId);
        values.put("link_id", messageLinkId);
        PimdirSql.Bound bound = PimdirSql.bind("LIST_ATTACHMENTS", values);
        List<StoredFile> attachments = new ArrayList<>();
        try (Cursor cursor = MailStore.typed(items.readable(), bound.sql, bound.args)) {
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
            try (Cursor cursor = MailStore.typed(items.readable(), bound.sql, bound.args)) {
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
     * The message a file came from: the first that references it as an
     * attachment and still has a live placement, with its sender. Null for a
     * file no message references (an import).
     */
    Origin origin(String fileLinkId) {
        SQLiteDatabase db = items.readable();
        Map<String, Object> values = new HashMap<>();
        values.put("kind", PimdirSummary.FILE);
        values.put("link_id", fileLinkId);
        List<String> messages = new ArrayList<>();
        PimdirSql.Bound bound = PimdirSql.bind("REFERENCES_TO", values);
        try (Cursor cursor = MailStore.typed(db, bound.sql, bound.args)) {
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

    /** A live mail placement of one message, with its sender; null when none is left. */
    private static Origin placement(SQLiteDatabase db, String messageLinkId) {
        Map<String, Object> values = new HashMap<>();
        values.put("link_id", messageLinkId);
        PimdirSql.Bound bound = PimdirSql.bind("LIST_LINK_PLACEMENTS", values);
        try (Cursor cursor = MailStore.typed(db, bound.sql, bound.args)) {
            while (cursor.moveToNext()) {
                String collection = cursor.getString(0);
                if (!PimdirSummary.MAIL.equals(kindOf(db, collection))) {
                    continue;
                }
                Map<String, Object> key = new HashMap<>();
                key.put("collection", collection);
                key.put("seq", cursor.getLong(2));
                PimdirSql.Bound mail = PimdirSql.bind("GET_MAIL", key);
                try (Cursor row = MailStore.typed(db, mail.sql, mail.args)) {
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

    /** The bytes a placement holds, null when it holds none or its blob is gone. */
    byte[] saved(StoredFile file) {
        if (file.objectHash == null) {
            return null;
        }
        try {
            return new PimdirBlobs(store.blobs()).get(file.objectHash);
        } catch (IOException error) {
            return null;
        }
    }

    /** The device's folders, by name. */
    List<PimdirCollections.Stored> folders() {
        List<PimdirCollections.Stored> folders = new ArrayList<>();
        for (PimdirCollections.Stored collection : collections.list(PimdirSummary.FILE)) {
            if (LocalBook.ACCOUNT.equals(collection.accountEmail)
                    && !isAttachments(collection.id)) {
                folders.add(collection);
            }
        }
        return folders;
    }

    /** Every account's attachments collection, by account. */
    List<PimdirCollections.Stored> attachmentCollections() {
        List<PimdirCollections.Stored> held = new ArrayList<>();
        for (PimdirCollections.Stored collection : collections.list(PimdirSummary.FILE)) {
            if (isAttachments(collection.id)) {
                held.add(collection);
            }
        }
        return held;
    }

    /** Creates a folder on the device, answering its collection id. */
    String createFolder(String name) {
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

    /** Renames a folder, which moves its label and nothing else. */
    void renameFolder(String folder, String name) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", folder);
        values.put("account", accounts.idOf(LocalBook.ACCOUNT));
        values.put("name", name);
        exec(items.writable(), "SET_COLLECTION_NAME", values);
    }

    /**
     * Deletes a folder with every file in it; a body no other file holds
     * goes with it.
     */
    void deleteFolder(String folder) {
        dropCollection(folder);
    }

    /**
     * Saves a file into a folder: its bytes become a blob, shared with every
     * other file holding the same ones, and the folder places the same key
     * with that body, a stand-in staying (section 14.3). A folder already
     * holding it is left as it is.
     */
    void save(String folder, StoredFile file, byte[] bytes) {
        place(folder, file.linkId, file.name, file.mediaType, bytes);
    }

    /**
     * Imports bytes from outside the store into a folder, as a file of its
     * own keyed {@code file:} and 128 random bits (Annex A.7).
     */
    void importFile(String folder, String name, String mediaType, byte[] bytes) {
        place(folder, "file:" + random(), name, mediaType, bytes);
    }

    /**
     * Deletes a folder's copy of a file, releasing its body. A stand-in is
     * not deleted by hand: it goes with the last message referencing it.
     *
     * <p>Retained then purged in one transaction: the canonical delete is
     * the purge of a retained row, and a collection no source syncs keeps no
     * retention (section 14.3).
     */
    void delete(StoredFile file) {
        if (isAttachments(file.collection)) {
            return;
        }
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            Map<String, Object> key = new HashMap<>();
            key.put("collection", file.collection);
            key.put("link_id", file.linkId);
            key.put("source", null);
            exec(db, "RETAIN_ITEM", key);
            Map<String, Object> purge = new HashMap<>();
            purge.put("collection", file.collection);
            purge.put("seq", file.seq);
            PimdirSql.Bound bound = PimdirSql.bind("PURGE_ITEM", purge);
            List<String> released = new ArrayList<>();
            try (Cursor cursor = MailStore.typed(db, bound.sql, bound.args)) {
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
    }

    /**
     * Drops an account's stand-ins with their collection. Its messages' own
     * removal already took them one by one; this takes the collection.
     */
    void forget(String accountId) {
        dropCollection(attachmentsOf(accountId));
    }

    /** Places a key with a body in a folder, unless the folder holds it already. */
    private void place(
            String folder, String linkId, String name, String mediaType, byte[] bytes) {
        String hash = PimdirHash.of(bytes);
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            if (!items.knows(db, folder, linkId)) {
                items.storeObject(db, hash, bytes);
                insert(db, folder, linkId, name, hash, PimdirItems.FULL);
                PimdirSummary.write(
                        db, folder, linkId, summary(name, mediaType, bytes.length, null), true);
                items.adjustRefcount(db, null, hash);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Deletes a collection with everything under it, settling the bodies it held. */
    private void dropCollection(String collection) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            exec(db, "DELETE_COLLECTION", values);
            exec(db, "RECOMPUTE_REFCOUNTS", new HashMap<>());
            items.collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
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

    /** A collection's declared kind, null when the store holds no such collection. */
    private static String kindOf(SQLiteDatabase db, String collection) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        PimdirSql.Bound bound = PimdirSql.bind("LOAD_KIND", values);
        try (Cursor cursor = MailStore.typed(db, bound.sql, bound.args)) {
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
     * Places a file in a collection, sharing the public id its key already
     * has elsewhere; sorted on its name, A to Z (Annex A.7).
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

    /** Records the {@code attachment} reference from a message to one of its files. */
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
        try (Cursor cursor = MailStore.typed(db, bound.sql, bound.args)) {
            cursor.moveToFirst();
        }
    }

    /**
     * The {@code file_summary} row of one placement, as {@link
     * PimdirSummary#write} takes it: a null value is left out, which binds
     * NULL.
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
