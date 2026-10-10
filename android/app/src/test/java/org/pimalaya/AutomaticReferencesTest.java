package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.PimdirSql;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The automatic references (pimdir STORAGE section 14.2) as the app records
 * them: a mail and its sender's card whichever lands first, an invitation and
 * its calendar item whichever lands first, none for a writer-derived key, and
 * a store written before them reconciled on open.
 */
@RunWith(RobolectricTestRunner.class)
public class AutomaticReferencesTest {
    private static final String ONE = "jane@example.com";
    private static final String DATE = "Mon, 5 Jan 2026 09:00:00 +0000";
    private static final String BOOK = "https://dav.example.org/jane/contacts/";
    private static final String CALENDAR = "https://dav.example.org/jane/calendar/";
    private static final String VCARD =
            "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:alice\r\nFN:Alice Martin\r\n"
                    + "EMAIL:Alice@Example.org\r\nEND:VCARD\r\n";
    private static final String ICS =
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:ev-1\r\n"
                    + "DTSTART:20260110T100000Z\r\nSUMMARY:Review\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
    private static final String INVITATION =
            "From: Alice <alice@example.org>\r\n"
                    + "Message-ID: <inv@example.org>\r\n"
                    + "Subject: Review\r\n"
                    + "Content-Type: multipart/mixed; boundary=\"sep\"\r\n"
                    + "\r\n"
                    + "--sep\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "\r\n"
                    + "Join us\r\n"
                    + "--sep\r\n"
                    + "Content-Type: text/calendar; method=REQUEST\r\n"
                    + "\r\n"
                    + "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:ev-1\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
                    + "--sep\r\n"
                    + "Content-Type: application/pdf\r\n"
                    + "Content-Disposition: attachment; filename=\"agenda.pdf\"\r\n"
                    + "\r\n"
                    + "%PDF\r\n"
                    + "--sep\r\n"
                    + "Content-Type: image/png\r\n"
                    + "Content-Disposition: inline; filename=\"logo.png\"\r\n"
                    + "\r\n"
                    + "PNG\r\n"
                    + "--sep--\r\n";

