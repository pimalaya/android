package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

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
import org.pimalaya.client.OfflineClient;
import org.pimalaya.file.FileStore;
import org.pimalaya.pimdir.PimdirAccount;
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

/**
 * An IMAP message is named across its account, addressed by its UID.
 *
 * <p>Its link id is the bridge's UID, UIDVALIDITY and mailbox, its handle the
 * UID: two mailboxes reusing a UID file two messages, each with its own
 * parts, and every server verb names the UID. The real engine and store.
 */
@RunWith(RobolectricTestRunner.class)
public class ImapLinkIdTest {
    private static final String EMAIL = "jane@example.com";
    private static final String DATE = "2026-10-07T08:00:00Z";

    private PimdirDb pimdir;
    private MailStore store;
    private FileStore files;
    private String accountId;
    private String inbox;
    private String archive;
    private String trash;
    private String sent;
    private Server server;

    /** An IMAP server of several mailboxes, its verbs recorded. */
    private final class Server extends MailClient {
        final Map<String, JSONArray> listed = new HashMap<>();
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
        public MessageSources fetchSources(
                MailSession session, String mailbox, List<String> ids, File dir) {
            List<MessageSource> items = new ArrayList<>();
            for (String uid : ids) {
                read.add(mailbox + " " + uid);
                File file = new File(dir, uid + ".eml");
                try {
                    Files.write(
                            file.toPath(),
                            sources.get(mailbox + " " + uid).getBytes(StandardCharsets.UTF_8));
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
                items.add(
                        new MessageSource(uid, null, file.getAbsolutePath(), null, file.length()));
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
        Context context = RuntimeEnvironment.getApplication();
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

    /** One member as the bridge's IMAP listing names it, UIDVALIDITY 7. */
    private static JSONObject listed(String mailbox, String uid, String messageId, String subject)
            throws JSONException {
        return new JSONObject()
                .put("handle", uid)
                .put("flags", new JSONArray())
                .put("linkId", uid + ":7:" + mailbox)
                .put(
                        "summary",
                        PimdirSummary.mail(
                                messageId, subject, "", EMAIL, null, DATE, 0, true))
                .put("sortKey", PimdirSummary.mailSortKey(DATE));
    }

    /** A message attaching one file. */
    private static String source(String messageId, String subject, String name, String type) {
        return "Message-ID: <" + messageId + ">\r\n"
                + "From: a@example.org\r\n"
                + "Subject: " + subject + "\r\n"
                + "Date: Wed, 07 Oct 2026 08:00:00 +0000\r\n"
                + "Content-Type: multipart/mixed; boundary=\"sep\"\r\n\r\n"
                + "--sep\r\nContent-Type: text/plain\r\n\r\nthe body\r\n"
                + "--sep\r\nContent-Type: " + type + "\r\n"
                + "Content-Disposition: attachment; filename=\"" + name + "\"\r\n\r\n"
                + "bytes\r\n--sep--\r\n";
    }

    /** The one message filed in a collection. */
    private MailStore.StoredMessage row(String collection) {
        for (MailStore.StoredMessage message : MailFixtures.merged(store, 10)) {
            if (message.collection.equals(collection)) {
                return message;
            }
        }
        throw new AssertionError("nothing filed in " + collection);
    }

    /** The names of the files the message of a collection attaches. */
    private List<String> attached(String collection) {
        List<String> names = new ArrayList<>();
        for (FileStore.StoredFile file : files.attachments(accountId, row(collection).id)) {
            names.add(file.name);
        }
        return names;
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

    @Test
    public void twoMailboxesReusingAUidKeepTheirOwnAttachments() throws Exception {
        server.list("INBOX", listed("INBOX", "42", "invoice@example.org", "Invoice"));
        server.list("Archive", listed("Archive", "42", "holiday@example.org", "Holiday"));
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
        assertEquals(List.of("invoice.pdf"), attached(inbox));
        assertEquals(List.of("beach.jpg"), attached(archive));
    }

    @Test
    public void theReaderFetchesAMessageByItsUid() throws Exception {
        server.list("Archive", listed("Archive", "42", "holiday@example.org", "Holiday"));
        server.sources.put(
                "Archive 42", source("holiday@example.org", "Holiday", "beach.jpg", "image/jpeg"));
        server.engine.sync(archive);

        MailReader reader =
                new MailReader(store, files, server, new MailStager(pimdir, Clients.real()));
        MailReader.Read read = reader.read(row(archive), () -> null);

        assertTrue(read.fetched);
        assertEquals(List.of("Archive 42"), server.read);
        assertEquals("Holiday", read.body.subject());
        assertEquals(List.of("beach.jpg"), attached(archive));
    }

    @Test
    public void aSentCopyLandsUnderItsArrivalsLinkId() throws Exception {
        synced(sent);
        String body = "Message-ID: <m1@example.com>\r\nSubject: Hi\r\n\r\nbody\r\n";
        server.engine.mutateAdd(
                sent,
                "m1@example.com",
                body,
                Flags.of(Flags.Marker.SEEN),
                PimdirSummary.mail("m1@example.com", "Hi", null, EMAIL, "bob@example.org",
                        DATE, body.length(), false),
                PimdirSummary.mailSortKey(DATE));
        server.list("Sent", listed("Sent", "900", "m1@example.com", "Hi"));

        server.engine.sync(sent);

        assertTrue("landed, not appended: " + server.calls, server.calls.isEmpty());
        assertEquals("1", scalar("SELECT count(*) FROM items WHERE collection = ?", sent));
        assertEquals("900:7:Sent", scalar("SELECT link_id FROM items WHERE collection = ?", sent));
        assertEquals("900", scalar("SELECT handle FROM bindings WHERE collection = ?", sent));
    }

    @Test
    public void aMoveRelocatesItsUidAndWithdrawsItsTarget() throws Exception {
        synced(trash);
        server.list("INBOX", listed("INBOX", "42", "m1@example.org", "Hello"));
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
        server.list("INBOX", listed("INBOX", "42", "inbox@example.org", "Inbox's"));
        server.list("Archive", listed("Archive", "42", "archive@example.org", "Archive's"));
        server.engine.sync(inbox);
        server.engine.sync(archive);

        server.engine.mutateCopy(archive, "42", sent);
        server.engine.sync(sent);

        assertEquals(List.of("copy Archive 42 Sent"), server.calls);
    }

    @Test
    public void aMarkerIsWrittenOnItsUid() throws Exception {
        server.list("INBOX", listed("INBOX", "42", "m1@example.org", "Hello"));
        server.engine.sync(inbox);

        server.engine.mutateFlags(inbox, "42", Flags.of(Flags.Marker.SEEN));
        server.engine.sync(inbox);

        assertEquals(List.of("add \\Seen 42"), server.calls);
    }
}
