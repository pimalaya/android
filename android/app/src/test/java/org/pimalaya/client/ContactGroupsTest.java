package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.pimalaya.client.ReplyShape.assertMirrors;
import static org.pimalaya.client.ReplyShape.assertSameJson;
import static org.pimalaya.client.ReplyShape.elements;
import static org.pimalaya.client.ReplyShape.field;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Pins the duplicate review's and merged view's verbs on the real bridge.
 *
 * <p>Inputs written as the JSON the review and pool built (captured literals),
 * replies read to the values the hand parsers read.
 */
@RunWith(RobolectricTestRunner.class)
public class ContactGroupsTest {
    private static String card(String... lines) {
        StringBuilder vcard = new StringBuilder("BEGIN:VCARD\r\nVERSION:4.0\r\n");
        for (String line : lines) {
            vcard.append(line).append("\r\n");
        }
        return vcard.append("END:VCARD\r\n").toString();
    }

    /** The scanned cards, one per ref as the review offers them. */
    private static final List<RefCard> CARDS =
            List.of(
                    new RefCard("a@x\nbook1/1", card("UID:u1", "FN:Jane Doe", "EMAIL:jane@doe.example")),
                    new RefCard(
                            "a@x\nbook2/2",
                            card("UID:u2", "FN:Jane Doe", "EMAIL:JANE@doe.example ")),
                    new RefCard("b@x\nbook3/3", card("UID:u3", "FN:Bob", "TEL:+33 6 11 22 33 44")),
                    new RefCard("b@x\nbook3/4", card("UID:u4", "FN:Robert", "TEL:+33611223344")),
                    new RefCard("b@x\nbook3/5", card("UID:u1", "FN:Other Name")),
                    new RefCard("c@x\nbook4/6", card("UID:u6", "FN:Unique Person")),
                    new RefCard("c@x\nbook4/7", card("UID:u7", "FN:Jane Doe")));

    /** The scan input the review built for {@link #CARDS}. */
    private static final String CARDS_JSON =
            "[{\"ref\":\"a@x\\nbook1\\/1\",\"vcard\":\"BEGIN:VCARD\\r\\nVERSION:4.0\\r\\nUID:u1\\r\\nFN:Jane Doe\\r\\nEMAIL:jane@doe.example\\r\\nEND:VCARD\\r\\n\"},{\"ref\":\"a@x\\nbook2\\/2\",\"vcard\":\"BEGIN:VCARD\\r\\nVERSION:4.0\\r\\nUID:u2\\r\\nFN:Jane Doe\\r\\nEMAIL:JANE@doe.example \\r\\nEND:VCARD\\r\\n\"},{\"ref\":\"b@x\\nbook3\\/3\",\"vcard\":\"BEGIN:VCARD\\r\\nVERSION:4.0\\r\\nUID:u3\\r\\nFN:Bob\\r\\nTEL:+33 6 11 22 33 44\\r\\nEND:VCARD\\r\\n\"},{\"ref\":\"b@x\\nbook3\\/4\",\"vcard\":\"BEGIN:VCARD\\r\\nVERSION:4.0\\r\\nUID:u4\\r\\nFN:Robert\\r\\nTEL:+33611223344\\r\\nEND:VCARD\\r\\n\"},{\"ref\":\"b@x\\nbook3\\/5\",\"vcard\":\"BEGIN:VCARD\\r\\nVERSION:4.0\\r\\nUID:u1\\r\\nFN:Other Name\\r\\nEND:VCARD\\r\\n\"},{\"ref\":\"c@x\\nbook4\\/6\",\"vcard\":\"BEGIN:VCARD\\r\\nVERSION:4.0\\r\\nUID:u6\\r\\nFN:Unique Person\\r\\nEND:VCARD\\r\\n\"},{\"ref\":\"c@x\\nbook4\\/7\",\"vcard\":\"BEGIN:VCARD\\r\\nVERSION:4.0\\r\\nUID:u7\\r\\nFN:Jane Doe\\r\\nEND:VCARD\\r\\n\"}]";

    @Test
    public void theScanWritesTheReviewsInputAndReadsItsGroups() throws Exception {
        JSONArray input = new JSONArray();
        for (RefCard card : CARDS) {
            input.put(card.toJson());
        }
        assertSameJson(CARDS_JSON, input.toString());
        String raw = Native.findDuplicates(CARDS_JSON);
        assertEquals(raw, Native.findDuplicates(input.toString()));
        assertMirrors(DuplicateSet.class, elements(field(raw, "groups")).get(0));

        assertEquals(
                List.of(
                        new DuplicateSet(
                                List.of("email", "name"),
                                List.of("a@x\nbook1/1", "a@x\nbook2/2", "c@x\nbook4/7")),
                        new DuplicateSet(
                                List.of("phone"),
                                List.of("b@x\nbook3/3", "b@x\nbook3/4"))),
                ContactClient.findDuplicates(CARDS));
        assertEquals(List.of(), ContactClient.findDuplicates(List.of()));

        PimalayaException error =
                assertThrows(
                        PimalayaException.class,
                        () -> ContactClient.findDuplicates(List.of(new RefCard("r", "nope"))));
        assertEquals(
                "Invalid vCard: Content line is missing a value separator: nope",
                error.getMessage());
    }

