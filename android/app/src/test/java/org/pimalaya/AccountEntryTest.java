package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.pimalaya.client.Account;

import java.util.List;

/**
 * One account is one identity covering several domains, not several accounts
 * wearing one address.
 *
 * <p>These pin the consequences that argument was made for: connecting a second
 * domain must not evict the first, refreshing one domain's token must not touch
 * another's, and the domains an account covers must stay answerable without
 * reassembling it from a roster.
 */
public class AccountEntryTest {
    private static AccountConnection connection(String baseUrl, String password) {
        return new AccountConnection(new Account(baseUrl, "jane", password));
    }

    private static AccountConnection oauth(String baseUrl, String refreshToken) {
        return new AccountConnection(
                new Account(baseUrl, "", "access"),
                refreshToken,
                "https://accounts.example.org/token",
                "client-id",
                null);
    }

    @Test
    public void connectingASecondDomainKeepsTheFirst() {
        AccountEntry account =
                AccountEntry.of(
                        "jane@example.org",
                        PimDomain.CONTACTS,
                        connection("https://dav.example.org/", "secret"));

        AccountEntry both =
                account.with(PimDomain.MAIL, connection("imaps://mail.example.org:993", "secret"));

        assertEquals(List.of(PimDomain.MAIL, PimDomain.CONTACTS), List.copyOf(both.domains()));
        assertTrue(both.covers(PimDomain.CONTACTS));
        assertTrue(both.covers(PimDomain.MAIL));
        assertFalse(both.covers(PimDomain.CALENDAR));
        assertEquals("https://dav.example.org/", both.server(PimDomain.CONTACTS).baseUrl);
    }

    @Test
    public void refreshingOneDomainLeavesTheOthersAlone() {
        // Two consents, two tokens, one account. Writing the mail refresh over
        // the calendar's would log the user out of a domain they never touched.
        AccountEntry account =
                new AccountEntry(
                        "jane@example.org",
                        java.util.Map.of(
                                PimDomain.MAIL, oauth("https://jmap.example.org/", "mail-refresh"),
                                PimDomain.CALENDAR,
                                        oauth("https://jmap.example.org/", "cal-refresh")));

        AccountConnection mail = account.connection(PimDomain.MAIL);
        AccountEntry refreshed =
                account.with(PimDomain.MAIL, mail.withAccessToken("fresh", "mail-rotated"));

        assertEquals("fresh", refreshed.server(PimDomain.MAIL).password);
        assertEquals("mail-rotated", refreshed.connection(PimDomain.MAIL).refreshToken);
        assertEquals(
                "the untouched domain keeps its own token",
                "cal-refresh",
                refreshed.connection(PimDomain.CALENDAR).refreshToken);
    }

    @Test
    public void anUnconnectedDomainAnswersRatherThanThrows() {
        AccountEntry account =
                AccountEntry.of(
                        "jane@example.org", PimDomain.MAIL, connection("imaps://x:993", "secret"));

        assertNull(account.server(PimDomain.CALENDAR));
        assertNull(account.connection(PimDomain.CALENDAR));
        assertFalse(account.covers(PimDomain.CALENDAR));
    }

    @Test
    public void disconnectingLeavesTheAccountAndItsOtherDomains() {
        AccountEntry account =
                AccountEntry.of(
                                "jane@example.org",
                                PimDomain.CONTACTS,
                                connection("https://dav.example.org/", "secret"))
                        .with(PimDomain.MAIL, connection("imaps://mail.example.org:993", "secret"));

        AccountEntry remaining = account.without(PimDomain.MAIL);

        assertEquals("jane@example.org", remaining.email);
        assertTrue(remaining.covers(PimDomain.CONTACTS));
        assertFalse(remaining.covers(PimDomain.MAIL));
    }
}
