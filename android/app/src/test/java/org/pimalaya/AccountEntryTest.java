package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * One account is one identity covering several domains, not several
 * accounts wearing one address.
 *
 * <p>These pin the consequences that argument was made for: connecting a
 * second domain must not evict the first, one consent renewed must renew
 * every domain that signed in with it while leaving a second consent
 * alone, and the domains an account covers must stay answerable without
 * reassembling it from a roster.
 */
public class AccountEntryTest {
    private static AccountCredential password(String secret) {
        return AccountCredential.password("jane", secret);
    }

    private static AccountCredential oauth(String refreshToken) {
        return AccountCredential.oauth(
                "access", refreshToken, "https://accounts.example.org/token", "client-id", null);
    }

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
        // The reason a credential is stored once: a provider that issues
        // a fresh refresh token on every use retires the old one, so a
        // domain left holding a copy could never sign in again.
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
            assertEquals("rotated", refreshed.credential(domain).refreshToken);
            assertEquals("fresh", refreshed.server(domain).password);
        }
        assertEquals("one credential, however many domains name it", 1,
                refreshed.credentials().size());
    }

    @Test
    public void renewingOneConsentLeavesAnotherAlone() {
        // Two grants, two credentials: Google issues its restricted mail
        // scopes separately from its contacts ones, and renewing either
        // must not log the user out of the other.
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

        assertEquals("mail-rotated", refreshed.credential(PimDomain.MAIL).refreshToken);
        assertEquals("fresh", refreshed.server(PimDomain.MAIL).password);
        assertEquals(
                "the other consent keeps its own token",
                "contacts-consent",
                refreshed.credential(PimDomain.CONTACTS).refreshToken);
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
    public void disconnectingLeavesTheAccountAndItsOtherDomains() {
        AccountEntry account =
                AccountEntry.of(
                                "jane@example.org",
                                PimDomain.CONTACTS,
                                "https://dav.example.org/",
                                password("secret"))
                        .with(
                                PimDomain.MAIL,
                                "imaps://mail.example.org:993",
                                password("secret"));

        AccountEntry remaining = account.without(PimDomain.MAIL);

        assertEquals("jane@example.org", remaining.email);
        assertTrue(remaining.covers(PimDomain.CONTACTS));
        assertFalse(remaining.covers(PimDomain.MAIL));
    }

    @Test
    public void disconnectingDropsACredentialNothingElseSignsInWith() {
        AccountCredential shared = oauth("one-consent");
        AccountEntry account =
                AccountEntry.of(
                                "jane@example.org",
                                PimDomain.MAIL,
                                "jmap://api.example.org/",
                                shared)
                        .with(PimDomain.CALENDAR, "jmap://api.example.org/", shared)
                        .with(
                                PimDomain.CONTACTS,
                                "https://people.example.org/",
                                oauth("contacts-consent"));

        // A secret nothing can present is one the app has no business
        // keeping; a shared one stays while another domain names it.
        AccountEntry withoutContacts = account.without(PimDomain.CONTACTS);
        assertEquals(1, withoutContacts.credentials().size());

        AccountEntry withoutMail = withoutContacts.without(PimDomain.MAIL);
        assertEquals("the calendar still signs in with it", 1, withoutMail.credentials().size());

        assertEquals(0, withoutMail.without(PimDomain.CALENDAR).credentials().size());
    }
}
