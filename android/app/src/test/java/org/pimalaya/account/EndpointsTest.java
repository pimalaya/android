package org.pimalaya.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.AuthMethod;
import org.pimalaya.client.ServiceConfig;
import org.robolectric.RobolectricTestRunner;

/**
 * Where a credential may go: encrypted endpoints only, and shown to the user.
 *
 * <p>Robolectric because a discovered provider API names its base through the
 * bridge, whose library loads into the sandbox's class loader.
 */
@RunWith(RobolectricTestRunner.class)
public class EndpointsTest {
    @Test
    public void aTypedMailServerIsImplicitTlsOrNothing() {
        assertEquals("imaps://mail.example.org:993", Endpoints.manualUrl(PimDomain.MAIL, "mail.example.org"));
        assertEquals("imaps://mail.example.org:1993", Endpoints.manualUrl(PimDomain.MAIL, "mail.example.org:1993"));
        assertEquals(
                "imaps://mail.example.org:993",
                Endpoints.manualUrl(PimDomain.MAIL, "imaps://mail.example.org:993"));
        assertNull(Endpoints.manualUrl(PimDomain.MAIL, "imap://mail.example.org:143"));
        assertNull(Endpoints.manualUrl(PimDomain.MAIL, "http://mail.example.org"));
    }

    @Test
    public void aTypedDavUrlIsHttpsOrNothing() {
        assertEquals("https://dav.example.org/", Endpoints.manualUrl(PimDomain.CONTACTS, "https://dav.example.org/"));
        assertEquals("https://dav.example.org", Endpoints.manualUrl(PimDomain.CALENDAR, "dav.example.org"));
        assertNull(Endpoints.manualUrl(PimDomain.CONTACTS, "http://dav.example.org/"));
        assertNull(Endpoints.manualUrl(PimDomain.CALENDAR, "ftp://dav.example.org/"));
    }

    @Test
    public void aTypedSubmissionServerIsSmtpOrNothing() {
        assertEquals("smtps://smtp.example.org:465", Endpoints.submitUrl("smtp.example.org"));
        assertEquals("smtp://smtp.example.org:587", Endpoints.submitUrl("smtp.example.org:587"));
        assertEquals("smtp://smtp.example.org:587", Endpoints.submitUrl("smtp://smtp.example.org:587"));
        assertNull(Endpoints.submitUrl("http://smtp.example.org"));
        assertNull(Endpoints.submitUrl("imap://smtp.example.org"));
    }

    @Test
    public void onlyEncryptedEndpointsAreSecure() {
        assertTrue(Endpoints.secure("https://dav.example.org/"));
        assertTrue(Endpoints.secure("imaps://mail.example.org:993"));
        assertTrue(Endpoints.secure("smtp://smtp.example.org:587"));
        assertTrue(Endpoints.secure("jmap://api.fastmail.com/jmap/session"));
        assertFalse(Endpoints.secure("http://dav.example.org/"));
        assertFalse(Endpoints.secure("imap://mail.example.org:143"));
        assertFalse(Endpoints.secure("mail.example.org"));
    }

    @Test
    public void theUserIsShownSchemeHostAndPortOnly() {
        assertEquals(
                "imaps://imap.example.org:993",
                Endpoints.destination("imaps://jane%40example.org@imap.example.org:993"));
        assertEquals("https://dav.example.org", Endpoints.destination("https://dav.example.org/dav/jane/"));
        assertEquals("https://api.fastmail.com", Endpoints.destination("jmap://api.fastmail.com/jmap/session"));
        assertNull(Endpoints.destination("google://jane@gmail.com"));
    }

    @Test
    public void aHostOutsideTheAddressDomainIsFlagged() {
        assertFalse(Endpoints.otherDomain("imaps://imap.example.org:993", "jane@example.org"));
        assertFalse(Endpoints.otherDomain("https://example.org/dav/", "jane@Example.org"));
        assertTrue(Endpoints.otherDomain("imaps://imap.fastmail.com:993", "jane@example.org"));
        assertTrue(Endpoints.otherDomain("imaps://example.org.evil.net:993", "jane@example.org"));
        assertFalse(Endpoints.otherDomain("imaps://imap.fastmail.com:993", "example.org"));
    }

