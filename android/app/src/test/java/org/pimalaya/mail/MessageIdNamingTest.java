package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.Derived;
import org.pimalaya.client.Flags;
import org.pimalaya.client.FloorReply;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.MailPage;
import org.pimalaya.client.MailRequest;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.MailWire;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.MessageRef;
import org.pimalaya.client.MessageSource;
import org.pimalaya.client.MessageSources;
import org.pimalaya.client.Named;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.PimdirSql;
import org.pimalaya.file.FileStore;
import org.pimalaya.pimdir.ItemFixtures;
import org.pimalaya.pimdir.ItemLinks;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A message is named by its {@code Message-ID}, its server id its handle. */
@RunWith(RobolectricTestRunner.class)
public class MessageIdNamingTest {
    private static final String EMAIL = "jane@example.com";
    private static final String SENDER = "a@example.org";
    private static final String DATE = "2026-10-07T08:00:00Z";
    private static final String CALENDAR = "https://dav.example.org/jane/calendar/";

    private Context context;
    private PimdirDb pimdir;
    private MailStore store;
    private FileStore files;
    private String accountId;
    private String inbox;
    private String archive;
    private String trash;
    private String sent;
    private Server server;

    /** A server of several mailboxes, its verbs recorded. */
    private final class Server extends MailClient {
        final Map<String, JSONArray> listed = new HashMap<>();
        final Map<String, Named> named = new HashMap<>();
        final Map<String, String> sources = new HashMap<>();
        final List<String> read = new ArrayList<>();
        final List<String> calls = new ArrayList<>();

        final MailEngine engine =
                new MailEngine(
                        pimdir,
                        new Clients(
                                new AccountClient(), new ContactClient(), new CalendarClient(),
                                this, new OfflineClient()),
                        null,
                        accountId);

        void list(String mailbox, JSONObject... members) {
            JSONArray items = new JSONArray();
            for (JSONObject member : members) {
                items.put(member);
            }
            listed.put(mailbox, items);
        }

        @Override
        public FloorReply floor(MailSession session, String mailbox, String before, int count) {
            return new FloorReply(null, 0, List.of());
        }

