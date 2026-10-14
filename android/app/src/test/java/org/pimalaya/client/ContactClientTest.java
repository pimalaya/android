package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.pimalaya.client.ReplyShape.assertMirrors;
import static org.pimalaya.client.ReplyShape.assertSameJson;

import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Pins the contact network records: each reply parsed to the values the
 * hand parsers the records replaced produced (literals captured on that
 * code), every record mirroring its reply, and the push changes written as
 * the JSON the engine used to build.
 *
 * <p>The network verbs cannot run here: the bridge refuses to send
 * credentials over the plain {@code http} a loopback server speaks, and the
 * transport takes TLS from the platform trust store. Their records are
 * pinned on literal replies shaped as the Rust structs serialize them
 * (rs/contact.rs 45, 68, 89; rs/contact/push.rs 13, 37), and the push
 * inputs on a CardDAV account, which the bridge reads and then refuses
 * before any request.
 */
@RunWith(RobolectricTestRunner.class)
public class ContactClientTest {
    private static final Account DAV = new Account("https://dav.example.org/", "u", "p");

    private static String vcard(String uid, String name) {
        return "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:uid-" + uid + "\r\nFN:" + name
                + "\r\nEND:VCARD\r\n";
    }

    @Test
    public void anAddressbookReadsAsTheBridgeListsIt() throws Exception {
        String raw =
                "{\"id\":\"work\",\"name\":\"Work\",\"url\":\"https://dav.example.org/work/\","
                        + "\"description\":\"Team\",\"color\":\"#ff0000\",\"role\":\"default\","
                        + "\"writable\":false}";
        assertMirrors(Addressbook.class, raw);
        assertEquals(
                new Addressbook(
                        "work", "Work", "https://dav.example.org/work/", "Team", "#ff0000",
                        Calendar.DEFAULT, false),
                Addressbook.from(new JSONObject(raw)));

        // NOTE: what the source does not say is no description or colour,
        // no role and writable.
        assertEquals(
                new Addressbook("bare", "bare", "https://dav.example.org/bare/", null, null),
                Addressbook.from(
                        new JSONObject(
                                "{\"id\":\"bare\",\"name\":\"bare\","
                                        + "\"url\":\"https://dav.example.org/bare/\","
                                        + "\"description\":null,\"color\":null}")));
    }

    @Test
    public void aCardReadsAsTheBridgeListsIt() throws Exception {
        String raw =
                "{\"id\":\"a\",\"uri\":\"a.vcf\",\"etag\":\"\\\"e1\\\"\",\"vcard\":\"V\","
                        + "\"books\":[\"b1\",\"b2\"]}";
        assertMirrors(Card.class, raw);
        assertEquals(
                new Card("a", "a.vcf", "\"e1\"", "V", List.of("b1", "b2")),
                Card.from(new JSONObject(raw)));

        // NOTE: a spine-only row, and a card of a collection-bound backend
        // (no books key, skip_serializing_if).
        assertEquals(
                List.of(new Card("b", "b", null, ""), new Card("c", "c", null, "V")),
                Card.list(
                        new JSONArray(
                                "[{\"id\":\"b\",\"uri\":\"b\",\"etag\":null,\"vcard\":\"\"},"
                                        + "{\"id\":\"c\",\"uri\":\"c\",\"vcard\":\"V\","
                                        + "\"books\":[]}]")));
    }

    @Test
    public void aDeltaReadsAsTheBridgeListsIt() throws Exception {
        String raw =
                "{\"changed\":[{\"id\":\"a\",\"uri\":\"a.vcf\",\"etag\":\"e1\",\"vcard\":\"\"}],"
                        + "\"vanished\":[\"gone.vcf\"],\"token\":\"t2\",\"complete\":true}";
        assertMirrors(CardDelta.class, raw);
        CardDelta delta = CardDelta.from(new JSONObject(raw));
        assertEquals(
                new CardDelta(
                        List.of(new Card("a", "a.vcf", "e1", "")),
                        List.of("gone.vcf"),
                        "t2",
                        true),
                delta);

        CardDelta none =
                CardDelta.from(
                        new JSONObject(
                                "{\"changed\":[],\"vanished\":[],\"token\":null,"
                                        + "\"complete\":false}"));
        assertEquals(new CardDelta(List.of(), List.of(), null, false), none);
        assertNull(none.token());
    }

    @Test
    public void outcomesReadAsAPushRoundAnswersThem() throws Exception {
        // NOTE: Rust skips the absent options, so no outcome carries all five
        // keys; the shape check reads a literal that does.
        assertMirrors(
                PushOutcome.class,
                "{\"ref\":\"r\",\"accepted\":false,\"id\":\"i\",\"etag\":\"e\",\"error\":\"x\"}");

        String raw =
                "[{\"ref\":\"n1\",\"accepted\":true,\"id\":\"srv-n1\"},"
                        + "{\"ref\":\"b\",\"accepted\":true,\"etag\":\"k-b\"},"
                        + "{\"ref\":\"c\",\"accepted\":false,\"error\":\"refused\"},"
                        + "{\"ref\":\"d\",\"accepted\":true}]";
        assertEquals(
                List.of(
                        new PushOutcome("n1", true, "srv-n1", null, null),
                        new PushOutcome("b", true, null, "k-b", null),
                        new PushOutcome("c", false, null, null, "refused"),
                        new PushOutcome("d", true, null, null, null)),
                PushOutcome.list(new JSONArray(raw)));
    }

