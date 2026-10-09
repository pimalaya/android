package org.pimalaya;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Account;
import org.pimalaya.client.PimalayaClient;
import org.robolectric.RobolectricTestRunner;

/**
 * The account traits the bridge reports and the composer, the account settings
 * and the entry page decide by, read through the real bridge so the wire's key
 * names cannot drift from what Java asks for. Under Robolectric, as every test
 * loading the bridge is: the library loads into one class loader only.
 */
@RunWith(RobolectricTestRunner.class)
public class AccountTraitsTest {
    @Test
    public void aJmapAccountSavedWithNoSubmitEndpointSendsOverItsSession() {
        // Saved by a build that could not send over JMAP, so with no submit
        // endpoint: after the upgrade it sends with nothing to set up.
        Account jmap = new Account("jmap://api.fastmail.com/jmap/session", "", "token", null);
        Account imap = new Account("imaps://imap.example.com:993", "jane", "secret", null);

        assertTrue(PimalayaClient.submitsOverSession(jmap));
        assertFalse(PimalayaClient.submitsOverSession(imap));
        assertFalse(PimalayaClient.submitsOverSession(null));
    }

    @Test
    public void aJmapCalendarTakesNoEventWrite() {
        assertFalse(PimalayaClient.writesEvents("jmap://api.fastmail.com/jmap/session/u1/c1"));
        assertTrue(PimalayaClient.writesEvents("https://dav.example.com/calendars/jane/home/"));
        assertTrue(PimalayaClient.writesEvents("msgraph://jane@outlook.com/c1"));
    }
}