    /** Each group: the members the review sent, its key, whether linkable. */
    private static final Object[][] GROUPS = {
            {"[{\"ref\":\"a@x\\nbook1\\/1\",\"book\":\"https:\\/\\/d\\/book1\\/\"},{\"ref\":\"a@x\\nbook2\\/2\",\"book\":\"https:\\/\\/d\\/book2\\/\"}]", "a@x\nbook1/1\u001fa@x\nbook2/2", true},
            {"[{\"ref\":\"b@x\\nbook3\\/3\",\"book\":\"https:\\/\\/d\\/book3\\/\"},{\"ref\":\"b@x\\nbook3\\/4\",\"book\":\"https:\\/\\/d\\/book3\\/\"}]", "b@x\nbook3/3\u001fb@x\nbook3/4", false},
            {"[{\"ref\":\"b@x\\nbook3\\/3\",\"book\":\"https:\\/\\/d\\/book3\\/\"},{\"ref\":\"b@x\\nbook3\\/3\",\"book\":\"https:\\/\\/d\\/book3\\/\"}]", "b@x\nbook3/3", false},
            {"[{\"ref\":\"z\\n2\",\"book\":\"B\"},{\"ref\":\"y\\n1\",\"book\":\"A\"},{\"ref\":\"z\\n2\",\"book\":\"B\"}]", "y\n1\u001fz\n2", true},
            {"[]", "", false}
    };

    @Test
    public void aGroupWritesTheReviewsMembersAndReadsItsFacts() throws Exception {
        String[][][] members = {
            {{"a@x\nbook1/1", "https://d/book1/"}, {"a@x\nbook2/2", "https://d/book2/"}},
            {{"b@x\nbook3/3", "https://d/book3/"}, {"b@x\nbook3/4", "https://d/book3/"}},
            {{"b@x\nbook3/3", "https://d/book3/"}, {"b@x\nbook3/3", "https://d/book3/"}},
            {{"z\n2", "B"}, {"y\n1", "A"}, {"z\n2", "B"}},
            {},
        };
        for (int at = 0; at < members.length; at++) {
            List<DuplicateMember> group = new ArrayList<>();
            JSONArray input = new JSONArray();
            for (String[] member : members[at]) {
                group.add(new DuplicateMember(member[0], member[1]));
                input.put(group.get(group.size() - 1).toJson());
            }
            String written = (String) GROUPS[at][0];
            assertSameJson(written, input.toString());
            String raw = Native.duplicateGroup(written);
            assertEquals(raw, Native.duplicateGroup(input.toString()));
            assertMirrors(DuplicateGroup.class, raw);

            assertEquals(
                    new DuplicateGroup((String) GROUPS[at][1], (boolean) GROUPS[at][2]),
                    ContactClient.duplicateGroup(group));
        }
    }

    /** The pool input the merged view built for {@link #pool}. */
    private static final String POOL_JSON =
            "{\"replicas\":[{\"ref\":\"a@x\\nk1\",\"uid\":\"u1\",\"name\":\"Zed\",\"id\":\"1\"},{\"ref\":\"a@x\\nk2\",\"uid\":\"\",\"name\":\"alpha\",\"id\":\"2\"},{\"ref\":\"b@x\\nk3\",\"uid\":\"u1\",\"name\":\"Zed too\",\"id\":\"3\"},{\"ref\":\"b@x\\nk4\",\"uid\":\"u4\",\"name\":\"\",\"id\":\"id-b\"},{\"ref\":\"c@x\\nk5\",\"uid\":\"u5\",\"name\":\"Mid\",\"id\":\"5\"},{\"ref\":\"c@x\\nk6\",\"uid\":\"u1\",\"name\":\"Detached\",\"id\":\"6\"}],\"links\":{\"uid\\u0000u4\":\"uid\\u0000u4\",\"ref\\u0000nobody\":null,\"uid\\u0000u5\":\"uid\\u0000u4\"},\"detached\":[\"c@x\\nk6\",\"nobody\\nk9\"]}";

    private static ReplicaPool pool() {
        Map<String, String> links = new HashMap<>();
        links.put("uid\u0000u5", "uid\u0000u4");
        links.put("uid\u0000u4", "uid\u0000u4");
        links.put("ref\u0000nobody", null);
        return new ReplicaPool(
                List.of(
                        new PoolReplica("a@x\nk1", "u1", "Zed", "1"),
                        new PoolReplica("a@x\nk2", "", "alpha", "2"),
                        new PoolReplica("b@x\nk3", "u1", "Zed too", "3"),
                        new PoolReplica("b@x\nk4", "u4", "", "id-b"),
                        new PoolReplica("c@x\nk5", "u5", "Mid", "5"),
                        new PoolReplica("c@x\nk6", "u1", "Detached", "6")),
                links,
                List.of("c@x\nk6", "nobody\nk9"));
    }

    @Test
    public void thePoolWritesTheMergedViewsInputAndReadsItsGroups() throws Exception {
        assertSameJson(POOL_JSON, pool().toJson().toString());
        String raw = Native.groupContacts(POOL_JSON);
        assertEquals(raw, Native.groupContacts(pool().toJson().toString()));
        assertMirrors(ContactGroup.class, elements(field(raw, "groups")).get(0));

        assertEquals(
                List.of(
                        new ContactGroup("ref\u0000a@x\nk2", List.of(1)),
                        new ContactGroup("ref\u0000c@x\nk6", List.of(5)),
                        new ContactGroup("uid\u0000u4", List.of(3, 4)),
                        new ContactGroup("uid\u0000u1", List.of(0, 2))),
                ContactClient.groupContacts(pool()));
        assertEquals(
                List.of(),
                ContactClient.groupContacts(new ReplicaPool(List.of(), Map.of(), List.of())));
    }
}
