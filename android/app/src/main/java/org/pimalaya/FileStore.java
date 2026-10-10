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
 * the folders a part is saved into with a body of its own.
 *
 * <p>Both live in collections of the file kind that no source syncs, written
 * here directly with the canonical statements. An account's stand-ins sit in
 * one collection whose id is the account's namespace and a control character,
 * as the outbox's is: no server names a mailbox that way, so it never
 * collides, and it is found again from the account id alone, with no app
 * state to lose or keep in step. A folder belongs to the device
 * ({@link LocalBook#ACCOUNT}) under a random id, so renaming it moves a label.
 */
final class FileStore {
    /** The collection name an account's stand-ins are filed under. */
    private static final String ATTACHMENTS = "\u0001attachments";

    /** The prefix of a folder's collection name, before its random part. */
    private static final String FOLDER = "folder:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PimdirDb store;
    private final PimdirItems items;
    private final PimdirCollections collections;
    private final PimdirAccount accounts;

    FileStore(Context context, PimdirDb store) {
        this.store = store;
        this.items = new PimdirItems(store);
        this.collections = new PimdirCollections(store, context);
        this.accounts = new PimdirAccount(context);
    }

    /** One file a message attaches, as {@code list_attachments} reads it. */
    static final class Attachment {
        /** The file's key, {@code part:<message link id>#<section>}. */
        final String linkId;

        /** The file's public id, shared by its stand-in and its saved copies. */
        final long seq;

        /** The file name, empty when the part names none. */
        final String name;

        /** The media type, null when the part states none. */
        final String mediaType;

        /** The decoded size in octets, null when unknown. */
        final Long size;

        /** The IMAP section of the part in its message. */
        final String part;

        /** The body a saved copy holds, null while none was saved. */
        final String objectHash;

        Attachment(
                String linkId, long seq, String name, String mediaType, Long size, String part,
                String objectHash) {
            this.linkId = linkId;
            this.seq = seq;
            this.name = name;
            this.mediaType = mediaType;
            this.size = size;
            this.part = part;
            this.objectHash = objectHash;
        }
    }

    /** The collection one account's stand-ins are filed in. */
    static String attachmentsOf(String accountId) {
        return PimdirAccount.collectionId(accountId, ATTACHMENTS);
    }

    /** The key of one part of a message (Annex A.7): every writer names it alike. */
    static String partKey(String messageLinkId, String section) {
        return "part:" + messageLinkId + "#" + section;
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
    List<Attachment> attachments(String accountId, String messageLinkId) {
        Map<String, Object> values = new HashMap<>();
        values.put("account", accountId);
        values.put("link_id", messageLinkId);
        PimdirSql.Bound bound = PimdirSql.bind("LIST_ATTACHMENTS", values);
        List<Attachment> attachments = new ArrayList<>();
        try (Cursor cursor = MailStore.typed(items.readable(), bound.sql, bound.args)) {
            while (cursor.moveToNext()) {
                attachments.add(
                        new Attachment(
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

    /** The bytes a saved copy holds, null when its blob is gone. */
    byte[] saved(Attachment attachment) {
        try {
            return new PimdirBlobs(store.blobs()).get(attachment.objectHash);
        } catch (IOException error) {
            return null;
        }
    }

    /** The device's folders, by name. */
    List<PimdirCollections.Stored> folders() {
        List<PimdirCollections.Stored> folders = new ArrayList<>();
        for (PimdirCollections.Stored collection : collections.list(PimdirSummary.FILE)) {
            if (LocalBook.ACCOUNT.equals(collection.accountEmail)) {
                folders.add(collection);
            }
        }
        return folders;
    }

    /** Creates a folder on the device, answering its collection id. */
    String createFolder(String name) {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        StringBuilder hex = new StringBuilder(FOLDER);
        for (byte octet : random) {
            hex.append(String.format(Locale.ROOT, "%02x", octet));
        }
        String account = accounts.idOf(LocalBook.ACCOUNT);
        String id = PimdirAccount.collectionId(account, hex.toString());
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

    /**
     * Saves one attachment into a folder: its bytes become a blob, shared
     * with every other file holding the same ones, and the folder places
     * the same key with that body, the stand-in staying (section 14.3). A
     * folder already holding it is left as it is.
     */
    void save(String folder, Attachment attachment, byte[] bytes) {
        String hash = PimdirHash.of(bytes);
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            if (!items.knows(db, folder, attachment.linkId)) {
                items.storeObject(db, hash, bytes);
                insert(db, folder, attachment.linkId, attachment.name, hash, PimdirItems.FULL);
                PimdirSummary.write(
                        db,
                        folder,
                        attachment.linkId,
                        summary(attachment.name, attachment.mediaType, bytes.length, null),
                        true);
                items.adjustRefcount(db, null, hash);
            }
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
        Map<String, Object> values = new HashMap<>();
        values.put("collection", attachmentsOf(accountId));
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            exec(db, "DELETE_COLLECTION", values);
            exec(db, "RECOMPUTE_REFCOUNTS", new HashMap<>());
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
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
