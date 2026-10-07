package org.pimalaya;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * What the merged list's last row says once a widening for older mail is
 * over: that the network is needed only when there is none, and otherwise
 * that older mail could not be loaded, which a tap tries again.
 */
public class MailOlderTest {
    @Test
    public void aWideningThatWentThroughSaysNothing() {
        assertEquals(0, MailList.stallOf(true, true));
    }

    @Test
    public void offlineTheRowAsksForTheNetwork() {
        assertEquals(R.string.mail_more_offline, MailList.stallOf(false, false));
    }

    @Test
    public void onlineAFailureIsNotBlamedOnTheNetwork() {
        // NOTE: the owner's device, online, read "older mail needs the
        // network" when the server had refused a mailbox name.
        assertEquals(R.string.mail_more_failed, MailList.stallOf(false, true));
    }
}