    @Test
    public void pushChangesWriteTheJsonTheEngineBuilt() throws Exception {
        String a = vcard("a", "A");
        String b = vcard("b", "B");
        String edited = vcard("b", "B edited");
        String one = vcard("n1", "New one");
        String two = vcard("n2", "New two");
        List<PushChange> changes =
                List.of(
                        new PushChange("n1", PushOp.CREATE, null, one, null, List.of(), List.of()),
                        new PushChange("a", PushOp.CREATE, null, a, null, List.of(), List.of()),
                        new PushChange(
                                "a", PushOp.BOOKS, "uid-a", null, null, List.of("b1"), List.of()),
                        new PushChange(
                                "b", PushOp.UPDATE, "uid-b", edited, b, List.of(), List.of()),
                        new PushChange(
                                "c", PushOp.DESTROY, "uid-c", null, null, List.of(), List.of()),
                        new PushChange(
                                "d", PushOp.BOOKS, "uid-d", null, null, List.of(), List.of("b1")),
                        new PushChange("n2", PushOp.UPDATE, "n2", two, null, List.of(), List.of()));

        // NOTE: the JSON the engine's round built for these changes, captured.
        String[] built = {
            "{\"ref\":\"n1\",\"op\":\"create\",\"vcard\":\"BEGIN:VCARD\\r\\nVERSION:4.0\\r\\n"
                    + "UID:uid-n1\\r\\nFN:New one\\r\\nEND:VCARD\\r\\n\"}",
            "{\"ref\":\"a\",\"op\":\"create\",\"vcard\":\"BEGIN:VCARD\\r\\nVERSION:4.0\\r\\n"
                    + "UID:uid-a\\r\\nFN:A\\r\\nEND:VCARD\\r\\n\"}",
            "{\"ref\":\"a\",\"op\":\"books\",\"id\":\"uid-a\",\"add\":[\"b1\"]}",
            "{\"ref\":\"b\",\"op\":\"update\",\"id\":\"uid-b\",\"vcard\":\"BEGIN:VCARD\\r\\n"
                    + "VERSION:4.0\\r\\nUID:uid-b\\r\\nFN:B edited\\r\\nEND:VCARD\\r\\n\","
                    + "\"baseVcard\":\"BEGIN:VCARD\\r\\nVERSION:4.0\\r\\nUID:uid-b\\r\\nFN:B\\r\\n"
                    + "END:VCARD\\r\\n\"}",
            "{\"ref\":\"c\",\"id\":\"uid-c\",\"op\":\"destroy\"}",
            "{\"ref\":\"d\",\"id\":\"uid-d\",\"op\":\"books\",\"remove\":[\"b1\"]}",
            "{\"ref\":\"n2\",\"op\":\"update\",\"id\":\"n2\",\"vcard\":\"BEGIN:VCARD\\r\\n"
                    + "VERSION:4.0\\r\\nUID:uid-n2\\r\\nFN:New two\\r\\nEND:VCARD\\r\\n\"}",
        };
        for (int index = 0; index < built.length; index++) {
            assertSameJson(built[index], changes.get(index).toJson().toString());
        }

        // NOTE: a book the store does not know went out as a null id, which
        // the bridge refuses to read; it still does.
        PushChange unknown =
                new PushChange(
                        "a", PushOp.BOOKS, "uid-a", null, null,
                        Collections.singletonList(null), List.of());
        assertEquals("[null]", new JSONArray().put((Object) null).toString());
        assertSameJson(
                "{\"ref\":\"a\",\"op\":\"books\",\"id\":\"uid-a\",\"add\":[null]}",
                unknown.toJson().toString());

        // NOTE: the bridge reads the round, then refuses it on a CardDAV
        // account before any request.
        try (Transport transport = new Transport()) {
            PimalayaException refused =
                    assertThrows(
                            PimalayaException.class,
                            () -> new ContactClient().pushCards(transport, DAV, "u", changes));
            assertEquals("Batch card push is JMAP and Graph only", refused.getMessage());

            PimalayaException unreadable =
                    assertThrows(
                            PimalayaException.class,
                            () -> new ContactClient().pushCards(
                                    transport, DAV, "u", List.of(unknown)));
            assertTrue(unreadable.getMessage().startsWith("Unreadable push changes"));
        }
    }

    @Test
    public void aMembershipUpdateReachesTheBridgeAsItsBookLists() throws Exception {
        try (Transport transport = new Transport()) {
            PimalayaException refused =
                    assertThrows(
                            PimalayaException.class,
                            () -> new ContactClient().updateCardBooks(
                                    transport,
                                    DAV,
                                    new BooksUpdate("uid-a", List.of("b1"), List.of("b2"))));
            assertEquals(
                    "Memberships of a carddav card are fixed to its collection",
                    refused.getMessage());
        }
    }
}
