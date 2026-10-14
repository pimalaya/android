package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The marker set writes the bytes the JSON arrays it replaced wrote: the
 * literals below were captured on Stager's has/withFlag and the
 * JSONArray each flag set crossed as, for the same inputs, under the
 * device's org.json (same flags, same order, same spellings).
 */
@RunWith(RobolectricTestRunner.class)
public class FlagsTest {
    private static String text(Flags flags) {
        return flags.toJson().toString();
    }

    @Test
    public void aToggleWritesWhatWithFlagWrote() {
        Flags seenTwice = new Flags(List.of("\\Seen", "$junk", "\\Seen"));
        assertEquals("[\"$junk\"]", text(seenTwice.with(Flags.Marker.SEEN, false)));
        assertEquals(
                "[\"$junk\",\"\\\\Seen\"]",
                text(new Flags(List.of("$junk", "\\Seen")).with(Flags.Marker.SEEN, true)));
        assertEquals("[\"\\\\Flagged\"]", text(Flags.none().with(Flags.Marker.FLAGGED, true)));
        assertEquals(
                "[\"a\\/b\"]",
                text(new Flags(List.of("a/b", "\\Deleted")).with(Flags.Marker.DELETED, false)));
        assertEquals(
                "[\"\\\\Answered\",\"\\\\Deleted\"]",
                text(new Flags(List.of("\\Answered")).with(Flags.Marker.DELETED, true)));
    }

    @Test
    public void aMarkerIsFoundAsHasFoundIt() {
        Flags seenTwice = new Flags(List.of("\\Seen", "$junk", "\\Seen"));
        assertTrue(seenTwice.has(Flags.Marker.SEEN));
        assertFalse(seenTwice.has(Flags.Marker.FLAGGED));
        assertFalse(Flags.read(null).has(Flags.Marker.SEEN));
    }

    @Test
    public void everyMarkerIsSpelledTheImapWay() {
        assertEquals(
                "[\"\\\\Seen\",\"\\\\Answered\",\"\\\\Flagged\",\"\\\\Deleted\"]",
                text(
                        Flags.of(
                                Flags.Marker.SEEN,
                                Flags.Marker.ANSWERED,
                                Flags.Marker.FLAGGED,
                                Flags.Marker.DELETED)));
        assertEquals("[]", text(Flags.none()));
    }

    @Test
    public void theStoreColumnReadsBackToTheSameBytes() throws Exception {
        String column = "[\"\\\\Seen\",\"$junk\",\"a\\/b\"]";
        assertEquals(column, text(Flags.parse(column)));
        assertEquals(List.of("\\Seen", "$junk", "a/b"), Flags.parse(column).markers());
        assertEquals(Flags.none(), Flags.parse(null));
        assertEquals(Flags.none(), Flags.parse(""));
    }

    @Test(expected = JSONException.class)
    public void anUnreadableColumnThrows() throws Exception {
        Flags.parse("{");
    }
}
