package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.phone.Accounts;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** The contacts spoke over a raw contacts provider, as far as a read goes. */
@RunWith(RobolectricTestRunner.class)
public class PhoneRemoteTest {
    private static final String URL = "https://dav.example.com/books/one/";
    private static final String SPOKE = PimdirStorage.phoneCollection(URL);

    private Context context;
    private Contacts provider;

    /** Raw contacts and their data rows in memory, selections as SQL. */
    public static class Contacts extends ContentProvider {
        SQLiteDatabase db;

        @Override
        public boolean onCreate() {
            db = SQLiteDatabase.create(null);
            db.execSQL("CREATE TABLE raw_contacts (_id INTEGER PRIMARY KEY, sourceid TEXT,"
                    + " version INTEGER, deleted INTEGER DEFAULT 0, sync1 TEXT,"
                    + " dirty INTEGER DEFAULT 0)");
            db.execSQL("CREATE TABLE data (_id INTEGER PRIMARY KEY, raw_contact_id INTEGER,"
                    + " mimetype TEXT, data1 TEXT, data2 TEXT, data3 TEXT, data4 TEXT,"
                    + " data5 TEXT, data6 TEXT, data7 TEXT, data8 TEXT, data9 TEXT,"
                    + " data10 TEXT, is_primary INTEGER)");
            return true;
        }

        private static String table(Uri uri) {
            return uri.getPathSegments().get(0);
        }

        private static String selection(Uri uri, String selection) {
            if (uri.getPathSegments().size() < 2) {
                return selection;
            }
            String id = "_id = " + ContentUris.parseId(uri);
            return selection == null ? id : "(" + selection + ") AND " + id;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args,
                String sort) {
            return db.query(table(uri), projection, selection(uri, selection), args, null, null,
                    sort);
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return ContentUris.withAppendedId(uri, db.insert(table(uri), null, values));
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return db.update(table(uri), values, selection(uri, selection), args);
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            return db.delete(table(uri), selection(uri, selection), args);
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        provider = Robolectric.setupContentProvider(Contacts.class, ContactsContract.AUTHORITY);
        Bundle data = new Bundle();
        data.putString("url", URL);
        AccountManager.get(context)
                .addAccountExplicitly(new Account("One", Accounts.TYPE), null, data);
        assertNotNull(Accounts.findByUrl(context, URL));
    }

    /** A raw contact the Contacts app edited: dirty at {@code version}. */
    private long edited(String handle, int version, String name) {
        ContentValues raw = new ContentValues();
        raw.put(RawContacts.SOURCE_ID, handle);
        raw.put(RawContacts.VERSION, version);
        raw.put(RawContacts.SYNC1, "stamped-before");
        raw.put(RawContacts.DIRTY, 1);
        long id = provider.db.insert("raw_contacts", null, raw);
        ContentValues row = new ContentValues();
        row.put(Data.RAW_CONTACT_ID, id);
        row.put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
        row.put(StructuredName.DISPLAY_NAME, name);
        provider.db.insert("data", null, row);
        return id;
    }

    /**
     * A read stamps the raw contact at the revision its listing named, the one
     * the engine records with the body (SYNC §5): read back once clean, the
     * contact names no change nobody made.
     */
    @Test
    public void aReadEditIsStampedAtTheRevisionItWasListedAt() throws Exception {
        long id = edited("h1", 3, "Jane");
        PhoneRemote remote = new PhoneRemote(context, new PimdirDb(context));

        JSONObject item = remote.read(SPOKE, "h1");

        assertEquals("dirty:3", item.getString("revision"));
        try (Cursor cursor =
                provider.db.rawQuery("SELECT sync1, dirty FROM raw_contacts WHERE _id = ?",
                        new String[] {String.valueOf(id)})) {
            cursor.moveToFirst();
            assertEquals(0, cursor.getInt(1));
            assertEquals(item.getString("revision"), cursor.getString(0));
        }
    }
}
