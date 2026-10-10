package org.pimalaya;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaClient;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    public void setUp() throws Exception {
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

    /** Bytes written to a file of their own, as a file travels in the app. */
    private File file(byte[] bytes) throws IOException {
        File file =
                File.createTempFile(
                        "bytes", null, RuntimeEnvironment.getApplication().getCacheDir());
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static byte[] bytes(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    /** One part of a message, written out by the bridge as the reader has it. */
    private byte[] part(byte[] source, String section) throws IOException {
        File out =
                File.createTempFile(
                        "part", null, RuntimeEnvironment.getApplication().getCacheDir());
        client.messagePart(file(source), section, out);
        return bytes(out);
    }

    private long scalar(String sql, String... args) {
        try (Cursor cursor = pimdir.getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    @Test
    public void aStoredMessageListsItsPartsAsStandInsInDocumentOrder() throws Exception {
        byte[] source = stored("m1");

        List<FileStore.StoredFile> attachments = files.attachments(accountId, "m1");
        assertEquals(2, attachments.size());
        FileStore.StoredFile invoice = attachments.get(0);
        assertEquals("part:m1#2", invoice.linkId);
        assertEquals("Invoice.pdf", invoice.name);
        assertEquals("application/pdf", invoice.mediaType);
        assertEquals(Long.valueOf(4), invoice.size);
        assertNull(invoice.objectHash);
        assertNull("a part stating no type has none", attachments.get(1).mediaType);
        assertArrayEquals(
                "%PDF".getBytes(StandardCharsets.UTF_8),
                part(source, invoice.part));

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
    public void storingABodyAgainRecordsNothingNew() throws Exception {
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
    public void aSavedCopySharesTheStandInsKeyAndOneBlobPerBytes() throws Exception {
        stored("m1");
        stored("m2");
        String folder = files.createFolder("Invoices");
        assertEquals("Invoices", files.folders().get(0).name);

        byte[] bytes = "%PDF".getBytes(StandardCharsets.UTF_8);
        FileStore.StoredFile first = files.attachments(accountId, "m1").get(0);
        FileStore.StoredFile second = files.attachments(accountId, "m2").get(0);
        files.save(folder, first, file(bytes));
        files.save(folder, second, file(bytes));
        files.save(folder, first, file(bytes));

        String hash = PimdirHash.of(bytes);
        assertEquals("two files, saved once each", 2,
                scalar("SELECT count(*) FROM items WHERE collection = ?", folder));
        assertEquals("one blob for the same bytes", 2,
                scalar("SELECT refcount FROM objects WHERE hash = ?", hash));
        assertEquals("the saved copy shares the stand-in's id",
                first.seq,
                scalar("SELECT seq FROM items WHERE collection = ? AND link_id = ?", folder,
                        first.linkId));

        FileStore.StoredFile saved = files.attachments(accountId, "m1").get(0);
        assertEquals(hash, saved.objectHash);
        assertEquals("the stand-in is still the row listed", "2", saved.part);
        assertArrayEquals(bytes, bytes(files.saved(saved)));
    }

    @Test
    public void aMessageGoneTakesItsStandInsAndLeavesItsSavedCopies() throws Exception {
        stored("m1");
        String folder = files.createFolder("Kept");
        FileStore.StoredFile invoice = files.attachments(accountId, "m1").get(0);
        files.save(folder, invoice, file("%PDF".getBytes(StandardCharsets.UTF_8)));

        SQLiteDatabase db = items.writable();
        items.remove(db, inbox, "m1");

        assertEquals(0, scalar("SELECT count(*) FROM items WHERE collection = ?",
                FileStore.attachmentsOf(accountId)));
        assertEquals(1, scalar("SELECT count(*) FROM items WHERE collection = ?", folder));
        assertEquals(0, scalar("SELECT count(*) FROM item_reference"));
    }

    @Test
    public void forgettingTheAccountDropsItsStandIns() throws Exception {
        stored("m1");

        mail.forget(ONE);

        assertEquals(0, scalar("SELECT count(*) FROM collections WHERE id = ?",
                FileStore.attachmentsOf(accountId)));
        assertTrue(files.attachments(accountId, "m1").isEmpty());
    }

    @Test
    public void aStandInLeadsBackToItsMessageAndSender() throws Exception {
        stored("m1");
        FileStore.StoredFile invoice =
                files.files(FileStore.attachmentsOf(accountId)).get(0);

        FileStore.Origin origin = files.origin(invoice.linkId);
        assertEquals(inbox, origin.collection);
        assertEquals("m1", origin.linkId);
        assertEquals("a@example.org", origin.sender);
        assertEquals("Invoice", mail.message(origin.collection, origin.linkId).subject);
        assertNull("an import comes from no message", files.origin("file:none"));
    }

    @Test
    public void foldersAreListedApartFromAttachmentsAndRenamedByLabel() throws Exception {
        stored("m1");
        String folder = files.createFolder("Taxes");

        assertEquals(1, files.folders().size());
        assertEquals(1, files.attachmentCollections().size());
        assertTrue(FileStore.isAttachments(files.attachmentCollections().get(0)));

        files.renameFolder(folder, "Receipts");
        assertEquals(folder, files.folders().get(0).id);
        assertEquals("Receipts", files.folders().get(0).name);
    }

    @Test
    public void anImportIsAFileOfItsOwnAndADeleteReleasesItsBody() throws Exception {
        String folder = files.createFolder("Inbox");
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        files.importFile(folder, "Hello.txt", "text/plain", file(bytes));

        List<FileStore.StoredFile> held = files.files(folder);
        assertEquals(1, held.size());
        assertTrue(held.get(0).linkId.matches("file:[0-9a-f]{32}"));
        assertEquals("text/plain", held.get(0).mediaType);
        assertEquals(Long.valueOf(5), held.get(0).size);
        assertArrayEquals(bytes, bytes(files.saved(held.get(0))));

        files.delete(held.get(0));

        assertTrue(files.files(folder).isEmpty());
        assertEquals(0, scalar("SELECT count(*) FROM objects WHERE hash = ?",
                PimdirHash.of(bytes)));
    }

    @Test
    public void aStandInIsNotDeletedByHandAndAFolderGoesWithItsFiles() throws Exception {
        stored("m1");
        String folder = files.createFolder("Kept");
        FileStore.StoredFile invoice = files.attachments(accountId, "m1").get(0);
        byte[] bytes = "%PDF".getBytes(StandardCharsets.UTF_8);
        files.save(folder, invoice, file(bytes));

        files.delete(invoice);
        assertEquals(2, files.attachments(accountId, "m1").size());

        files.deleteFolder(folder);
        assertTrue(files.folders().isEmpty());
        assertEquals(0, scalar("SELECT count(*) FROM objects WHERE hash = ?",
                PimdirHash.of(bytes)));
        assertNull(files.attachments(accountId, "m1").get(0).objectHash);
    }

    @Test
    public void theTypeChipsSortMediaTypes() throws Exception {
        assertTrue(FilesList.matches(FilesList.IMAGES, "image/png"));
        assertTrue(FilesList.matches(FilesList.PDFS, "application/pdf"));
        assertTrue(FilesList.matches(
                FilesList.DOCUMENTS,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertTrue(!FilesList.matches(FilesList.DOCUMENTS, null));
    }

    @Test
    public void aFolderNameIsTakenOnceAndASecondSaveSavesNothing() throws Exception {
        stored("m1");
        String taxes = files.createFolder("Taxes");
        String receipts = files.createFolder("Receipts");

        assertNull("a name taken", files.createFolder("Taxes"));
        assertFalse(files.renameFolder(receipts, "Taxes"));
        assertTrue("its own name is its own", files.renameFolder(taxes, "Taxes"));

        FileStore.StoredFile invoice = files.attachments(accountId, "m1").get(0);
        byte[] bytes = "%PDF".getBytes(StandardCharsets.UTF_8);
        assertTrue(files.save(taxes, invoice, file(bytes)));
        assertFalse(files.save(taxes, invoice, file(bytes)));
    }

    @Test
    public void anAttachmentUnlinkedComesBackWithTheBody() throws Exception {
        stored("m1");
        ItemLinks links = new ItemLinks(RuntimeEnvironment.getApplication(), pimdir);
        ItemLinks.Link attachment =
                links.links(new ItemLinks.Endpoint(PimdirSummary.MAIL, "m1")).get(0);

        // NOTE: the reader offers no unlink for it; a reference removed
        // anyway takes the stand-in, and storing the body records both again.
        links.remove(attachment);
        stored("m1");

        assertEquals(2, files.attachments(accountId, "m1").size());
    }

    @Test
    public void aTextPartIsReadAsItsSenderWroteIt() throws Exception {
        byte[] latin = {'c', 'a', 'f', (byte) 0xe9};
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        raw.write(("From: a@example.org\r\n"
                        + "Content-Type: multipart/mixed; boundary=\"sep\"\r\n\r\n"
                        + "--sep\r\nContent-Type: text/plain\r\n\r\nbody\r\n"
                        + "--sep\r\nContent-Type: text/plain; charset=iso-8859-1\r\n"
                        + "Content-Disposition: attachment; filename=\"latin.txt\"\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        raw.write(latin);
        raw.write("\r\n--sep--\r\n".getBytes(StandardCharsets.US_ASCII));

        assertArrayEquals(latin, part(raw.toByteArray(), "2"));
    }
}