        @Override
        public MailPage enumerateMailbox(MailSession session, String mailbox, MailRequest request) {
            try {
                return MailWire.page(new JSONObject()
                        .put("items", listed.getOrDefault(mailbox, new JSONArray()))
                        .put("vanished", new JSONArray())
                        .put("complete", true)
                        .put("last", true)
                        .put("checkpoint", "7:9"));
            } catch (JSONException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public List<Named> nameMessages(MailSession session, String mailbox, List<String> handles) {
            List<Named> read = new ArrayList<>();
            for (String handle : handles) {
                read.add(named.get(mailbox + " " + handle));
            }
            return read;
        }

        @Override
        public MessageSources fetchSources(
                MailSession session, String mailbox, List<String> ids, File dir) {
            List<MessageSource> items = new ArrayList<>();
            for (String id : ids) {
                read.add(mailbox + " " + id);
                File file = new File(dir, id + ".eml");
                try {
                    Files.write(
                            file.toPath(),
                            sources.get(mailbox + " " + id).getBytes(StandardCharsets.UTF_8));
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
                items.add(new MessageSource(id, null, file.getAbsolutePath(), null, file.length()));
            }
            return new MessageSources(List.of(), items);
        }

        @Override
        public void relocate(MailSession session, MessageRef message, String target) {
            calls.add("relocate " + message.mailbox() + " " + message.id() + " " + target);
        }

        @Override
        public void copy(MailSession session, MessageRef message, String target) {
            calls.add("copy " + message.mailbox() + " " + message.id() + " " + target);
        }

        @Override
        public void setFlag(
                MailSession session, MessageRef message, Flags.Marker flag, boolean add) {
            calls.add((add ? "add " : "remove ") + flag.wire() + " " + message.id());
        }

        @Override
        public void append(MailSession session, String mailbox, byte[] source, Flags flags) {
            calls.add("append " + mailbox);
        }
    }

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new MailStore(context, pimdir);
        files = new FileStore(context, pimdir);
        store.replaceMailboxes(
                EMAIL,
                List.of(
                        new Mailbox("INBOX", "inbox"),
                        new Mailbox("Archive", ""),
                        new Mailbox("Trash", "trash"),
                        new Mailbox("Sent", "sent")));
        accountId = new PimdirAccount(context).idOf(EMAIL);
        inbox = store.collectionOf(EMAIL, "INBOX");
        archive = store.collectionOf(EMAIL, "Archive");
        trash = store.collectionOf(EMAIL, "Trash");
        sent = store.collectionOf(EMAIL, "Sent");
        server = new Server();
    }

    /** The hint the bridge names a member by: its Message-ID, else alt:. */
    private static String hint(String messageId, String subject) {
        return messageId != null ? messageId : "alt:" + subject + "|" + DATE + "|" + SENDER;
    }

    /** The summary a listing reads of a message. */
    private static JSONObject summary(String messageId, String subject) {
        return PimdirSummary.mail(messageId, subject, "", SENDER, null, DATE, 0, true);
    }

    /** One member as the bridge lists it. */
    private static JSONObject listed(String handle, String messageId, String subject)
            throws JSONException {
        return new JSONObject()
                .put("handle", handle)
                .put("flags", new JSONArray())
                .put("linkId", hint(messageId, subject))
                .put("summary", summary(messageId, subject))
                .put("sortKey", PimdirSummary.mailSortKey(DATE));
    }

    /** A message attaching one file; a null Message-ID states none. */
    private static String source(String messageId, String subject, String name, String type) {
        return (messageId == null ? "" : "Message-ID: <" + messageId + ">\r\n")
                + "From: " + SENDER + "\r\n"
                + "Subject: " + subject + "\r\n"
                + "Date: Wed, 07 Oct 2026 08:00:00 +0000\r\n"
                + "Content-Type: multipart/mixed; boundary=\"sep\"\r\n\r\n"
                + "--sep\r\nContent-Type: text/plain\r\n\r\nthe body\r\n"
                + "--sep\r\nContent-Type: " + type + "\r\n"
                + "Content-Disposition: attachment; filename=\"" + name + "\"\r\n\r\n"
                + "bytes\r\n--sep--\r\n";
    }

    /** The messages filed in a collection. */
    private List<MailStore.StoredMessage> rows(String collection) {
        List<MailStore.StoredMessage> rows = new ArrayList<>();
        for (MailStore.StoredMessage message : MailFixtures.merged(store, 20)) {
            if (message.collection.equals(collection)) {
                rows.add(message);
            }
        }
        return rows;
    }

    /** The one message filed in a collection. */
    private MailStore.StoredMessage row(String collection) {
        List<MailStore.StoredMessage> rows = rows(collection);
        assertEquals("one message in " + collection, 1, rows.size());
        return rows.get(0);
    }

    /** The names of the files a message's stand-ins hold. */
    private List<String> attached(MailStore.StoredMessage message) {
        List<String> names = new ArrayList<>();
        for (FileStore.StoredFile file : files.attachments(accountId, message.id)) {
            names.add(file.name);
        }
        return names;
    }

    /** The names of the files a message's reader shows. */
    private List<String> shown(MailStore.StoredMessage message, MailReader.Read read) {
        List<String> names = new ArrayList<>();
        for (FileStore.StoredFile file : reader().attachments(message, read.body)) {
            names.add(file.name);
        }
        return names;
    }

    private MailReader reader() {
        return new MailReader(store, files, server, new MailStager(pimdir, Clients.real()));
    }

    /** The subject line a message's stored body states. */
    private String storedSubject(MailStore.StoredMessage message) {
        String body = new String(store.storedSource(message.collection, message.id),
                StandardCharsets.UTF_8);
        int at = body.indexOf("Subject: ");
        return body.substring(at + 9, body.indexOf("\r\n", at));
    }

    private String scalar(String sql, String... args) {
        try (Cursor cursor = pimdir.getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    /** Checkpoints a mailbox, as its first sync would. */
    private void synced(String collection) throws JSONException {
        JSONObject checkpoint =
                new JSONObject()
                        .put("op", "setCheckpoint")
                        .put("collection", collection)
                        .put("checkpoint", "7:9");
        new MailStager(pimdir, Clients.real())
                .serve(
                        new JSONObject()
                                .put("op", "write")
                                .put("writes", new JSONArray().put(checkpoint))
                                .toString());
    }

    /** Files a task made of the mail a Message-ID names, as a capture does. */
    private void task(String messageId) {
        new PimdirCollections(pimdir, context)
                .ensure(CALENDAR, EMAIL, PimdirSummary.CALENDAR, "Calendar");
        String ics =
                "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:task-1\r\n"
                        + "SUMMARY:Pay the invoice\r\nATTACH:mid:" + messageId + "\r\n"
                        + "END:VTODO\r\nEND:VCALENDAR\r\n";
        Derived derived =
                PimdirSql.derive(PimdirSummary.CALENDAR, ics.getBytes(StandardCharsets.UTF_8));
        SQLiteDatabase db = pimdir.getWritableDatabase();
        db.beginTransaction();
        try {
            new ItemFixtures(pimdir)
                    .put(
                            db,
                            CALENDAR,
                            new ItemFixtures.Row(
                                    derived.linkId(), ics, derived.summary(), derived.sortKey()));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** The mailbox the task's {@code source} link opens, null for none. */
    private String taskSource() {
        for (ItemLinks.Link link :
                new ItemLinks(context, pimdir)
                        .links(ItemLinks.of(PimdirSummary.CALENDAR, "task-1"))) {
            if ("source".equals(link.role) && link.other != null) {
                return link.other.collection;
            }
        }
        return null;
    }

    @Test
    public void twoMailboxesReusingAUidKeepTheirOwnAttachments() throws Exception {
        server.list("INBOX", listed("42", "invoice@example.org", "Invoice"));
        server.list("Archive", listed("42", "holiday@example.org", "Holiday"));
        server.sources.put(
                "INBOX 42",
                source("invoice@example.org", "Invoice", "invoice.pdf", "application/pdf"));
        server.sources.put(
                "Archive 42", source("holiday@example.org", "Holiday", "beach.jpg", "image/jpeg"));

        server.engine.sync(inbox);
        server.engine.sync(archive);
        server.engine.downloadStep(List.of(inbox, archive), null, null);

        assertEquals("each read by its UID", Set.of("INBOX 42", "Archive 42"),
                Set.copyOf(server.read));
        assertEquals("invoice@example.org", row(inbox).id);
        assertEquals(List.of("invoice.pdf"), attached(row(inbox)));
        assertEquals(List.of("beach.jpg"), attached(row(archive)));
        assertEquals("the Files tab lists both", "2",
                scalar("SELECT count(*) FROM items WHERE collection = ?",
                        FileStore.attachmentsOf(accountId)));
    }

    @Test
    public void anImapMoveKeepsItsAttachmentRecordsAndItsTasksSource() throws Exception {
        synced(archive);
        server.list("INBOX", listed("42", "invoice@example.org", "Invoice"));
        server.sources.put(
                "INBOX 42",
                source("invoice@example.org", "Invoice", "invoice.pdf", "application/pdf"));
        server.engine.sync(inbox);
        server.engine.downloadStep(List.of(inbox), null, null);
        task("invoice@example.org");
        assertEquals("the task opens its mail", inbox, taskSource());

        server.engine.mutateMove(inbox, "42", archive);
        server.engine.sync(inbox);
        server.list("INBOX");
        server.list("Archive", listed("7", "invoice@example.org", "Invoice"));
        server.engine.sync(archive);
        server.read.clear();
        server.engine.downloadStep(List.of(archive), null, null);

        assertEquals(List.of("relocate INBOX 42 Archive"), server.calls);
        MailStore.StoredMessage moved = row(archive);
        assertEquals("invoice@example.org", moved.id);
        assertEquals("7", scalar("SELECT handle FROM bindings WHERE item ="
                + " (SELECT id FROM items WHERE collection = ?)", archive));
        assertEquals("its record kept", List.of("invoice.pdf"), attached(moved));
        assertEquals("one record", "1", scalar("SELECT count(*) FROM items WHERE collection = ?",
                FileStore.attachmentsOf(accountId)));
        assertTrue("its body linked, not read again: " + server.read, server.read.isEmpty());
        assertTrue("its body held", store.storedSource(archive, moved.id) != null);
        assertEquals("the task still opens it", archive, taskSource());
    }

    @Test
    public void aGraphMoveKeepsThem() throws Exception {
        synced(archive);
        server.list("INBOX", listed("AAMkAD1", "invoice@example.org", "Invoice"));
        server.sources.put(
                "INBOX AAMkAD1",
                source("invoice@example.org", "Invoice", "invoice.pdf", "application/pdf"));
        server.engine.sync(inbox);
        server.engine.downloadStep(List.of(inbox), null, null);
        task("invoice@example.org");

        server.engine.mutateMove(inbox, "AAMkAD1", archive);
        server.engine.sync(inbox);
        // NOTE: a Graph delta lists the moved message by its new id alone.
        server.list("INBOX");
        server.list("Archive", new JSONObject().put("handle", "AAMkAD2").put("flags",
                new JSONArray()));
        JSONObject read = listed("AAMkAD2", "invoice@example.org", "Invoice");
        server.named.put(
                "Archive AAMkAD2",
                new Named(List.of(), "AAMkAD2", read.getString("linkId"),
                        read.getString("sortKey"), read.getJSONObject("summary")));
        server.engine.sync(archive);

        assertEquals(List.of("relocate INBOX AAMkAD1 Archive"), server.calls);
        MailStore.StoredMessage moved = row(archive);
        assertEquals("invoice@example.org", moved.id);
        assertEquals(List.of("invoice.pdf"), attached(moved));
        assertEquals("the task still opens it", archive, taskSource());
    }

    @Test
    public void aMessageWithoutAMessageIdListsReadsAndSyncs() throws Exception {
        server.list("INBOX", listed("5", null, "Notice"));
        server.sources.put("INBOX 5", source(null, "Notice", "notice.pdf", "application/pdf"));
        server.engine.sync(inbox);

        MailStore.StoredMessage notice = row(inbox);
        assertEquals("alt:Notice|" + DATE + "|" + SENDER, notice.id);
        MailReader.Read read = reader().read(notice, () -> null);
        assertTrue(read.fetched);
        assertEquals(List.of("INBOX 5"), server.read);
        assertEquals("Notice", read.body.subject());
        assertTrue("no record under a derived key", attached(notice).isEmpty());
        assertEquals("shown from its body", List.of("notice.pdf"), shown(notice, read));

        server.engine.mutateFlags(inbox, "5", Flags.of(Flags.Marker.SEEN));
        server.engine.sync(inbox);
        assertEquals(List.of("add \\Seen 5"), server.calls);
    }

    @Test
    public void twoMessagesSharingAMessageIdStayTwo() throws Exception {
        server.list(
                "INBOX",
                listed("1", "same@example.org", "First"),
                listed("2", "same@example.org", "Second"));
        server.list("Archive", listed("1", "same@example.org", "Third"));
        String pdf = "application/pdf";
        server.sources.put("INBOX 1", source("same@example.org", "First", "a.pdf", pdf));
        server.sources.put("INBOX 2", source("same@example.org", "Second", "b.pdf", pdf));
        server.sources.put("Archive 1", source("same@example.org", "Third", "c.pdf", pdf));

        server.engine.sync(inbox);
        server.engine.sync(archive);
        server.engine.downloadStep(List.of(inbox), null, null);
        server.engine.downloadStep(List.of(archive), null, null);

        assertEquals(
                Set.of("INBOX 1", "INBOX 2", "Archive 1"), Set.copyOf(server.read));
        Map<String, String> subjects = new HashMap<>();
        for (MailStore.StoredMessage message : rows(inbox)) {
            subjects.put(message.id, storedSubject(message));
        }
        assertEquals(
                Map.of("same@example.org", "First", "dup:same@example.org#2", "Second"),
                subjects);
        MailStore.StoredMessage third = row(archive);
        assertEquals("Third", storedSubject(third));
        MailStore.StoredMessage second = store.message(inbox, "dup:same@example.org#2");
        assertEquals("its own parts", List.of("b.pdf"),
                shown(second, reader().read(second, () -> null)));
    }

    @Test
    public void aSentCopyKeepsItsMessageIdAsItsKey() throws Exception {
        synced(sent);
        stageSent("m1@example.com");
        server.list("Sent", listed("900", "m1@example.com", "Hi"));

        server.engine.sync(sent);

        assertTrue("landed, not appended: " + server.calls, server.calls.isEmpty());
        assertEquals("1", scalar("SELECT count(*) FROM items WHERE collection = ?", sent));
        assertEquals("m1@example.com",
                scalar("SELECT link_id FROM items WHERE collection = ?", sent));
        assertEquals("900", scalar("SELECT handle FROM bindings WHERE collection = ?", sent));
    }

    @Test
    public void aGmailSentCopyLandsOnItsPendingCreate() throws Exception {
        synced(sent);
        stageSent("m1@example.com");
        // NOTE: Gmail stamps a Message-ID of its own on what it sends.
        store.policy().aliasSentCopy("m1@example.com", "CAF00@mail.gmail.com");
        server.list("Sent", listed("18c2a", "CAF00@mail.gmail.com", "Hi"));

        server.engine.sync(sent);

        assertTrue("landed, not appended: " + server.calls, server.calls.isEmpty());
        assertEquals("1", scalar("SELECT count(*) FROM items WHERE collection = ?", sent));
        assertEquals("the create's key", "m1@example.com",
                scalar("SELECT link_id FROM items WHERE collection = ?", sent));
        assertEquals("18c2a", scalar("SELECT handle FROM bindings WHERE collection = ?", sent));
        assertNull("the alias spent", store.policy().stagedSentCopy("CAF00@mail.gmail.com"));
        assertFalse("Gmail's markers, not the staged \\Seen",
                row(sent).flags.has(Flags.Marker.SEEN));
    }

    /**
     * Gmail restamps the Date too, to when it sent: its copy lands all the
     * same, rather than a second row beside the staged one.
     */
    @Test
    public void aGmailSentCopyRedatedLandsOnItsPendingCreate() throws Exception {
        synced(sent);
        stageSent("m1@example.com");
        store.policy().aliasSentCopy("m1@example.com", "CAF00@mail.gmail.com");
        String later = "2026-10-07T08:00:05Z";
        server.list(
                "Sent",
                listed("18c2a", "CAF00@mail.gmail.com", "Hi")
                        .put("summary",
                                PimdirSummary.mail("CAF00@mail.gmail.com", "Hi", "", SENDER, null,
                                        later, 0, true))
                        .put("sortKey", PimdirSummary.mailSortKey(later)));

        server.engine.sync(sent);

        assertTrue("landed, not appended: " + server.calls, server.calls.isEmpty());
        assertEquals("one row", "1",
                scalar("SELECT count(*) FROM items WHERE collection = ?", sent));
        assertEquals("the create's key", "m1@example.com",
                scalar("SELECT link_id FROM items WHERE collection = ?", sent));
        assertEquals("18c2a", scalar("SELECT handle FROM bindings WHERE collection = ?", sent));
    }

    /** Stages a sent message's copy, as a submission does. */
    private void stageSent(String messageId) throws Exception {
        String body = "Message-ID: <" + messageId + ">\r\nSubject: Hi\r\n\r\nbody\r\n";
        server.engine.mutateAdd(
                sent,
                messageId,
                body,
                Flags.of(Flags.Marker.SEEN),
                PimdirSummary.mail(messageId, "Hi", null, SENDER, "bob@example.org",
                        DATE, body.length(), false),
                PimdirSummary.mailSortKey(DATE));
    }

    @Test
    public void theReaderFetchesAMessageByItsUid() throws Exception {
        server.list("Archive", listed("42", "holiday@example.org", "Holiday"));
        server.sources.put(
                "Archive 42", source("holiday@example.org", "Holiday", "beach.jpg", "image/jpeg"));
        server.engine.sync(archive);

        MailReader.Read read = reader().read(row(archive), () -> null);

        assertTrue(read.fetched);
        assertEquals(List.of("Archive 42"), server.read);
        assertEquals("Holiday", read.body.subject());
        assertEquals(List.of("beach.jpg"), attached(row(archive)));
    }

    @Test
    public void aMoveRelocatesItsUidAndWithdrawsItsTarget() throws Exception {
        synced(trash);
        server.list("INBOX", listed("42", "m1@example.org", "Hello"));
        server.engine.sync(inbox);

        server.engine.mutateMove(inbox, "42", trash);
        server.engine.sync(inbox);

        assertEquals(List.of("relocate INBOX 42 Trash"), server.calls);
        assertNull("the arrival brings it",
                scalar("SELECT link_id FROM items WHERE collection = ?", trash));
    }

    @Test
    public void aCopyComesFromItsOwnMailboxByItsUid() throws Exception {
        synced(sent);
        server.list("INBOX", listed("42", "inbox@example.org", "Inbox's"));
        server.list("Archive", listed("42", "archive@example.org", "Archive's"));
        server.engine.sync(inbox);
        server.engine.sync(archive);

        server.engine.mutateCopy(archive, "42", sent);
        server.engine.sync(sent);

        assertEquals(List.of("copy Archive 42 Sent"), server.calls);
    }

    @Test
    public void aMarkerIsWrittenOnItsUid() throws Exception {
        server.list("INBOX", listed("42", "m1@example.org", "Hello"));
        server.engine.sync(inbox);

        server.engine.mutateFlags(inbox, "42", Flags.of(Flags.Marker.SEEN));
        server.engine.sync(inbox);

        assertEquals(List.of("add \\Seen 42"), server.calls);
    }
}
