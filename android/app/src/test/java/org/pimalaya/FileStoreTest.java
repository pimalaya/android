package org.pimalaya;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaClient;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Attachments as files (pimdir STORAGE section 14.3): a stored message's parts
 * recorded as body-less stand-ins it references, read back in document order,
 * and saved into a folder as a body shared by every copy of the same bytes.
 *
 * <p>The parts come from the real bridge, so the sections the stand-ins are
 * keyed by are the ones {@link PimalayaClient#messagePart} reads back.
 */
@RunWith(RobolectricTestRunner.class)
public class FileStoreTest {
    private static final String ONE = "jane@example.com";
    private static final String DATE = "Mon, 5 Jan 2026 09:00:00 +0000";
    private static final String SOURCE =
            "From: a@example.org\r\n"
                    + "Content-Type: multipart/mixed; boundary=\"sep\"\r\n"
                    + "\r\n"
                    + "--sep\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "\r\n"
                    + "the body\r\n"
                    + "--sep\r\n"
                    + "Content-Type: application/pdf\r\n"
                    + "Content-Disposition: attachment; filename=\"Invoice.pdf\"\r\n"
                    + "\r\n"
                    + "%PDF\r\n"
                    + "--sep\r\n"
                    + "Content-Disposition: attachment; filename=\"notes\"\r\n"
                    + "\r\n"
                    + "notes\r\n"
                    + "--sep--\r\n";

    private final PimalayaClient client = new PimalayaClient();
    private PimdirDb pimdir;
    private PimdirItems items;
    private MailStore mail;
    private FileStore files;
    private String accountId;
    private String inbox;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        items = new PimdirItems(pimdir);
        mail = new MailStore(context, pimdir);
        files = new FileStore(context, pimdir);
        mail.replaceMailboxes(ONE, List.of(new Mailbox("INBOX", "")));
        accountId = mail.accountIdOf(ONE);
        inbox = mail.collectionOf(ONE, "INBOX");
    }

    /** Files one message with its body, and records its parts as a store of it does. */
    private byte[] stored(String linkId) {
        byte[] source = SOURCE.getBytes(StandardCharsets.UTF_8);
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            items.put(
                    db,
                    inbox,
                    new PimdirItems.Row(
                            linkId,
                            null,
                            PimdirSummary.mail(
                                    null, "Invoice", "Sender", "a@example.org", null, DATE, 0,
                                    true),
                            PimdirSummary.mailSortKey(DATE)));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        mail.saveSource(inbox, linkId, source);
        files.recordAttachments(accountId, linkId, client.parseMessage(source).attachments);
        return source;
    }

    private long scalar(String sql, String... args) {
        try (Cursor cursor = pimdir.getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    @Test
    public void aStoredMessageListsItsPartsAsStandInsInDocumentOrder() {
        byte[] source = stored("m1");

        List<FileStore.Attachment> attachments = files.attachments(accountId, "m1");
        assertEquals(2, attachments.size());
        FileStore.Attachment invoice = attachments.get(0);
        assertEquals("part:m1#2", invoice.linkId);
        assertEquals("Invoice.pdf", invoice.name);
        assertEquals("application/pdf", invoice.mediaType);
        assertEquals(Long.valueOf(4), invoice.size);
        assertNull(invoice.objectHash);
        assertNull("a part stating no type has none", attachments.get(1).mediaType);
        assertArrayEquals(
                "%PDF".getBytes(StandardCharsets.UTF_8),
                client.messagePart(source, invoice.part));

        assertEquals(
                "a stand-in holds no body",
                PimdirItems.META,
                scalar("SELECT level FROM items WHERE link_id = ? AND object_hash IS NULL",
                        "part:m1#2"));
        assertEquals(
                "sorted on its name, lowercased",
                1,
                scalar("SELECT count(*) FROM items WHERE sort_key = 'invoice.pdf'"));
    }

    @Test
    public void storingABodyAgainRecordsNothingNew() {
        stored("m1");
        long seq = files.attachments(accountId, "m1").get(0).seq;

        files.recordAttachments(
                accountId,
                "m1",
                client.parseMessage(SOURCE.getBytes(StandardCharsets.UTF_8)).attachments);

        assertEquals(2, scalar("SELECT count(*) FROM items WHERE link_id LIKE 'part:%'"));
        assertEquals(2, scalar("SELECT count(*) FROM item_reference"));
        assertEquals(seq, files.attachments(accountId, "m1").get(0).seq);
    }

    @Test
    public void aSavedCopySharesTheStandInsKeyAndOneBlobPerBytes() {
        stored("m1");
        stored("m2");
        String folder = files.createFolder("Invoices");
        assertEquals("Invoices", files.folders().get(0).name);

        byte[] bytes = "%PDF".getBytes(StandardCharsets.UTF_8);
        FileStore.Attachment first = files.attachments(accountId, "m1").get(0);
        FileStore.Attachment second = files.attachments(accountId, "m2").get(0);
        files.save(folder, first, bytes);
        files.save(folder, second, bytes);
        files.save(folder, first, bytes);

        String hash = PimdirHash.of(bytes);
        assertEquals("two files, saved once each", 2,
                scalar("SELECT count(*) FROM items WHERE collection = ?", folder));
        assertEquals("one blob for the same bytes", 2,
                scalar("SELECT refcount FROM objects WHERE hash = ?", hash));
        assertEquals("the saved copy shares the stand-in's id",
                first.seq,
                scalar("SELECT seq FROM items WHERE collection = ? AND link_id = ?", folder,
                        first.linkId));

        FileStore.Attachment saved = files.attachments(accountId, "m1").get(0);
        assertEquals(hash, saved.objectHash);
        assertEquals("the stand-in is still the row listed", "2", saved.part);
        assertArrayEquals(bytes, files.saved(saved));
    }

    @Test
    public void aMessageGoneTakesItsStandInsAndLeavesItsSavedCopies() {
        stored("m1");
        String folder = files.createFolder("Kept");
        FileStore.Attachment invoice = files.attachments(accountId, "m1").get(0);
        files.save(folder, invoice, "%PDF".getBytes(StandardCharsets.UTF_8));

        SQLiteDatabase db = items.writable();
        items.remove(db, inbox, "m1");

        assertEquals(0, scalar("SELECT count(*) FROM items WHERE collection = ?",
                FileStore.attachmentsOf(accountId)));
        assertEquals(1, scalar("SELECT count(*) FROM items WHERE collection = ?", folder));
        assertEquals(0, scalar("SELECT count(*) FROM item_reference"));
    }

    @Test
    public void forgettingTheAccountDropsItsStandIns() {
        stored("m1");

        mail.forget(ONE);

        assertEquals(0, scalar("SELECT count(*) FROM collections WHERE id = ?",
                FileStore.attachmentsOf(accountId)));
        assertTrue(files.attachments(accountId, "m1").isEmpty());
    }
}
