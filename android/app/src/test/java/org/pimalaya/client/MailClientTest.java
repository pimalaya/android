package org.pimalaya.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.pimalaya.client.ReplyShape.assertMirrors;
import static org.pimalaya.client.ReplyShape.assertSameJson;
import static org.pimalaya.client.ReplyShape.elements;
import static org.pimalaya.client.ReplyShape.field;
import static org.pimalaya.client.ReplyShape.keys;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Pins the mail read records on the real bridge: a corpus of crafted
 * messages parsed, described and cut into parts, each reply parsed to the
 * values the hand parsers the records replaced produced (literals
 * captured on that code), and every record mirroring its reply.
 *
 * <p>The session verbs (openMail, listMailboxes, floor, fetchSources)
 * cannot run here: the bridge refuses to sign in over the plain
 * {@code imap} a loopback server speaks (rs/client/trust.rs
 * {@code require_tls}), and the transport takes TLS from the platform
 * trust store. Their records are pinned on literal replies shaped as the
 * Rust structs serialize them (rs/ffi/mail.rs 725, rs/mail.rs 157,
 * rs/mail/listing.rs 257 and its test at 535, rs/mail/session.rs 358),
 * parsed to the values the replaced parsers read off the same literals.
 */
@RunWith(RobolectricTestRunner.class)
public class MailClientTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private final MailClient client = new MailClient();

    /** What each message of the corpus parses to, null where it cannot be read. */
    private static final List<MessageBody> PARSED =
            Arrays.asList(
                    new MessageBody(
                            "Lunch",
                            "Alice Martin",
                            "alice@example.org",
                            "bob@example.org",
                            "",
                            "2026-01-06T09:30:00+01:00",
                            "plain",
                            "See you at noon.\r\n",
                            List.of(),
                            false),
                    new MessageBody(
                            "Agenda",
                            "Doe, Jane",
                            "jane@example.org",
                            "Bob <bob@example.org>, carol@example.org",
                            "Dan <dan@example.org>",
                            "2026-01-07T18:00:00-05:00",
                            "html",
                            "<p>HTML <b>agenda</b></p>",
                            List.of(),
                            false),
                    new MessageBody(
                            "Invoice",
                            "",
                            "billing@example.org",
                            "bob@example.org",
                            "",
                            "2026-01-08T10:00:00Z",
                            "plain",
                            "Your invoice.",
                            List.of(
                                    new MessageBody.Attachment(
                                            "invoice.pdf",
                                            "application/pdf",
                                            19L,
                                            "2"),
                                    new MessageBody.Attachment("résumé.txt", "text/plain", 5L, "3"),
                                    new MessageBody.Attachment(
                                            "",
                                            "application/octet-stream",
                                            3L,
                                            "4")),
                            true),
                    new MessageBody(
                            "Newsletter",
                            "",
                            "news@example.org",
                            "bob@example.org",
                            "",
                            "2026-01-09T07:00:00Z",
                            "html",
                            "<img src=\"cid:logo@x\">",
                            List.of(),
                            false),
                    new MessageBody(
                            "Café et crème",
                            "René Dupré",
                            "rene@example.fr",
                            "Élodie <elodie@example.fr>, x@example.fr",
                            "Zürich <zh@example.ch>",
                            "2026-01-10T12:00:00+02:00",
                            "plain",
                            "Café crème\r\n",
                            List.of(),
                            false),
                    new MessageBody(
                            "Review",
                            "Alice",
                            "alice@example.org",
                            "bob@example.org",
                            "",
                            "2026-01-11T08:00:00Z",
                            "plain",
                            "Join us",
                            List.of(
                                    new MessageBody.Attachment(
                                            "invite.ics",
                                            "application/ics",
                                            30L,
                                            "2")),
                            true),
                    new MessageBody(
                            "Fwd: Lunch",
                            "",
                            "bob@example.org",
                            "carol@example.org",
                            "",
                            "2026-01-12T10:00:00Z",
                            "plain",
                            "Forwarding.",
                            List.of(
                                    new MessageBody.Attachment(
                                            "lunch.eml",
                                            "message/rfc822",
                                            67L,
                                            "2")),
                            true),
                    new MessageBody(
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "plain",
                            "just a body\r\n",
                            List.of(),
                            false),
                    new MessageBody(
                            "Broken",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            List.of(),
                            false),
                    null,
                    new MessageBody(
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            List.of(),
                            false));

    /** What each message of the corpus describes to, its summary apart. */
    private static final List<Description> DESCRIBED =
            List.of(
                    new Description(
                            List.of(),
                            "6b5huqsihshplsmfkrnpwjl2km",
                            210L,
                            null),
                    new Description(
                            List.of(),
                            "vs6e3yurjrnpibxr3yaifyxsia",
                            390L,
                            null),
                    new Description(
                            List.of(
                                    new MessageBody.Attachment(
                                            "invoice.pdf",
                                            "application/pdf",
                                            19L,
                                            "2"),
                                    new MessageBody.Attachment("résumé.txt", "text/plain", 5L, "3"),
                                    new MessageBody.Attachment(
                                            "",
                                            "application/octet-stream",
                                            3L,
                                            "4")),
                            "utwmwcojlrarw7winioihfxg3a",
                            611L,
                            null),
                    new Description(
                            List.of(),
                            "fvivl5gez3v64x6sk6ykcmcb2q",
                            378L,
                            null),
                    new Description(
                            List.of(),
                            "2i34y4dkvspqcdeg3hz3cxf6ji",
                            357L,
                            null),
                    new Description(
                            List.of(
                                    new MessageBody.Attachment(
                                            "invite.ics",
                                            "application/ics",
                                            30L,
                                            "2")),
                            "vwcbov6ehy6fq6d4fgwcu5ssuy",
                            656L,
                            null),
                    new Description(
                            List.of(
                                    new MessageBody.Attachment(
                                            "lunch.eml",
                                            "message/rfc822",
                                            67L,
                                            "2")),
                            "3v2g4sye43d4awgd677ifahuxm",
                            375L,
                            null),
                    new Description(
                            List.of(),
                            "pug7fhjak5ea4cexrtjlq4fwie",
                            15L,
                            null),
                    new Description(
                            List.of(),
                            "bxpmxdd7ojyioai54hvl56r6km",
                            133L,
                            null),
                    new Description(
                            null,
                            "4oymiquy7qobjgx36tejs35zeq",
                            0L,
                            null),
                    new Description(
                            List.of(),
                            "cety7qeb6qkjdnrwpht5inbbyu",
                            8L,
                            null));

    /** The summary each message of the corpus describes to, as text. */
    private static final List<String> SUMMARIES =
            List.of(
                    "{\"mail\":{\"addresses\":[{\"address\":\"alice@example.org\","
                            + "\"name\":\"Alice Martin\",\"position\":0,\"role\":\"from\"},{"
                            + "\"address\":\"bob@example.org\",\"position\":0,\"role\":\"to\""
                            + "}],\"attachment\":false,\"date\":\"2026-01-06T08:30:00Z\",\"in"
                            + "_reply_to\":[],\"message_id\":\"plain@example.org\",\"sender\""
                            + ":\"alice@example.org\",\"sender_name\":\"Alice Martin\",\"size"
                            + "\":210,\"subject\":\"Lunch\"}}",
                    "{\"mail\":{\"addresses\":[{\"address\":\"jane@example.org\",\""
                            + "name\":\"Doe, Jane\",\"position\":0,\"role\":\"from\"},{\"addr"
                            + "ess\":\"bob@example.org\",\"name\":\"Bob\",\"position\":0,\"ro"
                            + "le\":\"to\"},{\"address\":\"carol@example.org\",\"position\":1"
                            + ",\"role\":\"to\"},{\"address\":\"dan@example.org\",\"name\":\""
                            + "Dan\",\"position\":0,\"role\":\"cc\"}],\"attachment\":false,\""
                            + "date\":\"2026-01-07T23:00:00Z\",\"in_reply_to\":[],\"sender\":"
                            + "\"jane@example.org\",\"sender_name\":\"Doe, Jane\",\"size\":39"
                            + "0,\"subject\":\"Agenda\"}}",
                    "{\"mail\":{\"addresses\":[{\"address\":\"billing@example.org\""
                            + ",\"position\":0,\"role\":\"from\"},{\"address\":\"bob@example."
                            + "org\",\"position\":0,\"role\":\"to\"}],\"attachment\":true,\"d"
                            + "ate\":\"2026-01-08T10:00:00Z\",\"in_reply_to\":[],\"sender\":"
                            + "\"billing@example.org\",\"size\":611,\"subject\":\"Invoice\"}}",
                    "{\"mail\":{\"addresses\":[{\"address\":\"news@example.org\",\""
                            + "position\":0,\"role\":\"from\"},{\"address\":\"bob@example.org"
                            + "\",\"position\":0,\"role\":\"to\"}],\"attachment\":false,\"dat"
                            + "e\":\"2026-01-09T07:00:00Z\",\"in_reply_to\":[],\"sender\":\"n"
                            + "ews@example.org\",\"size\":378,\"subject\":\"Newsletter\"}}",
                    "{\"mail\":{\"addresses\":[{\"address\":\"rene@example.fr\",\"n"
                            + "ame\":\"René Dupré\",\"position\":0,\"role\":\"from\"},{\"addr"
                            + "ess\":\"elodie@example.fr\",\"name\":\"Élodie\",\"position\":0"
                            + ",\"role\":\"to\"},{\"address\":\"x@example.fr\",\"position\":1"
                            + ",\"role\":\"to\"},{\"address\":\"zh@example.ch\",\"name\":\"Zü"
                            + "rich\",\"position\":0,\"role\":\"cc\"}],\"attachment\":false,"
                            + "\"date\":\"2026-01-10T10:00:00Z\",\"in_reply_to\":[],\"sender"
                            + "\":\"rene@example.fr\",\"sender_name\":\"René Dupré\",\"size\""
                            + ":357,\"subject\":\"Café et crème\"}}",
                    "{\"mail\":{\"addresses\":[{\"address\":\"alice@example.org\","
                            + "\"name\":\"Alice\",\"position\":0,\"role\":\"from\"},{\"addres"
                            + "s\":\"bob@example.org\",\"position\":0,\"role\":\"to\"}],\"att"
                            + "achment\":true,\"date\":\"2026-01-11T08:00:00Z\",\"in_reply_to"
                            + "\":[],\"invitation\":\"ev-1\",\"message_id\":\"inv@example.org"
                            + "\",\"sender\":\"alice@example.org\",\"sender_name\":\"Alice\","
                            + "\"size\":656,\"subject\":\"Review\"}}",
                    "{\"mail\":{\"addresses\":[{\"address\":\"bob@example.org\",\"p"
                            + "osition\":0,\"role\":\"from\"},{\"address\":\"carol@example.or"
                            + "g\",\"position\":0,\"role\":\"to\"}],\"attachment\":true,\"dat"
                            + "e\":\"2026-01-12T10:00:00Z\",\"in_reply_to\":[],\"sender\":\"b"
                            + "ob@example.org\",\"size\":375,\"subject\":\"Fwd: Lunch\"}}",
                    "{\"mail\":{\"addresses\":[],\"attachment\":false,\"in_reply_to"
                            + "\":[],\"size\":15,\"subject\":\"\"}}",
                    "{\"mail\":{\"addresses\":[],\"attachment\":false,\"in_reply_to"
                            + "\":[],\"size\":133,\"subject\":\"Broken\"}}",
                    "{\"mail\":{\"addresses\":[],\"attachment\":false,\"in_reply_to"
                            + "\":[],\"size\":0,\"subject\":\"\"}}",
                    "{\"mail\":{\"addresses\":[],\"attachment\":false,\"in_reply_to"
                            + "\":[],\"size\":8,\"subject\":\"\"}}");

    private File file(int index) throws Exception {
        File file = folder.newFile();
        Files.write(file.toPath(), MessageCorpus.MESSAGES.get(index));
        return file;
    }

    @Test
    public void everyMessageParsesAsBefore() throws Exception {
        for (int index = 0; index < MessageCorpus.MESSAGES.size(); index++) {
            byte[] message = MessageCorpus.MESSAGES.get(index);
            MessageBody expected = PARSED.get(index);
            if (expected == null) {
                assertEquals(
                        "Could not read the message",
                        assertThrows(PimalayaException.class, () -> client.parseMessage(message))
                                .getMessage());
                File file = file(index);
                assertEquals(
                        "Could not read the message",
                        assertThrows(PimalayaException.class, () -> client.parseMessage(file))
                                .getMessage());
                continue;
            }
            assertEquals("message " + index, expected, client.parseMessage(message));
            assertEquals("message " + index, expected, client.parseMessage(file(index)));
        }
    }

    @Test
    public void aParseMirrorsItsReply() {
        String raw = Native.parseMessage(MessageCorpus.MESSAGES.get(2));
        assertMirrors(MessageBody.class, raw);
        assertMirrors(MessageBody.Attachment.class, elements(field(raw, "attachments")).get(0));
    }

    @Test
    public void everyMessageDescribesAsBefore() throws Exception {
        for (int index = 0; index < MessageCorpus.MESSAGES.size(); index++) {
            Description described = client.describeMessage(file(index));
            Description expected = DESCRIBED.get(index);
            assertEquals("message " + index, expected.attachments(), described.attachments());
            assertEquals("message " + index, expected.hash(), described.hash());
            assertEquals("message " + index, expected.size(), described.size());
            assertSameJson(SUMMARIES.get(index), described.summary().toString());
        }
    }

    @Test
    public void aDescriptionMirrorsItsReply() throws Exception {
        String raw = Native.describeMessage(file(2).getAbsolutePath());
        assertMirrors(Description.class, raw);
        // NOTE: a description lists its attachments with sorted keys, a
        // parse in field order: the same four fields.
        assertEquals(
                List.of("mime", "name", "part", "size"),
                keys(elements(field(raw, "attachments")).get(0)));
        assertEquals(
                "null", field(Native.describeMessage(file(9).getAbsolutePath()), "attachments"));
    }

    @Test
    public void everyAttachmentReadsBackByItsSection() throws Exception {
        File invoice = file(2);
        assertPart(
                invoice, "2", Base64.getDecoder().decode("JVBERi0xLjQKJcOkw7zDtsOfCg=="));
        assertPart(invoice, "3", "notes".getBytes(StandardCharsets.UTF_8));
        assertPart(invoice, "4", "raw".getBytes(StandardCharsets.UTF_8));
        assertPart(
                file(5), "2", "BEGIN:VCALENDAR\r\nEND:VCALENDAR".getBytes(StandardCharsets.UTF_8));
        assertPart(
                file(6),
                "2",
                "From: Alice <alice@example.org>\r\nSubject: Lunch\r\n\r\nSee you at noon."
                        .getBytes(StandardCharsets.UTF_8));

        for (int index = 0; index < MessageCorpus.MESSAGES.size(); index++) {
            File source = file(index);
            File target = folder.newFile("x" + index);
            assertEquals(
                    index == 9 ? "Could not read the message" : "No part 99 in the message",
                    assertThrows(
                                    PimalayaException.class,
                                    () -> client.messagePart(source, "99", target))
                            .getMessage());
        }
    }

    private void assertPart(File source, String part, byte[] expected) throws Exception {
        File target = folder.newFile();
        assertEquals(expected.length, client.messagePart(source, part, target));
        assertArrayEquals(expected, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void mailboxesReadAsBefore() throws Exception {
        String raw =
                "[{\"name\":\"INBOX\",\"role\":\"inbox\"},{\"name\":\"Trash\",\"role\":\"trash\"},"
                        + "{\"name\":\"Projets/Alpha\",\"role\":\"\"}]";
        assertMirrors(Mailbox.class, elements(raw).get(0));
        assertEquals(
                List.of(
                        new Mailbox("INBOX", "inbox"),
                        new Mailbox("Trash", "trash"),
                        new Mailbox("Projets/Alpha", "")),
                Mailbox.list(Replies.array(raw)));
        assertEquals(List.of(), Mailbox.list(Replies.array("[]")));
    }

    @Test
    public void aFloorReadsAsBefore() {
        String whole =
                "{\"floor\":null,\"dated\":3,\"dates\":[\"2026-09-03T00:00:00Z\","
                        + "\"2026-09-02T00:00:00Z\",\"2026-09-01T00:00:00Z\"]}";
        assertMirrors(FloorReply.class, whole);
        assertEquals(
                new FloorReply(
                        null,
                        3,
                        List.of(
                                "2026-09-03T00:00:00Z",
                                "2026-09-02T00:00:00Z",
                                "2026-09-01T00:00:00Z")),
                FloorReply.from(Replies.object(whole)));
        assertEquals(
                new FloorReply(
                        "2026-09-01T00:00:00Z",
                        2,
                        List.of("2026-09-02T00:00:00Z", "2026-09-01T00:00:00Z")),
                FloorReply.from(
                        Replies.object(
                                "{\"floor\":\"2026-09-01T00:00:00Z\",\"dated\":2,\"dates\":"
                                        + "[\"2026-09-02T00:00:00Z\",\"2026-09-01T00:00:00Z\"]}")));
        assertEquals(
                new FloorReply(null, 0, List.of()),
                FloorReply.from(Replies.object("{\"floor\":null,\"dated\":0,\"dates\":[]}")));
    }

    @Test
    public void sourcesReadAsBefore() {
        assertEquals(
                new MessageSources(List.of(), List.of()),
                MessageSources.from(Replies.object("{\"gone\":[],\"items\":[]}")));

        String plain =
                "{\"gone\":[\"7\"],\"items\":[{\"id\":\"3\",\"path\":\"/d/3\",\"size\":120},"
                        + "{\"id\":\"4\",\"path\":\"/d/4\",\"size\":0}]}";
        assertMirrors(MessageSources.class, plain);
        // NOTE: a Plain source carries neither labels nor a reception time.
        assertEquals(
                List.of("id", "path", "size"), keys(elements(field(plain, "items")).get(0)));
        assertEquals(
                new MessageSources(
                        List.of("7"),
                        List.of(
                                new MessageSource("3", null, "/d/3", null, 120),
                                new MessageSource("4", null, "/d/4", null, 0))),
                MessageSources.from(Replies.object(plain)));

        String labelled =
                "{\"gone\":[\"g1\"],\"items\":[{\"id\":\"18c\",\"labels\":[\"INBOX\",\"Label_1\"],"
                        + "\"path\":\"/d/18c\",\"received\":1767690000000,\"size\":2048},"
                        + "{\"id\":\"18d\",\"labels\":[],\"path\":\"/d/18d\",\"received\":null,"
                        + "\"size\":5}]}";
        assertMirrors(MessageSource.class, elements(field(labelled, "items")).get(0));
        assertEquals(
                new MessageSources(
                        List.of("g1"),
                        List.of(
                                new MessageSource(
                                        "18c",
                                        List.of("INBOX", "Label_1"),
                                        "/d/18c",
                                        1767690000000L,
                                        2048),
                                new MessageSource("18d", List.of(), "/d/18d", null, 5))),
                MessageSources.from(Replies.object(labelled)));
    }

    @Test
    public void anOpenedSessionReadsAsBefore() {
        String raw = "{\"expungesOne\":false,\"handle\":140234567890}";
        assertMirrors(Opened.class, raw);
        assertEquals(new Opened(false, 140234567890L), Opened.from(Replies.object(raw)));
        assertEquals(
                new Opened(true, 1),
                Opened.from(Replies.object("{\"expungesOne\":true,\"handle\":1}")));
    }
}
