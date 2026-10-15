package org.pimalaya.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
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
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Pins the mail read records on the real bridge, against captured literals.
 *
 * <p>Session verbs refuse a loopback's plain imap (rs/client/trust.rs):
 * literals instead (rs/ffi/mail.rs 725, rs/mail.rs 157, rs/mail/listing.rs 257
 * and 535, rs/mail/session.rs 358).
 */
@RunWith(RobolectricTestRunner.class)
public class MailClientTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private final MailClient client = new MailClient();

    /** What each corpus message parses to, null where it cannot be read. */
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

    /**
     * Drafts as the composer hands them, minted date and Message-ID literal.
     *
     * <p>Plain, a reply with every address field, non-ASCII words, absent reply
     * headers (null) and a long line.
     */
    private static final List<ComposeDraft> DRAFTS =
            Arrays.asList(
                    new ComposeDraft(
                            "jane@example.com",
                            "Jane",
                            "bob@example.org",
                            "",
                            "",
                            "Hi",
                            "the body",
                            "Wed, 14 Oct 2026 10:00:00 +0200",
                            "<fixed-16@example.com>",
                            "",
                            ""),
                    new ComposeDraft(
                            "jane@example.com",
                            "",
                            "bob@example.org, carol@example.org",
                            "dan@example.org",
                            "eve@example.org",
                            "Re: Hi",
                            "line one\nline two\n",
                            "Wed, 14 Oct 2026 10:00:00 +0200",
                            "<fixed-16@example.com>",
                            "<a@example.org>",
                            "<root@example.org> <a@example.org>"),
                    new ComposeDraft(
                            "zoe@example.fr",
                            "Zoé Dupré",
                            "Élodie <elodie@example.fr>",
                            "",
                            "",
                            "Réunion de l’équipe — ordre du jour très long qui doit être"
                                    + " replié sur plusieurs lignes",
                            "Bonjour à tous,\n\nvoici l’ordre du jour : café ☕.\n",
                            "Wed, 14 Oct 2026 10:00:00 +0200",
                            "<fixed-14@example.com>",
                            "",
                            ""),
                    new ComposeDraft(
                            "jane@example.com",
                            "Jane",
                            "bob@example.org",
                            "",
                            "",
                            "",
                            "",
                            "Wed, 14 Oct 2026 10:00:00 +0200",
                            "<fixed-16@example.com>",
                            null,
                            null),
                    new ComposeDraft(
                            "jane@example.com",
                            "Doe, Jane",
                            "\"Smith, Bob\" <bob@example.org>",
                            "",
                            "hidden@example.org",
                            "Fwd: report",
                            "a very long line " + "x".repeat(200) + "\n",
                            "Wed, 14 Oct 2026 10:00:00 +0200",
                            "<fixed-16@example.com>",
                            "",
                            ""));

    /** What MailSubmit wrote for each of {@link #DRAFTS}, captured. */
    private static final List<String> DRAFT_JSON =
            List.of(
                    "{\"from\":\"jane@example.com\",\"fromName\":\"Jane\",\"to\":\"bob@examp"
                        + "le.org\",\"cc\":\"\",\"bcc\":\"\",\"subject\":\"Hi\",\"body\":\"the body\",\"d"
                        + "ate\":\"Wed, 14 Oct 2026 10:00:00 +0200\",\"messageId\":\"<fixed-1"
                        + "6@example.com>\",\"inReplyTo\":\"\",\"references\":\"\"}",
                    "{\"from\":\"jane@example.com\",\"fromName\":\"\",\"to\":\"bob@example.o"
                        + "rg, carol@example.org\",\"cc\":\"dan@example.org\",\"bcc\":\"eve@exa"
                        + "mple.org\",\"subject\":\"Re: Hi\",\"body\":\"line one\\nline two\\n\",\""
                        + "date\":\"Wed, 14 Oct 2026 10:00:00 +0200\",\"messageId\":\"<fixed-"
                        + "16@example.com>\",\"inReplyTo\":\"<a@example.org>\",\"references\":"
                        + "\"<root@example.org> <a@example.org>\"}",
                    "{\"from\":\"zoe@example.fr\",\"fromName\":\"Zoé Dupré\",\"to\":\"Élodie"
                        + " <elodie@example.fr>\",\"cc\":\"\",\"bcc\":\"\",\"subject\":\"Réunion de"
                        + " l’équipe — ordre du jour très long qui doit être replié sur"
                        + " plusieurs lignes\",\"body\":\"Bonjour à tous,\\n\\nvoici l’ordre "
                        + "du jour : café ☕.\\n\",\"date\":\"Wed, 14 Oct 2026 10:00:00 +0200"
                        + "\",\"messageId\":\"<fixed-14@example.com>\",\"inReplyTo\":\"\",\"refer"
                        + "ences\":\"\"}",
                    "{\"from\":\"jane@example.com\",\"fromName\":\"Jane\",\"to\":\"bob@examp"
                        + "le.org\",\"cc\":\"\",\"bcc\":\"\",\"subject\":\"\",\"body\":\"\",\"date\":\"Wed,"
                        + " 14 Oct 2026 10:00:00 +0200\",\"messageId\":\"<fixed-16@example."
                        + "com>\"}",
                    "{\"from\":\"jane@example.com\",\"fromName\":\"Doe, Jane\",\"to\":\"\\\"Sm"
                        + "ith, Bob\\\" <bob@example.org>\",\"cc\":\"\",\"bcc\":\"hidden@example."
                        + "org\",\"subject\":\"Fwd: report\",\"body\":\"a very long line xxxxxx"
                        + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                        + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                        + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                        + "xxxxxxxxxxxxxx\\n\",\"date\":\"Wed, 14 Oct 2026 10:00:00 +0200\",\""
                        + "messageId\":\"<fixed-16@example.com>\",\"inReplyTo\":\"\",\"referenc"
                        + "es\":\"\"}");

    /**
     * What the bridge composed of each of {@link #DRAFTS}, base64, captured.
     */
    private static final List<String> COMPOSED =
            List.of(
                    "RGF0ZTogV2VkLCAxNCBPY3QgMjAyNiAxMDowMDowMCArMDIwMA0KTWVzc2Fn"
                        + "ZS1JRDogPGZpeGVkLTE2QGV4YW1wbGUuY29tPg0KRnJvbTogSmFuZSA8amFu"
                        + "ZUBleGFtcGxlLmNvbT4NClRvOiBib2JAZXhhbXBsZS5vcmcNClN1YmplY3Q6"
                        + "IEhpDQpNSU1FLVZlcnNpb246IDEuMA0KQ29udGVudC1UeXBlOiB0ZXh0L3Bs"
                        + "YWluOyBjaGFyc2V0PXV0Zi04DQpDb250ZW50LVRyYW5zZmVyLUVuY29kaW5n"
                        + "OiBxdW90ZWQtcHJpbnRhYmxlDQoNCnRoZSBib2R5DQo=",
                    "RGF0ZTogV2VkLCAxNCBPY3QgMjAyNiAxMDowMDowMCArMDIwMA0KTWVzc2Fn"
                        + "ZS1JRDogPGZpeGVkLTE2QGV4YW1wbGUuY29tPg0KSW4tUmVwbHktVG86IDxh"
                        + "QGV4YW1wbGUub3JnPg0KUmVmZXJlbmNlczogPHJvb3RAZXhhbXBsZS5vcmc+"
                        + "DQogPGFAZXhhbXBsZS5vcmc+DQpGcm9tOiBqYW5lQGV4YW1wbGUuY29tDQpU"
                        + "bzogYm9iQGV4YW1wbGUub3JnLCBjYXJvbEBleGFtcGxlLm9yZw0KQ2M6IGRh"
                        + "bkBleGFtcGxlLm9yZw0KQmNjOiBldmVAZXhhbXBsZS5vcmcNClN1YmplY3Q6"
                        + "IFJlOiBIaQ0KTUlNRS1WZXJzaW9uOiAxLjANCkNvbnRlbnQtVHlwZTogdGV4"
                        + "dC9wbGFpbjsgY2hhcnNldD11dGYtOA0KQ29udGVudC1UcmFuc2Zlci1FbmNv"
                        + "ZGluZzogcXVvdGVkLXByaW50YWJsZQ0KDQpsaW5lIG9uZQ0KbGluZSB0d28N"
                        + "Cg0K",
                    "RGF0ZTogV2VkLCAxNCBPY3QgMjAyNiAxMDowMDowMCArMDIwMA0KTWVzc2Fn"
                        + "ZS1JRDogPGZpeGVkLTE0QGV4YW1wbGUuY29tPg0KRnJvbTogPT9VVEYtOD9C"
                        + "P1dtL0RxU0JFZFhCeXc2az0/PSA8em9lQGV4YW1wbGUuZnI+DQpUbzogPT9V"
                        + "VEYtOD9CP3c0bHNiMlJwWlE9PT89IDxlbG9kaWVAZXhhbXBsZS5mcj4NClN1"
                        + "YmplY3Q6ID0/VVRGLTg/Qj9Vc09wZFc1cGIyND0/PSBkZSA9P1VURi04P0I/"
                        + "Yk9LQW1jT3BjWFZwY0dVZzRvQ1U/PSBvcmRyZSBkdQ0KIGpvdXIgPT9VVEYt"
                        + "OD9CP2RITERxSE09Pz0gbG9uZyBxdWkgZG9pdCA9P1VURi04P0I/dzZwMGNt"
                        + "VWdjbVZ3YkduRHFRPT0/PSBzdXINCiBwbHVzaWV1cnMgbGlnbmVzDQpNSU1F"
                        + "LVZlcnNpb246IDEuMA0KQ29udGVudC1UeXBlOiB0ZXh0L3BsYWluOyBjaGFy"
                        + "c2V0PXV0Zi04DQpDb250ZW50LVRyYW5zZmVyLUVuY29kaW5nOiBxdW90ZWQt"
                        + "cHJpbnRhYmxlDQoNCkJvbmpvdXIgPUMzPUEwIHRvdXMsDQoNCnZvaWNpIGw9"
                        + "RTI9ODA9OTlvcmRyZSBkdSBqb3VyIDogY2FmPUMzPUE5ID1FMj05OD05NS4N"
                        + "Cg0K",
                    "RGF0ZTogV2VkLCAxNCBPY3QgMjAyNiAxMDowMDowMCArMDIwMA0KTWVzc2Fn"
                        + "ZS1JRDogPGZpeGVkLTE2QGV4YW1wbGUuY29tPg0KRnJvbTogSmFuZSA8amFu"
                        + "ZUBleGFtcGxlLmNvbT4NClRvOiBib2JAZXhhbXBsZS5vcmcNClN1YmplY3Q6"
                        + "DQpNSU1FLVZlcnNpb246IDEuMA0KQ29udGVudC1UeXBlOiB0ZXh0L3BsYWlu"
                        + "OyBjaGFyc2V0PXV0Zi04DQpDb250ZW50LVRyYW5zZmVyLUVuY29kaW5nOiBx"
                        + "dW90ZWQtcHJpbnRhYmxlDQoNCg0K",
                    "RGF0ZTogV2VkLCAxNCBPY3QgMjAyNiAxMDowMDowMCArMDIwMA0KTWVzc2Fn"
                        + "ZS1JRDogPGZpeGVkLTE2QGV4YW1wbGUuY29tPg0KRnJvbTogRG9lLCBKYW5l"
                        + "IDxqYW5lQGV4YW1wbGUuY29tPg0KVG86ICJTbWl0aCwgQm9iIiA8Ym9iQGV4"
                        + "YW1wbGUub3JnPg0KQmNjOiBoaWRkZW5AZXhhbXBsZS5vcmcNClN1YmplY3Q6"
                        + "IEZ3ZDogcmVwb3J0DQpNSU1FLVZlcnNpb246IDEuMA0KQ29udGVudC1UeXBl"
                        + "OiB0ZXh0L3BsYWluOyBjaGFyc2V0PXV0Zi04DQpDb250ZW50LVRyYW5zZmVy"
                        + "LUVuY29kaW5nOiBxdW90ZWQtcHJpbnRhYmxlDQoNCmEgdmVyeSBsb25nIGxp"
                        + "bmUgeHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4"
                        + "eHh4eHh4eHh4eHh4eHh4eD0NCnh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4"
                        + "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4"
                        + "eHh4eD0NCnh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4"
                        + "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHgNCg0K");

    @Test
    public void aDraftIsWrittenAsBefore() throws Exception {
        for (int index = 0; index < DRAFTS.size(); index++) {
            assertSameJson(DRAFT_JSON.get(index), DRAFTS.get(index).toJson().toString());
        }
    }

    @Test
    public void aDraftComposesToTheSameBytes() {
        for (int index = 0; index < DRAFTS.size(); index++) {
            assertArrayEquals(
                    "draft " + index,
                    Base64.getDecoder().decode(COMPOSED.get(index)),
                    client.compose(DRAFTS.get(index)));
        }
    }

    @Test
    public void aStepRequestIsWrittenAsTheBridgeReadsIt() throws Exception {
        assertSameJson(
                "{\"count\":50,\"frontier\":null,\"bound\":null,\"anchors\":[]}",
                new StepRequest("Clients", 50, null, null, List.of(), null).toJson().toString());
        assertSameJson(
                "{\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":\"2026-06-30T22:00:00Z\","
                        + "\"anchors\":[\"a2\",\"a1\"],\"mailboxes\":[\"INBOX\",\"Sent\"]}",
                new StepRequest(
                                null,
                                50,
                                "2099-01-01T00:00:00Z",
                                "2026-06-30T22:00:00Z",
                                List.of("a2", "a1"),
                                List.of("INBOX", "Sent"))
                        .toJson()
                        .toString());
        assertSameJson(
                "{\"count\":50,\"frontier\":null,\"bound\":null,\"anchors\":[],\"mailboxes\":[]}",
                new StepRequest(null, 50, null, null, List.of(), List.of()).toJson().toString());
    }

    @Test
    public void aGmailOrJmapStepReadsAsBefore() throws Exception {
        String jmap =
                "{\"anchors\":[\"e2\",\"e1\"],\"byReception\":false,\"exhausted\":true,"
                        + "\"historyId\":\"s42\",\"items\":[{\"flags\":[\"\\\\Seen\"],"
                        + "\"handle\":\"e1\",\"linkId\":\"e1\",\"mailboxes\":[\"INBOX\",\"Clients\"],"
                        + "\"path\":\"/d/e1\",\"received\":1767690000000,\"size\":120,"
                        + "\"sortKey\":\"2026-01-06T08:30:00Z\",\"summary\":{\"subject\":\"Lunch\"}},"
                        + "{\"flags\":[],\"handle\":\"e2\",\"linkId\":\"e2\",\"mailboxes\":[],"
                        + "\"path\":\"/d/e2\",\"received\":null,\"size\":0}]}";
        assertMirrors(NamedStep.class, jmap);
        assertMirrors(NamedStepItem.class, elements(field(jmap, "items")).get(0));
        NamedStep step = NamedStep.from(Replies.object(jmap));
        assertEquals(List.of("e2", "e1"), step.anchors());
        assertEquals(false, step.byReception());
        assertEquals(true, step.exhausted());
        assertEquals("s42", step.historyId());
        assertEquals(2, step.items().size());
        NamedStepItem first = step.items().get(0);
        assertEquals(List.of("\\Seen"), first.flags());
        assertEquals("e1", first.handle());
        assertEquals("e1", first.linkId());
        assertEquals(List.of("INBOX", "Clients"), first.mailboxes());
        assertEquals("/d/e1", first.path());
        assertEquals(Long.valueOf(1767690000000L), first.received());
        assertEquals(120, first.size());
        assertEquals("2026-01-06T08:30:00Z", first.sortKey());
        assertSameJson("{\"subject\":\"Lunch\"}", first.summary().toString());
        NamedStepItem second = step.items().get(1);
        assertEquals(List.of(), second.flags());
        assertEquals(List.of(), second.mailboxes());
        assertEquals(null, second.received());
        assertEquals(0, second.size());
        assertEquals("absent reads empty, as optString did", "", second.sortKey());
        assertEquals(null, second.summary());

        String gmail =
                "{\"byReception\":true,\"exhausted\":false,\"historyId\":\"981\",\"items\":[]}";
        NamedStep labelled = NamedStep.from(Replies.object(gmail));
        assertEquals("Gmail answers no anchors", null, labelled.anchors());
        assertEquals(true, labelled.byReception());
        assertEquals(false, labelled.exhausted());
        assertEquals("981", labelled.historyId());
        assertEquals(List.of(), labelled.items());
    }

    @Test
    public void aGraphStepReadsAsBefore() throws Exception {
        String raw =
                "{\"exhausted\":false,\"items\":[{\"handle\":\"AAM1\",\"mailboxes\":[\"Inbox\"],"
                        + "\"path\":\"/d/AAM1\",\"received\":1767690000000,\"size\":2048,"
                        + "\"sortKey\":\"2026-01-06T08:30:00Z\"},{\"handle\":\"AAM2\","
                        + "\"mailboxes\":[null],\"received\":null,\"size\":null,"
                        + "\"sortKey\":\"2026-01-05T08:30:00Z\"}]}";
        assertMirrors(GraphStepReply.class, raw);
        assertMirrors(GraphStepItem.class, elements(field(raw, "items")).get(0));
        assertEquals(
                new GraphStepReply(
                        false,
                        List.of(
                                new GraphStepItem(
                                        "AAM1",
                                        List.of("Inbox"),
                                        "/d/AAM1",
                                        1767690000000L,
                                        2048L,
                                        "2026-01-06T08:30:00Z"),
                                new GraphStepItem(
                                        "AAM2",
                                        Arrays.asList((String) null),
                                        null,
                                        null,
                                        null,
                                        "2026-01-05T08:30:00Z"))),
                GraphStepReply.from(Replies.object(raw)));
    }

    /** A round's page as rs/mail/listing.rs writes it (its own test pins it). */
    private static final String ROUND =
            "{\"complete\":true,\"cursor\":\"7:8\",\"items\":[{\"flags\":[\"\\\\Seen\"],"
                    + "\"handle\":\"7\",\"linkId\":\"7\",\"sortKey\":\"2026-10-01T08:00:00Z\","
                    + "\"summary\":{\"mail\":{\"addresses\":[],\"date\":\"2026-10-01T08:00:00Z\","
                    + "\"in_reply_to\":[],\"message_id\":\"m1@example.org\",\"subject\":\"Hi\"}}},"
                    + "{\"flags\":[],\"handle\":\"8\",\"linkId\":\"8\"}],\"last\":false,"
                    + "\"vanished\":[]}";

    @Test
    public void aListingPageReadsAsTheBridgeWritesIt() throws Exception {
        assertMirrors(
                MailPage.class,
                "{\"checkpoint\":\"1:42\",\"complete\":false,\"cursor\":null,"
                        + "\"cursorRejected\":true,\"items\":[],\"last\":true,\"vanished\":[]}");
        assertMirrors(Named.class, elements(field(ROUND, "items")).get(0));

        MailPage round = MailPage.from(Replies.object(ROUND));
        assertEquals(null, round.checkpoint());
        assertEquals(true, round.complete());
        assertEquals("7:8", round.cursor());
        assertEquals(false, round.cursorRejected());
        assertEquals(false, round.last());
        assertEquals(List.of(), round.vanished());
        Named named = round.items().get(0);
        assertEquals(List.of("\\Seen"), named.flags());
        assertEquals("7", named.handle());
        assertEquals("7", named.linkId());
        assertEquals("2026-10-01T08:00:00Z", named.sortKey());
        assertEquals(
                "m1@example.org",
                named.summary().getJSONObject("mail").getString("message_id"));
        Named bare = round.items().get(1);
        assertEquals("absent reads empty, as optString did", "", bare.sortKey());
        assertEquals(null, bare.summary());
        assertSameJson(ROUND, round.toJson().toString());

        String delta =
                "{\"checkpoint\":\"1:42\",\"complete\":false,\"items\":[],\"last\":true,"
                        + "\"vanished\":[\"6\"]}";
        assertSameJson(delta, MailPage.from(Replies.object(delta)).toJson().toString());
        String rejected =
                "{\"complete\":false,\"cursorRejected\":true,\"items\":[],\"last\":false,"
                        + "\"vanished\":[]}";
        assertSameJson(rejected, MailPage.from(Replies.object(rejected)).toJson().toString());
        assertEquals(
                "a page saying nothing of last closes its listing, as the engine reads it",
                true,
                MailPage.from(Replies.object("{\"items\":[]}")).last());
    }

    @Test
    public void aListingRequestIsWrittenAsTheBridgeReadsIt() throws Exception {
        MailRequest round =
                new MailRequest(
                        false,
                        new MailRequest.Listing("round", null, null, false),
                        null,
                        new MailRequest.Scope(null, null));
        assertSameJson(
                "{\"listing\":{\"kind\":\"round\",\"cursor\":null,\"band\":false},"
                        + "\"scope\":{},\"covered\":false}",
                round.toJson().toString());
        MailRequest delta =
                new MailRequest(
                        true,
                        new MailRequest.Listing("delta", "1:42", null, false),
                        1767690000000L,
                        new MailRequest.Scope("2026-04-01T00:00:00Z", "2026-05-01T00:00:00Z"));
        assertSameJson(
                "{\"listing\":{\"kind\":\"delta\",\"checkpoint\":\"1:42\"},"
                        + "\"scope\":{\"since\":\"2026-04-01T00:00:00Z\","
                        + "\"until\":\"2026-05-01T00:00:00Z\"},\"covered\":true,"
                        + "\"received\":1767690000000}",
                delta.toJson().toString());

        // NOTE: the bridge reads the request before the session, so a closed
        // one answers for a request it could read.
        for (MailRequest request : List.of(round, delta)) {
            String reply = Native.enumerateMailbox(null, 0, "INBOX", request.toJson().toString());
            assertEquals("This mail session is closed", new JSONObject(reply).getString("error"));
        }
        String unread = Native.enumerateMailbox(null, 0, "INBOX", "{}");
        assertTrue(new JSONObject(unread).getString("error").startsWith("Invalid listing request"));
    }
}
