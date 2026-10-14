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
 * Pins the card index and the type-set vocabulary on the real bridge: each
 * corpus card indexed to the values the hand parser the record replaced
 * read (literals captured on that code), the record mirroring its reply.
 */
@RunWith(RobolectricTestRunner.class)
public class ContactIndexTest {
    /** Each corpus card's index, its summary apart as JSON text (null: none). */
    private static final Object[] INDEXES = {
            new CardIndex("", List.of(), "9d0945d61308edfc", "", "Only Name", "", "only name", null, "min"),
            "{\"contact\":{\"addresses\":[],\"fn\":\"Only Name\",\"uid\":\"min\"}}",
            new CardIndex("jane@home.example", List.of("jane@home.example", "jane@work.example"), "1a73b29ba1a40a4f", "ACME", "Dr. Jane Q. Doe Jr.", "+33 6 11 11 11 11", "dr. jane q. doe jr.", null, "rich"),
            "{\"contact\":{\"addresses\":[{\"address\":\"jane@home.example\",\"position\":0,\"role\":\"email\"},{\"address\":\"jane@work.example\",\"position\":1,\"role\":\"email\"}],\"fn\":\"Dr. Jane Q. Doe Jr.\",\"org\":\"ACME\",\"uid\":\"rich\"}}",
            new CardIndex("old@style.example", List.of("old@style.example"), "9adec7b311357ba3", "Single", "Old Style", "0611", "old style", null, "v3"),
            "{\"contact\":{\"addresses\":[{\"address\":\"old@style.example\",\"position\":0,\"role\":\"email\"}],\"fn\":\"Old Style\",\"org\":\"Single\",\"uid\":\"v3\"}}",
            new CardIndex("", List.of(), "5d80c439ca7df8c6", "", "Jane Doe Yo", "+331111", "jane doe yo", null, "lossy"),
            "{\"contact\":{\"addresses\":[],\"fn\":\"Jane Doe Yo\",\"uid\":\"lossy\"}}",
            new CardIndex("", List.of(), "8b8d05814919b11d", "", "Mr. John Smith", "", "", null, "nfn"),
            "{\"contact\":{\"addresses\":[],\"fn\":\"\",\"uid\":\"nfn\"}}",
            new CardIndex("", List.of(), "86e3e1bcd24ef835", "", "", "", "", null, "empties"),
            "{\"contact\":{\"addresses\":[],\"fn\":\"\",\"org\":\"\",\"uid\":\"empties\"}}",
            new CardIndex("zoe@exämple.org", List.of("zoe@exämple.org"), "3d4ef22a5c355d6d", "Café, Inc.", "Zoë ; Ünïcødé 😀", "", "zoë ; ünïcødé 😀", null, "escapes"),
            "{\"contact\":{\"addresses\":[{\"address\":\"zoe@exämple.org\",\"position\":0,\"role\":\"email\"}],\"fn\":\"Zoë ; Ünïcødé 😀\",\"org\":\"Café, Inc.\",\"uid\":\"escapes\"}}",
            new CardIndex("a@b.c", List.of("a@b.c"), "f5d8e5d413fdd20d", "", "Types", "1", "types", null, "types"),
            "{\"contact\":{\"addresses\":[{\"address\":\"a@b.c\",\"position\":0,\"role\":\"email\"}],\"fn\":\"Types\",\"uid\":\"types\"}}",
            new CardIndex("", List.of(), "a233d1a1a1073199", "", "Dates", "", "dates", null, "dates"),
            "{\"contact\":{\"addresses\":[],\"fn\":\"Dates\",\"uid\":\"dates\"}}",
            new CardIndex("", List.of(), "816a3e962f77622a", "First Org", "First", "", "first", null, "dups"),
            "{\"contact\":{\"addresses\":[],\"fn\":\"First\",\"org\":\"First Org\",\"uid\":\"dups\"}}",
            new CardIndex("", List.of(), "eefa3466f02a84a9", "", "Grouped", "", "grouped", null, "groups"),
            "{\"contact\":{\"addresses\":[{\"address\":\"g@x.example\",\"position\":0,\"role\":\"email\"}],\"fn\":\"Grouped\",\"uid\":\"groups\"}}",
            new CardIndex("", List.of(), "c860632b1fe3b37e", "", "", "", "", null, "onlyuid"),
            "{\"contact\":{\"addresses\":[],\"fn\":\"\",\"uid\":\"onlyuid\"}}",
            new CardIndex("", List.of(), "95f0b089a67e26de", "A very long note that is folded across several lines because it exceeds seventy-five octets per line as the RFC asks", "Folded Name That Is Rather Long And Goes On And On Beyond The Line Limit", "", "folded name that is rather long and goes on and on beyond the line limit", null, "folded"),
            "{\"contact\":{\"addresses\":[],\"fn\":\"Folded Name That Is Rather Long And Goes On And On Beyond The Line Limit\",\"uid\":\"folded\"}}",
            new CardIndex("", List.of(), "b7d932a63ac9ab96", "", "Sparse", "", "sparse", null, "sparse"),
            "{\"contact\":{\"addresses\":[{\"address\":\"\",\"position\":0,\"role\":\"email\"}],\"fn\":\"Sparse\",\"uid\":\"sparse\"}}"
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
