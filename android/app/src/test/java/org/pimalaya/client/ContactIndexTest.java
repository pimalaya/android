package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.pimalaya.client.ReplyShape.assertMirrors;
import static org.pimalaya.client.ReplyShape.assertSameJson;

import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Pins the card index and the type-set vocabulary on the real bridge.
 *
 * <p>Each corpus card indexed to captured literals, the record mirroring its
 * reply.
 */
@RunWith(RobolectricTestRunner.class)
public class ContactIndexTest {
    /** Each corpus card's index, its summary as JSON text (null: none). */
    private static final Object[] INDEXES = {
            new CardIndex("", List.of(), "9d0945d61308edfc", "", "min", "Only Name", "", "only name", null, "min"),
            "{\"component\":\"contact\",\"title\":\"Only Name\",\"uid\":\"min\",\"values\":[]}",
            new CardIndex("jane@home.example", List.of("jane@home.example", "jane@work.example"), "1a73b29ba1a40a4f", "ACME", "rich", "Dr. Jane Q. Doe Jr.", "+33 6 11 11 11 11", "dr. jane q. doe jr.", null, "rich"),
            "{\"component\":\"contact\",\"org\":\"ACME\",\"title\":\"Dr. Jane Q. Doe Jr.\",\"uid\":\"rich\",\"values\":[{\"position\":0,\"role\":\"email\",\"value\":\"jane@home.example\"},{\"position\":1,\"role\":\"email\",\"value\":\"jane@work.example\"}]}",
            new CardIndex("old@style.example", List.of("old@style.example"), "9adec7b311357ba3", "Single", "v3", "Old Style", "0611", "old style", null, "v3"),
            "{\"component\":\"contact\",\"org\":\"Single\",\"title\":\"Old Style\",\"uid\":\"v3\",\"values\":[{\"position\":0,\"role\":\"email\",\"value\":\"old@style.example\"}]}",
            new CardIndex("", List.of(), "5d80c439ca7df8c6", "", "lossy", "Jane Doe Yo", "+331111", "jane doe yo", null, "lossy"),
            "{\"component\":\"contact\",\"title\":\"Jane Doe Yo\",\"uid\":\"lossy\",\"values\":[]}",
            new CardIndex("", List.of(), "8b8d05814919b11d", "", "nfn", "Mr. John Smith", "", "", null, "nfn"),
            "{\"component\":\"contact\",\"title\":\"\",\"uid\":\"nfn\",\"values\":[]}",
            new CardIndex("", List.of(), "86e3e1bcd24ef835", "", "empties", "", "", "", null, "empties"),
            "{\"component\":\"contact\",\"org\":\"\",\"title\":\"\",\"uid\":\"empties\",\"values\":[]}",
            new CardIndex("zoe@exämple.org", List.of("zoe@exämple.org"), "3d4ef22a5c355d6d", "Café, Inc.", "escapes", "Zoë ; Ünïcødé 😀", "", "zoë ; ünïcødé 😀", null, "escapes"),
            "{\"component\":\"contact\",\"org\":\"Café, Inc.\",\"title\":\"Zoë ; Ünïcødé 😀\",\"uid\":\"escapes\",\"values\":[{\"position\":0,\"role\":\"email\",\"value\":\"zoe@exämple.org\"}]}",
            new CardIndex("a@b.c", List.of("a@b.c"), "f5d8e5d413fdd20d", "", "types", "Types", "1", "types", null, "types"),
            "{\"component\":\"contact\",\"title\":\"Types\",\"uid\":\"types\",\"values\":[{\"position\":0,\"role\":\"email\",\"value\":\"a@b.c\"}]}",
            new CardIndex("", List.of(), "a233d1a1a1073199", "", "dates", "Dates", "", "dates", null, "dates"),
            "{\"component\":\"contact\",\"title\":\"Dates\",\"uid\":\"dates\",\"values\":[]}",
            new CardIndex("", List.of(), "816a3e962f77622a", "First Org", "dups", "First", "", "first", null, "dups"),
            "{\"component\":\"contact\",\"org\":\"First Org\",\"title\":\"First\",\"uid\":\"dups\",\"values\":[]}",
            new CardIndex("", List.of(), "eefa3466f02a84a9", "", "groups", "Grouped", "", "grouped", null, "groups"),
            "{\"component\":\"contact\",\"title\":\"Grouped\",\"uid\":\"groups\",\"values\":[{\"position\":0,\"role\":\"email\",\"value\":\"g@x.example\"}]}",
            new CardIndex("", List.of(), "c860632b1fe3b37e", "", "onlyuid", "", "", "", null, "onlyuid"),
            "{\"component\":\"contact\",\"title\":\"\",\"uid\":\"onlyuid\",\"values\":[]}",
            new CardIndex("", List.of(), "95f0b089a67e26de", "A very long note that is folded across several lines because it exceeds seventy-five octets per line as the RFC asks", "folded", "Folded Name That Is Rather Long And Goes On And On Beyond The Line Limit", "", "folded name that is rather long and goes on and on beyond the line limit", null, "folded"),
            "{\"component\":\"contact\",\"title\":\"Folded Name That Is Rather Long And Goes On And On Beyond The Line Limit\",\"uid\":\"folded\",\"values\":[]}",
            new CardIndex("", List.of(), "b7d932a63ac9ab96", "", "sparse", "Sparse", "", "sparse", null, "sparse"),
            "{\"component\":\"contact\",\"title\":\"Sparse\",\"uid\":\"sparse\",\"values\":[{\"position\":0,\"role\":\"email\",\"value\":\"\"}]}"
    };

