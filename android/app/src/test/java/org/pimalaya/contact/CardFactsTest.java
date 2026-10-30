package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** What the contacts filter reads off a card (cairn/changes/one-filter-panel). */
public class CardFactsTest {
    private static final String CARD =
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Sam\r\nitem1.EMAIL;TYPE=work:sam@exam\r\n"
                    + " ple.org\r\nCATEGORIES:Work,Garden\\, plants\r\nCATEGORIES;PID=1:Friends\r\n"
                    + "BDAY:19800101\r\nEND:VCARD\r\n";

    @Test
    public void aPropertyCountsWithAGroupPrefixAndParameters() {
        assertTrue(CardFacts.has(CARD, "EMAIL"));
        assertTrue(CardFacts.has(CARD, "BDAY"));
        assertFalse(CardFacts.has(CARD, "TEL"));
        assertFalse(CardFacts.has(CARD, "ADR"));
    }

    @Test
    public void categoriesAreEveryLinesUnescaped() {
        assertEquals(List.of("Work", "Garden, plants", "Friends"), CardFacts.categories(CARD));
    }

    @Test
    public void aCardStatingNoKindIsAnIndividual() {
        assertEquals("individual", CardFacts.kind(CARD));
        assertEquals("group", CardFacts.kind("BEGIN:VCARD\r\nKIND:Group\r\nEND:VCARD\r\n"));
    }
}
