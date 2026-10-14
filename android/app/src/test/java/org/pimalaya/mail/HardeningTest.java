package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The mail surface against what a hostile message hands it: links in a
 * message, and bodies that kill the app.
 */
@RunWith(RobolectricTestRunner.class)
public class HardeningTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void aTappedWebOrMailLinkOpensAsABrowserWould() {
        Intent web = MessageView.linkIntent(Uri.parse("https://example.org/a"), true, true);
        Intent mail = MessageView.linkIntent(Uri.parse("mailto:bob@example.org"), true, true);

        assertNotNull(web);
        assertEquals(Intent.ACTION_VIEW, web.getAction());
        assertTrue(web.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertNull(web.getComponent());
        assertNull(web.getSelector());
        assertNotNull(mail);
        assertNotNull(MessageView.linkIntent(Uri.parse("HTTP://example.org"), true, true));
    }

    @Test
    public void aLinkNotTappedInTheMessageItselfOpensNothing() {
        Uri tracker = Uri.parse("https://tracker.example/?id=victim");

        // A meta refresh, then an iframe.
        assertNull(MessageView.linkIntent(tracker, false, true));
        assertNull(MessageView.linkIntent(tracker, true, false));
        assertNull(MessageView.linkIntent(Uri.parse("someapp://action?x=1"), true, true));
        assertNull(MessageView.linkIntent(Uri.parse("org.pimalaya:/oauth2redirect"), true, true));
        assertNull(MessageView.linkIntent(Uri.parse("intent:#Intent;end"), true, true));
        assertNull(MessageView.linkIntent(Uri.parse("file:///data/x"), true, true));
        assertNull(MessageView.linkIntent(Uri.parse("relative"), true, true));
    }

    @Test
    public void aReadThatKilledTheAppIsGivenUpAfterItsAttempts() {
        String key = MailOwed.key("inbox", "m1");

        for (int attempt = 0; attempt < MailBodies.ATTEMPTS; attempt++) {
            assertFalse(MailPoison.poisoned(context, key));
            // NOTE: begun and never ended, as a crash leaves it.
            assertTrue(MailPoison.begin(context, key));
        }

        assertTrue(MailPoison.poisoned(context, key));
        assertFalse(MailPoison.begin(context, key));
    }

    @Test
    public void aReadThatEndsLeavesNoMark() {
        String key = MailOwed.key("inbox", "m2");

        for (int read = 0; read < MailBodies.ATTEMPTS + 2; read++) {
            assertTrue(MailPoison.begin(context, key));
            MailPoison.end(context, key);
        }

        assertFalse(MailPoison.poisoned(context, key));
    }
}