    private Context context;
    private PimdirDb pimdir;
    private PimdirItems items;
    private MailStore mail;
    private FileStore files;
    private ItemLinks links;
    private String inbox;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        open();
        mail.replaceMailboxes(ONE, List.of(new Mailbox("INBOX", "")));
        inbox = mail.collectionOf(ONE, "INBOX");
        PimdirCollections collections = new PimdirCollections(pimdir, context);
        collections.ensure(BOOK, ONE, PimdirSummary.CONTACT, "Contacts");
        collections.ensure(CALENDAR, ONE, PimdirSummary.CALENDAR, "Calendar");
    }

    private void open() {
        pimdir = new PimdirDb(context);
        items = new PimdirItems(pimdir);
        mail = new MailStore(context, pimdir);
        files = new FileStore(context, pimdir);
        links = new ItemLinks(context, pimdir);
    }

    private void envelope(String linkId) {
        put(
                inbox,
                new PimdirItems.Row(
                        linkId,
                        null,
                        PimdirSummary.mail(
                                null, "Review", "Alice", "alice@example.org", null, DATE, 0,
                                false),
                        PimdirSummary.mailSortKey(DATE)));
    }

    private void derived(String collection, String kind, String body) {
        JSONObject derived = PimdirSql.derive(kind, body.getBytes(StandardCharsets.UTF_8));
        try {
            put(
                    collection,
                    new PimdirItems.Row(
                            derived.getString("linkId"),
                            body,
                            derived.getJSONObject("summary"),
                            derived.getString("sortKey")));
        } catch (org.json.JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private void put(String collection, PimdirItems.Row row) {
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            items.put(db, collection, row);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Stores the invitation's body the way an open does. */
    private void storeInvitation(String linkId) {
        byte[] body = INVITATION.getBytes(StandardCharsets.UTF_8);
        mail.saveSource(inbox, linkId, body);
        mail.restateFromBody(
                inbox, linkId, PimdirSql.derive(PimdirSummary.MAIL, body).optJSONObject("summary"));
        files.recordAttachments(
                mail.accountIdOf(ONE), linkId, new PimalayaClient().parseMessage(body).attachments);
    }

    private long count(String sql, String... args) {
        try (Cursor cursor = pimdir.getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    private long references(String role) {
        return count("SELECT count(*) FROM item_reference WHERE role = ?", role);
    }

    @Test
    public void aMailAndItsSendersCardAreTiedWhicheverLandsFirst() {
        envelope("m1");
        derived(BOOK, PimdirSummary.CONTACT, VCARD);
        assertEquals("the card gathers the mail already there", 1, references("sender"));

        envelope("m2");
        assertEquals("a mail landing after the card refers to it", 2, references("sender"));
        ItemLinks.Link link = links.links(ItemLinks.ofContact("alice")).get(0);
        assertTrue(link.automatic());
        assertEquals("sender", link.role);
    }

    @Test
    public void anInvitationAndItsEventAreTiedWhicheverLandsFirst() {
        envelope("inv@example.org");
        storeInvitation("inv@example.org");
        assertEquals("no event held yet", 0, references("invitation"));

        derived(CALENDAR, PimdirSummary.CALENDAR, ICS);
        assertEquals("the event lands after its invitation", 1, references("invitation"));

        envelope("inv2@example.org");
        storeInvitation("inv2@example.org");
        assertEquals("an invitation stored after its event", 2, references("invitation"));
    }

    @Test
    public void onlyPartsDisposedAsAttachmentsBecomeFilesAndNoneUnderADerivedKey() {
        envelope("inv@example.org");
        storeInvitation("inv@example.org");
        List<FileStore.StoredFile> attached = files.attachments(mail.accountIdOf(ONE), "inv@example.org");
        assertEquals(1, attached.size());
        assertEquals("agenda.pdf", attached.get(0).name);

        envelope("alt:Review|2026-01-05T09:00:00Z|alice@example.org");
        storeInvitation("alt:Review|2026-01-05T09:00:00Z|alice@example.org");
        assertTrue(
                files.attachments(
                                mail.accountIdOf(ONE),
                                "alt:Review|2026-01-05T09:00:00Z|alice@example.org")
                        .isEmpty());
        assertTrue(ItemLinks.derived("alt:x"));
        assertTrue(ItemLinks.derived("part:dup:x#2"));
        assertFalse(ItemLinks.derived("part:inv@example.org#2"));
        assertNull(FileStore.partKey("hash:0011", "2"));
    }

    @Test
    public void aStoreWrittenBeforeAutomaticReferencesIsReconciledOnOpen() {
        envelope("inv@example.org");
        mail.saveSource(inbox, "inv@example.org", INVITATION.getBytes(StandardCharsets.UTF_8));
        derived(BOOK, PimdirSummary.CONTACT, VCARD);
        derived(CALENDAR, PimdirSummary.CALENDAR, ICS);
        files.recordAttachments(
                mail.accountIdOf(ONE),
                "inv@example.org",
                new PimalayaClient()
                        .parseMessage(INVITATION.getBytes(StandardCharsets.UTF_8))
                        .attachments);
        String attachments = FileStore.attachmentsOf(mail.accountIdOf(ONE));
        long rows = count("SELECT count(*) FROM items");

        // NOTE: the previous schema, fabricated: no invitation column, a role
        // constraint without the attachments role, no reference recorded by
        // rule, the attachments collection carrying no role.
        SQLiteDatabase db = pimdir.getWritableDatabase();
        db.execSQL("DELETE FROM item_reference WHERE role <> 'attachment'");
        db.execSQL("UPDATE collections SET role = NULL WHERE id = ?", new Object[] {attachments});
        db.execSQL("DROP INDEX mail_summary_by_invitation");
        db.execSQL("ALTER TABLE mail_summary DROP COLUMN invitation");
        String declared;
        try (Cursor cursor =
                db.rawQuery("SELECT sql FROM sqlite_master WHERE name = 'collections'", null)) {
            cursor.moveToFirst();
            declared = cursor.getString(0);
        }
        String older =
                declared.replaceAll(
                        "\\s*OR \\(kind = 'application/octet-stream' AND role = 'attachments'\\)",
                        "");
        db.setForeignKeyConstraintsEnabled(false);
        db.beginTransaction();
        db.execSQL("PRAGMA legacy_alter_table = ON");
        db.execSQL(older.replaceFirst("collections", "collections_old"));
        db.execSQL("INSERT INTO collections_old SELECT * FROM collections");
        db.execSQL("DROP TABLE collections");
        db.execSQL("ALTER TABLE collections_old RENAME TO collections");
        db.execSQL("PRAGMA legacy_alter_table = OFF");
        db.setTransactionSuccessful();
        db.endTransaction();
        db.setForeignKeyConstraintsEnabled(true);
        pimdir.close();

        open();

        assertEquals("no row lost to a cascade", rows, count("SELECT count(*) FROM items"));
        assertEquals(
                1,
                count("SELECT count(*) FROM sqlite_master WHERE name = 'collections'"
                        + " AND sql LIKE '%''attachments''%'"));
        assertEquals(
                "the collections triggers come back",
                1,
                count("SELECT count(*) FROM sqlite_master WHERE name = 'collections_role_moves'"));
        assertEquals(
                1,
                count("SELECT count(*) FROM mail_summary WHERE invitation = 'ev-1'"));
        assertEquals(1, references("invitation"));
        assertEquals(1, references("sender"));
        assertEquals(
                "the attachments collection takes its role",
                1,
                count("SELECT count(*) FROM collections WHERE id = ? AND role = 'attachments'",
                        attachments));
    }
}