    @Test
    public void everyEndpointMustBeTheProvidersOwn() {
        List<String> gmail =
                List.of(
                        "imaps://imap.gmail.com:993",
                        "smtps://smtp.gmail.com:465",
                        "google://jane@gmail.com",
                        "https://apidata.googleusercontent.com/caldav/v2/jane%40gmail.com/user");
        assertTrue(Endpoints.allOn(gmail, "google", Oauth.GOOGLE_HOSTS));
        assertFalse(
                Endpoints.allOn(
                        List.of("imaps://imap.gmail.com:993", "imaps://imap.evil.example:993"),
                        "google",
                        Oauth.GOOGLE_HOSTS));
        assertFalse(Endpoints.allOn(List.of("imap://imap.gmail.com:143"), "google", Oauth.GOOGLE_HOSTS));
        assertFalse(Endpoints.allOn(List.of("msgraph://jane@outlook.com"), "google", Oauth.GOOGLE_HOSTS));
    }

    @Test
    public void onlyMailEndpointsNameTheirUser() {
        assertEquals(
                "imaps://jane%40example.org@imap.example.org:993",
                Endpoints.asUser("imaps://imap.example.org:993", "jane@example.org"));
        assertEquals(
                "smtp://jane%40example.org@smtp.example.org:587",
                Endpoints.asUser("smtp://smtp.example.org:587", "jane@example.org"));
        assertEquals(
                "https://dav.example.org/dav/", Endpoints.asUser("https://dav.example.org/dav/", "jane@example.org"));
        assertEquals(
                "jmap://api.example.org/jmap/session",
                Endpoints.asUser("jmap://api.example.org/jmap/session", "jane@example.org"));
    }

    @Test
    public void aDiscoveredSubmissionServerIsSmtpOverTlsOrStarttls() {
        assertEquals("smtps://smtp.example.org:465", Endpoints.submissionUrl(tcp("smtp", 465, "tls")));
        assertEquals("smtp://smtp.example.org:587", Endpoints.submissionUrl(tcp("smtp", 587, "STARTTLS")));
        assertNull(Endpoints.submissionUrl(tcp("smtp", 25, "plain")));
        assertNull(Endpoints.submissionUrl(tcp("imap", 993, "tls")));
        assertNull(Endpoints.submissionUrl(http("smtp", "https://smtp.example.org/")));
    }

    @Test
    public void aDiscoveredEndpointIsHttpsOrImplicitTlsImap() {
        String email = "jane@example.org";
        assertEquals("https://dav.example.org/", Endpoints.endpointUrl(http("carddav", "https://dav.example.org/"), email));
        assertNull(Endpoints.endpointUrl(http("carddav", "http://dav.example.org/"), email));
        assertEquals(
                AccountClient.jmapBase("https://api.example.org/jmap/session"),
                Endpoints.endpointUrl(http("jmap", "https://api.example.org/jmap/session"), email));
        assertEquals(AccountClient.msgraphBase(email), Endpoints.endpointUrl(http("msgraph", "https://graph.microsoft.com/"), email));
        assertEquals(AccountClient.msgraphBase(email), Endpoints.endpointUrl(http("msgraphCalendar", "https://graph.microsoft.com/"), email));
        assertEquals(AccountClient.googleBase(email), Endpoints.endpointUrl(http("gmail", "https://gmail.googleapis.com/"), email));
        assertEquals(AccountClient.googleBase(email), Endpoints.endpointUrl(http("gcal", "https://www.googleapis.com/"), email));
        assertEquals("imaps://imap.example.org:993", Endpoints.endpointUrl(tcp("imap", 993, "TLS"), email));
        assertNull(Endpoints.endpointUrl(tcp("imap", 143, "starttls"), email));
        assertNull(Endpoints.endpointUrl(tcp("smtp", 465, "tls"), email));
    }

    private static ServiceConfig tcp(String service, int port, String security) {
        return new ServiceConfig(
                service, null, service + ".example.org", port, security, null, List.of(AuthMethod.password()), "pacc");
    }

    private static ServiceConfig http(String service, String url) {
        return new ServiceConfig(service, url, null, 0, null, null, List.of(AuthMethod.password()), "pacc");
    }
}
