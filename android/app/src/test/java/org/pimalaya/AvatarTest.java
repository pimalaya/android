package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** The disc every list row leads with. */
@RunWith(RobolectricTestRunner.class)
public class AvatarTest {
    @Test
    public void anInitialIsALetterOrAHash() {
        assertEquals("A", Avatar.letter("ada@example.org"));
        assertEquals("A", Avatar.letter("  ada lovelace"));
        assertEquals("a row with nothing to show still gets a disc", "#", Avatar.letter(""));
        assertEquals("#", Avatar.letter("1password"));
    }

    @Test
    public void aDeclaredColourWinsAndAnUnreadableOneFallsBack() {
        assertEquals(Color.RED, Avatar.colorOf("#ff0000", "Work"));

        // Servers send colours the platform cannot parse (named CSS
        // colours, `#rgb`, an empty element). The derived hue is a better
        // answer than a crash, and the same one the calendar would have
        // had with no colour at all.
        assertEquals(Avatar.colorOf(null, "Work"), Avatar.colorOf("chartreuse", "Work"));
        assertEquals(Avatar.colorOf(null, "Work"), Avatar.colorOf("", "Work"));
        assertNotEquals(Avatar.colorOf(null, "Work"), Avatar.colorOf(null, "Personal"));
    }
}
