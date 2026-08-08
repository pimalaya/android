package org.pimalaya;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.pimalaya.client.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * The mail side of the local store: envelope spines, one row per
 * message, keyed by account, mailbox and UID.
 *
 * <p>Its own database for the same reason {@link EventStore} is: mail is
 * read-only here and has no use for the contacts schema's merge model,
 * and both are due to be replaced by pimdir at P1-3.
 *
 * <p>Spines only, so a row is a few hundred bytes and a whole account's
 * list is one query. Bodies are not stored because nothing renders them
 * yet.
 */
final class MailStore extends SQLiteOpenHelper {
    private static final String DATABASE = "mail.db";
    private static final int VERSION = 1;

    MailStore(Context context) {
        super(context, DATABASE, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS message ("
                        + "account_email TEXT NOT NULL, "
                        + "mailbox TEXT NOT NULL, "
                        + "uid INTEGER NOT NULL, "
                        + "subject TEXT NOT NULL DEFAULT '', "
                        + "sender TEXT NOT NULL DEFAULT '', "
                        + "date TEXT NOT NULL DEFAULT '', "
                        + "stamp INTEGER NOT NULL DEFAULT 0, "
                        + "seen INTEGER NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY (account_email, mailbox, uid))");
        // The merged list is one descending scan over every account, so
        // the sort key is the only index that matters.
        db.execSQL("CREATE INDEX IF NOT EXISTS message_stamp ON message (stamp DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        // NOTE: a cache of a read-only sync, so an upgrade refetches.
        db.execSQL("DROP TABLE IF EXISTS message");
        onCreate(db);
    }

    /** Replaces an account's messages with what the walk just returned. */
    void replaceMessages(String accountEmail, List<Message> messages) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("message", "account_email = ?", new String[] {accountEmail});
            for (Message message : messages) {
                ContentValues values = new ContentValues();
                values.put("account_email", accountEmail);
                values.put("mailbox", message.mailbox);
                values.put("uid", message.uid);
                values.put("subject", message.subject);
                values.put("sender", message.from);
                values.put("date", message.date);
                values.put("stamp", MailDate.toStamp(message.date));
                values.put("seen", message.seen ? 1 : 0);
                db.insertWithOnConflict("message", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** One stored envelope, with the account it came from. */
    static final class StoredMessage {
        final String accountEmail;
        final String mailbox;
        final long uid;
        final String subject;
        final String from;
        final long stamp;
        final boolean seen;

        StoredMessage(
                String accountEmail,
                String mailbox,
                long uid,
                String subject,
                String from,
                long stamp,
                boolean seen) {
            this.accountEmail = accountEmail;
            this.mailbox = mailbox;
            this.uid = uid;
            this.subject = subject;
            this.from = from;
            this.stamp = stamp;
            this.seen = seen;
        }
    }

    /** Every account's messages, newest first: the merged list itself. */
    List<StoredMessage> loadMerged(int limit) {
        List<StoredMessage> messages = new ArrayList<>();
        try (Cursor cursor =
                getReadableDatabase()
                        .query(
                                "message",
                                null,
                                null,
                                null,
                                null,
                                null,
                                "stamp DESC",
                                String.valueOf(limit))) {
            while (cursor.moveToNext()) {
                messages.add(
                        new StoredMessage(
                                cursor.getString(cursor.getColumnIndexOrThrow("account_email")),
                                cursor.getString(cursor.getColumnIndexOrThrow("mailbox")),
                                cursor.getLong(cursor.getColumnIndexOrThrow("uid")),
                                cursor.getString(cursor.getColumnIndexOrThrow("subject")),
                                cursor.getString(cursor.getColumnIndexOrThrow("sender")),
                                cursor.getLong(cursor.getColumnIndexOrThrow("stamp")),
                                cursor.getInt(cursor.getColumnIndexOrThrow("seen")) != 0));
            }
        }
        return messages;
    }

    /** The distinct mailbox names seen, for the filter's collection axis. */
    List<String> loadMailboxes() {
        List<String> mailboxes = new ArrayList<>();
        try (Cursor cursor =
                getReadableDatabase()
                        .query(
                                true,
                                "message",
                                new String[] {"mailbox"},
                                null,
                                null,
                                null,
                                null,
                                "mailbox COLLATE NOCASE",
                                null)) {
            while (cursor.moveToNext()) {
                mailboxes.add(cursor.getString(0));
            }
        }
        return mailboxes;
    }
}
