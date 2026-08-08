package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Set;

/**
 * Which discovered services serve which domain, which is what the connection
 * flow's second step is built from.
 *
 * <p>The mapping decides what a user is offered, so getting it wrong is not a
 * rendering bug: a missing domain is a provider the app silently cannot
 * connect, and a spurious one is an option that leads to an empty screen.
 */
public class PimDomainTest {
    @Test
    public void eachProtocolServesTheDomainItIsFor() {
        assertEquals(Set.of(PimDomain.MAIL), PimDomain.servedBy("imap"));
        assertEquals(Set.of(PimDomain.CONTACTS), PimDomain.servedBy("carddav"));
        assertEquals(Set.of(PimDomain.CALENDAR), PimDomain.servedBy("caldav"));
    }

    @Test
    public void jmapIsOfferedOnlyWhereThereIsSomethingToReadItWith() {
        // One JMAP session serves all three and the app now reads all three,
        // so the mapping is complete. It was not always: offering a domain
        // with no reader made an account that connected and was then never
        // read. The assertion stays exhaustive rather than becoming a
        // contains-check, so a fourth domain cannot be mapped here without
        // someone stating that its reader exists.
        assertEquals(
                Set.of(PimDomain.MAIL, PimDomain.CONTACTS, PimDomain.CALENDAR),
                PimDomain.servedBy("jmap"));
    }

    @Test
    public void aServiceWithNoReaderIsNeverOffered() {
        // Discovery reports more than this app can drive. Offering SMTP would
        // put an option on the screen that connects to nothing, and generic
        // WebDAV is not a domain at all.
        assertTrue(PimDomain.servedBy("smtp").isEmpty());
        assertTrue(PimDomain.servedBy("webdav").isEmpty());
        assertTrue(PimDomain.servedBy("pop3").isEmpty());
        assertTrue(PimDomain.servedBy("managesieve").isEmpty());
    }

    @Test
    public void anAccountStoredBeforeDomainsExistedIsAContactsAccount() {
        // Contacts is all the app could connect then, so defaulting keeps those
        // accounts working instead of dropping them off the roster.
        assertEquals(PimDomain.CONTACTS, PimDomain.byId(""));
        assertEquals(PimDomain.CONTACTS, PimDomain.byId("nonsense"));

        // And a stored domain round-trips through the id it was written under.
        for (PimDomain domain : PimDomain.values()) {
            assertEquals(domain, PimDomain.byId(domain.id));
        }
    }

    @Test
    public void theIdsAreStableAndDistinct() {
        // They are persisted in the encrypted account blob, so renaming one
        // silently re-domains every account stored under it.
        assertEquals(List.of("mail", "contacts", "calendar"),
                List.of(PimDomain.MAIL.id, PimDomain.CONTACTS.id, PimDomain.CALENDAR.id));
    }
}