    @Test
    public void everyCorpusCardIndexesAsBefore() throws Exception {
        for (int at = 0; at < INDEXES.length / 2; at++) {
            CardIndex expected = (CardIndex) INDEXES[2 * at];
            String summary = (String) INDEXES[2 * at + 1];
            CardIndex index = ContactClient.indexCard(CardCorpus.VCARDS.get(at));

            assertEquals(
                    "card " + at,
                    expected,
            new CardIndex(
                            index.email(),
                            index.emails(),
                            index.hash(),
                            index.info(),
                            index.linkId(),
                            index.name(),
                            index.phone(),
                            index.sortKey(),
                            null,
                            index.uid()));
            if (summary == null) {
                assertNull("card " + at, index.summary());
            } else {
                assertSameJson(summary, index.summary().toString());
            }
        }
    }

    @Test
    public void theIndexMirrorsItsReply() {
        assertMirrors(CardIndex.class, Native.indexCard(CardCorpus.VCARDS.get(1)));
    }

    @Test
    public void anUnreadableCardFailsTheIndex() {
        PimalayaException error =
                assertThrows(
                        PimalayaException.class,
                        () -> ContactClient.indexCard(CardCorpus.VCARDS.get(14)));
        assertEquals(
                "Invalid vCard: Content line is missing a value separator: not a vcard",
                error.getMessage());
    }

    @Test
    public void everyKindReadsItsTypeSets() {
        assertEquals(List.of(List.of("cell"), List.of("home"), List.of("work"), List.of("cell", "work"), List.of("fax", "home"), List.of("fax", "work"), List.of("pager"), List.of()), ContactClient.cardTypeOrder(TypeKind.PHONE));
        assertEquals(List.of(List.of("home"), List.of("work"), List.of()), ContactClient.cardTypeOrder(TypeKind.EMAIL));
        assertEquals(List.of(List.of("home"), List.of("work"), List.of()), ContactClient.cardTypeOrder(TypeKind.ADDRESS));
        assertEquals(List.of(List.of("spouse"), List.of("child"), List.of("parent"), List.of("sibling"), List.of("friend"), List.of("colleague"), List.of("emergency"), List.of()), ContactClient.cardTypeOrder(TypeKind.RELATION));
        assertEquals(List.of(List.of(""), List.of("M"), List.of("F"), List.of("O"), List.of("N"), List.of("U")), ContactClient.cardTypeOrder(TypeKind.GENDER));
    }
}
