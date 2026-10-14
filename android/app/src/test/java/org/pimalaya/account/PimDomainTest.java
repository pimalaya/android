package org.pimalaya.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Set;
import org.pimalaya.client.AuthMethod;
import org.pimalaya.client.ServiceConfig;

/**
 * Which discovered services serve which domain, as the connection flow offers.
 *
 * <p>A wrong mapping is no rendering bug: a missing domain is a provider the
 * app silently cannot connect, a spurious one leads to an empty screen.
 */
public class PimDomainTest {
    private static final String MAIL_URN = "urn:ietf:params:jmap:mail";
    private static final String SUBMISSION_URN = "urn:ietf:params:jmap:submission";
    private static final String CONTACTS_URN = "urn:ietf:params:jmap:contacts";
    private static final String CALENDARS_URN = "urn:ietf:params:jmap:calendars";

    @Test
    public void eachProtocolServesTheDomainItIsFor() {
        assertEquals(Set.of(PimDomain.MAIL), PimDomain.servedBy("imap"));
        assertEquals(Set.of(PimDomain.CONTACTS), PimDomain.servedBy("carddav"));
        assertEquals(Set.of(PimDomain.CALENDAR), PimDomain.servedBy("caldav"));
    }

    @Test
    public void jmapIsOfferedOnlyWhereThereIsSomethingToReadItWith() {
        // NOTE: offered before sign-in since only the session says which it
        // serves; kept exhaustive so no fourth domain is mapped without stating
        // that its reader exists.
        assertEquals(
                Set.of(PimDomain.MAIL, PimDomain.CONTACTS, PimDomain.CALENDAR),
                PimDomain.servedBy("jmap"));
    }

    @Test
    public void aJmapSessionServesTheDomainsItAdvertises() {
        // NOTE: a mail-scoped token's session leaves contacts and calendars out
        // rather than failing the sign-in.
        assertEquals(
                Set.of(PimDomain.MAIL),
                PimDomain.servedByJmap(Set.of(MAIL_URN, SUBMISSION_URN)));
        assertEquals(
                Set.of(PimDomain.MAIL, PimDomain.CONTACTS, PimDomain.CALENDAR),
                PimDomain.servedByJmap(
                        Set.of(MAIL_URN, SUBMISSION_URN, CONTACTS_URN, CALENDARS_URN)));
        assertTrue(PimDomain.servedByJmap(Set.of()).isEmpty());
    }

    @Test
    public void jmapMailIsServedOnlyWithSendingBesideIt() {
        // NOTE: an account that cannot answer would refuse every message from
        // compose.
        assertEquals(
                Set.of(PimDomain.CONTACTS),
                PimDomain.servedByJmap(Set.of(MAIL_URN, CONTACTS_URN)));
    }

    @Test
    public void eachDomainRanksWhatTheAppCanDoOverIt() {
        assertTrue(PimDomain.MAIL.rank("jmap") < PimDomain.MAIL.rank("imap"));
        assertTrue(PimDomain.CONTACTS.rank("jmap") < PimDomain.CONTACTS.rank("carddav"));
        // NOTE: JMAP calendars are known only once signed in, and the probe
        // drops what is not served, so CalDAV leads.
        assertTrue(PimDomain.CALENDAR.rank("caldav") < PimDomain.CALENDAR.rank("jmap"));
        assertTrue(PimDomain.CALENDAR.rank("jmap") < PimDomain.CALENDAR.rank(null));
    }

    @Test
    public void aServiceWithNoReaderIsNeverOffered() {
        // NOTE: offering SMTP would connect to nothing, and generic WebDAV is
        // not a domain.
        assertTrue(PimDomain.servedBy("smtp").isEmpty());
        assertTrue(PimDomain.servedBy("webdav").isEmpty());
        assertTrue(PimDomain.servedBy("pop3").isEmpty());
        assertTrue(PimDomain.servedBy("managesieve").isEmpty());
    }

    @Test
    public void anAccountStoredBeforeDomainsExistedIsAContactsAccount() {
        // NOTE: contacts was all the app could connect then; defaulting keeps
        // those accounts.
        assertEquals(PimDomain.CONTACTS, PimDomain.byId(""));
        assertEquals(PimDomain.CONTACTS, PimDomain.byId("nonsense"));

        for (PimDomain domain : PimDomain.values()) {
            assertEquals(domain, PimDomain.byId(domain.id));
        }
    }

    @Test
    public void theIdsAreStableAndDistinct() {
        // NOTE: persisted in the encrypted account blob: renaming one
        // re-domains every account stored under it.
        assertEquals(List.of("mail", "contacts", "calendar"),
                List.of(PimDomain.MAIL.id, PimDomain.CONTACTS.id, PimDomain.CALENDAR.id));
    }

    @Test
    public void eachDomainKeepsTheConfigsServingItInDiscoveryOrder() {
        ServiceConfig imap = config("imap");
        ServiceConfig smtp = config("smtp");
        ServiceConfig carddav = config("carddav");
        ServiceConfig jmap = config("jmap");
        ServiceConfig gcal = config("gcal");
        List<ServiceConfig> configs = List.of(imap, smtp, carddav, jmap, gcal);

        assertEquals(List.of(imap, jmap), PimDomain.configsFor(PimDomain.MAIL, configs));
        assertEquals(List.of(carddav, jmap), PimDomain.configsFor(PimDomain.CONTACTS, configs));
        assertEquals(List.of(jmap, gcal), PimDomain.configsFor(PimDomain.CALENDAR, configs));
        assertTrue(PimDomain.configsFor(PimDomain.MAIL, List.of(smtp)).isEmpty());
    }

    private static ServiceConfig config(String service) {
        return new ServiceConfig(
                service, "https://" + service + ".example.org/", null, 0, null, null, List.of(AuthMethod.password()), "pacc");
    }
}
