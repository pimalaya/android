package org.pimalaya.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which domains share one browser grant: one per API for Microsoft, whose
 * tokens name one audience, and one for all of Google, whose tokens serve
 * every API they were granted.
 */
public class OauthTest {
    private static final String GMAIL = "https://mail.google.com/";
    private static final String CALENDAR = "https://www.googleapis.com/auth/calendar";
    private static final String CONTACTS = "https://www.googleapis.com/auth/contacts";

    private static String google(String scope) {
        return Oauth.audience(Oauth.GOOGLE_AUTH_ENDPOINT, null, scope);
    }

    private static String microsoft(String scope) {
        return Oauth.audience(Oauth.MICROSOFT_AUTH_ENDPOINT, null, scope);
    }

    @Test
    public void googleMailCalendarsAndContactsAreOneConsent() {
        assertEquals(google(GMAIL), google(CALENDAR));
        assertEquals(google(GMAIL), google(CONTACTS));
        assertEquals(google(GMAIL), google("https://www.googleapis.com/auth/carddav"));
    }

    @Test
    public void microsoftOutlookAndGraphAreTwoConsentsAndGraphIsOne() {
        String outlook =
                microsoft("https://outlook.office.com/IMAP.AccessAsUser.All offline_access");
        String graphMail =
                microsoft("https://graph.microsoft.com/Mail.ReadWrite offline_access");
        String graphCalendars = microsoft("Calendars.ReadWrite offline_access");

        assertNotEquals(outlook, graphMail);
        assertEquals(graphMail, graphCalendars);
    }

    @Test
    public void aResourceNamesTheAudienceOutsideGoogle() {
        assertEquals(
                "https://api.example.org/",
                Oauth.audience(
                        "https://auth.example.org/authorize", "https://api.example.org/", "mail"));
    }

    @Test
    public void scopesUniteInOrderEachOnce() {
        assertEquals(
                GMAIL + " " + CALENDAR + " " + CONTACTS,
                Oauth.union(GMAIL + " " + CALENDAR, null, " " + CALENDAR + "  " + CONTACTS));
        assertNull(Oauth.union(null, ""));
    }

    @Test
    public void aGrantCoversOnlyScopesItRecords() {
        assertTrue(Oauth.covers(GMAIL + " " + CONTACTS, GMAIL));
        assertFalse(Oauth.covers(CONTACTS, GMAIL));
        assertFalse(Oauth.covers(null, GMAIL));
        assertFalse(Oauth.covers(GMAIL, null));
    }

    /**
     * The app's own clients sign in only to their provider's hosts: a
     * configuration naming Google's sign-in beside another IMAP host gets a
     * generic grant, so the token the user consents to never reaches it.
     */
    @Test
    public void theShippedClientSignsInToItsProviderOnly() {
        assertEquals(
                Oauth.Shipped.GOOGLE,
                Oauth.shipped(
                        Oauth.GOOGLE_AUTH_ENDPOINT,
                        java.util.List.of("imaps://imap.gmail.com:993", "smtps://smtp.gmail.com:465")));
        assertNull(
                Oauth.shipped(
                        Oauth.GOOGLE_AUTH_ENDPOINT,
                        java.util.List.of("imaps://imap.evil.example:993")));
        assertEquals(
                Oauth.Shipped.MICROSOFT,
                Oauth.shipped(
                        Oauth.MICROSOFT_AUTH_ENDPOINT,
                        java.util.List.of(
                                "imaps://outlook.office365.com:993", "msgraph://jane@outlook.com")));
        assertNull(
                Oauth.shipped(
                        Oauth.MICROSOFT_AUTH_ENDPOINT,
                        java.util.List.of("smtps://smtp.evil.example:465")));
        assertNull(
                Oauth.shipped(
                        "https://auth.example.org/authorize",
                        java.util.List.of("imaps://imap.gmail.com:993")));
    }

    /** A shipped client asks for its provider's scopes, nothing a configuration adds. */
    @Test
    public void theShippedClientAsksForFixedScopes() {
        assertEquals(
                GMAIL + " " + CALENDAR,
                Oauth.fixedScope(
                        Oauth.Shipped.GOOGLE,
                        GMAIL + " https://www.googleapis.com/auth/drive " + CALENDAR));
        assertEquals(
                "https://outlook.office.com/IMAP.AccessAsUser.All offline_access",
                Oauth.fixedScope(
                        Oauth.Shipped.MICROSOFT,
                        "https://outlook.office.com/IMAP.AccessAsUser.All Files.ReadWrite.All offline_access"));
        assertNull(Oauth.fixedScope(Oauth.Shipped.GOOGLE, "https://www.googleapis.com/auth/drive"));
    }
}
