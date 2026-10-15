package org.pimalaya;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.robolectric.RobolectricTestRunner;

/**
 * The account traits the bridge reports and the screens decide by.
 *
 * <p>Read through the real bridge, so the wire's key names cannot drift; under
 * Robolectric, as the library loads into one class loader only.
 */
@RunWith(RobolectricTestRunner.class)
public class AccountTraitsTest {
    @Test
    public void aJmapAccountSavedWithNoSubmitEndpointSendsOverItsSession() {
        // NOTE: saved by a build that could not send over JMAP, so with no
        // submit endpoint: after the upgrade it sends with nothing to set up.
        Account jmap = new Account("jmap://api.fastmail.com/jmap/session", "", "token", null);
        Account imap = new Account("imaps://imap.example.com:993", "jane", "secret", null);

        assertTrue(AccountClient.submitsOverSession(jmap));
        assertFalse(AccountClient.submitsOverSession(imap));
        assertFalse(AccountClient.submitsOverSession(null));
    }
}
