package org.pimalaya.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import org.pimalaya.client.OauthGrant;
import org.pimalaya.client.OauthTokens;

/**
 * One account is one identity covering several domains.
 *
 * <p>Connecting a second domain keeps the first; renewing one consent renews
 * every domain that signed in with it and leaves a second consent alone; the
 * covered domains stay answerable.
 */
public class AccountEntryTest {
    private static AccountCredential password(String secret) {
        return AccountCredential.password("jane", secret);
    }

    private static AccountCredential oauth(String refreshToken) {
        return AccountCredential.oauth(
                new OauthTokens("access", "Bearer", 0, refreshToken, null, 0),
                new OauthGrant("https://accounts.example.org/token", "client-id", null, null));
    }

    private static AccountCredential google(String refreshToken, String scope) {
        return AccountCredential.oauth(
                new OauthTokens("access", "Bearer", 0, refreshToken, null, 0),
                new OauthGrant(Oauth.GOOGLE_TOKEN_ENDPOINT, Oauth.GOOGLE_CLIENT_ID, null, scope));
    }

    private static final String GMAIL = "https://mail.google.com/";
    private static final String CALENDAR = "https://www.googleapis.com/auth/calendar";
    private static final String CONTACTS = "https://www.googleapis.com/auth/contacts";

    @Test
    public void connectingASecondDomainKeepsTheFirst() {
        AccountEntry account =
                AccountEntry.of(
                        "jane@example.org",
                        PimDomain.CONTACTS,
                        "https://dav.example.org/",
                        password("secret"));

        AccountEntry both =
                account.with(
                        PimDomain.MAIL, "imaps://mail.example.org:993", password("secret"));

        assertEquals(List.of(PimDomain.MAIL, PimDomain.CONTACTS), List.copyOf(both.domains()));
        assertTrue(both.covers(PimDomain.CONTACTS));
        assertTrue(both.covers(PimDomain.MAIL));
        assertFalse(both.covers(PimDomain.CALENDAR));
        assertEquals("https://dav.example.org/", both.server(PimDomain.CONTACTS).baseUrl);
        assertEquals("imaps://mail.example.org:993", both.server(PimDomain.MAIL).baseUrl);
    }

    @Test
    public void oneConsentRenewedRenewsEveryDomainSigningInWithIt() {
        // NOTE: a credential is stored once because a provider rotating refresh
        // tokens retires the old one, so a domain holding a copy could never
        // sign in again.
        AccountCredential granted = oauth("one-consent");
        AccountEntry account =
                AccountEntry.of(
                                "jane@example.org",
                                PimDomain.MAIL,
                                "jmap://api.example.org/",
                                granted)
                        .with(PimDomain.CONTACTS, "jmap://api.example.org/", granted)
                        .with(PimDomain.CALENDAR, "jmap://api.example.org/", granted);

        AccountEntry refreshed = account.refreshed(PimDomain.CONTACTS, "fresh", "rotated");

        for (PimDomain domain : PimDomain.values()) {
            assertEquals("rotated", refreshed.credential(domain).refreshToken());
            assertEquals("fresh", refreshed.server(domain).password);
            // NOTE: what a calendar write announces a meeting by.
            assertEquals("jane@example.org", refreshed.server(domain).address);
        }
        assertEquals("one credential, however many domains name it", 1,
                refreshed.credentials().size());
    }

    @Test
    public void renewingOneConsentLeavesAnotherAlone() {
        // NOTE: Google issues its restricted mail scopes apart from contacts
        // ones, and renewing either must not sign the other out.
        AccountEntry account =
                AccountEntry.of(
                                "jane@example.org",
                                PimDomain.MAIL,
                                "https://gmail.example.org/",
                                oauth("mail-consent"))
                        .with(
                                PimDomain.CONTACTS,
                                "https://people.example.org/",
                                oauth("contacts-consent"));

        AccountEntry refreshed = account.refreshed(PimDomain.MAIL, "fresh", "mail-rotated");

        assertEquals("mail-rotated", refreshed.credential(PimDomain.MAIL).refreshToken());
        assertEquals("fresh", refreshed.server(PimDomain.MAIL).password);
        assertEquals(
                "the other consent keeps its own token",
                "contacts-consent",
                refreshed.credential(PimDomain.CONTACTS).refreshToken());
        assertEquals("access", refreshed.server(PimDomain.CONTACTS).password);
    }

    @Test
    public void anUnconnectedDomainAnswersRatherThanThrows() {
        AccountEntry account =
                AccountEntry.of(
                        "jane@example.org", PimDomain.MAIL, "imaps://x:993", password("secret"));

        assertNull(account.server(PimDomain.CALENDAR));
        assertNull(account.credential(PimDomain.CALENDAR));
        assertNull(account.connection(PimDomain.CALENDAR));
        assertFalse(account.covers(PimDomain.CALENDAR));
    }

    @Test
    public void aGoogleGrantAddedLaterTakesOverTheDomainsItCovers() {
        AccountEntry account =
                AccountEntry.of(
                        "jane@example.org",
                        PimDomain.MAIL,
                        "gmail://jane@example.org",
                        google("mail-consent", GMAIL));

        // NOTE: a later grant asks for what the account holds beside its own,
        // so it can replace it.
        assertEquals(GMAIL, account.grantedScope(Oauth.GOOGLE_CLIENT_ID));
        assertEquals(
                GMAIL + " " + CONTACTS,
                Oauth.union(account.grantedScope(Oauth.GOOGLE_CLIENT_ID), CONTACTS));

        AccountCredential union = google("union-consent", GMAIL + " " + CONTACTS);
        AccountEntry connected = account.with(PimDomain.CONTACTS, "google://", union);
        assertEquals(List.of(PimDomain.MAIL), connected.regrantable(union));

        AccountEntry regranted =
                connected.with(PimDomain.MAIL, "gmail://jane@example.org", union);
        assertEquals("one refresh token for the account", 1, regranted.credentials().size());
        assertEquals("union-consent", regranted.credential(PimDomain.MAIL).refreshToken());
        assertTrue(regranted.regrantable(union).isEmpty());
    }

    @Test
    public void aGrantMissingAScopeOrFromAnotherClientTakesNothingOver() {
        AccountEntry account =
                AccountEntry.of(
                                "jane@example.org",
                                PimDomain.MAIL,
                                "gmail://jane@example.org",
                                google("mail-consent", GMAIL))
                        .with(PimDomain.CALENDAR, "https://dav.example.org/", oauth("dav"));

        // NOTE: the user unticked Gmail on the consent screen: mail keeps its
        // grant.
        assertTrue(account.regrantable(google("narrow", CALENDAR + " " + CONTACTS)).isEmpty());

        // NOTE: a grant whose scopes were never recorded is not assumed
        // covered.
        AccountEntry unrecorded =
                AccountEntry.of(
                        "jane@example.org",
                        PimDomain.MAIL,
                        "gmail://jane@example.org",
                        google("old", null));
        assertTrue(unrecorded.regrantable(google("new", GMAIL + " " + CONTACTS)).isEmpty());
        assertEquals(null, unrecorded.grantedScope(Oauth.GOOGLE_CLIENT_ID));
    }
}
