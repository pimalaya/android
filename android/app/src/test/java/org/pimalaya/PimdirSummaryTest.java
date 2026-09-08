package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;

/**
 * The summaries and sort keys the app writes into a pimdir store.
 *
 * <p>The sort keys are what these are really about. The column is TEXT compared
 * byte for byte, so a key is correct only if its <em>ordering</em> is, and that
 * is not something reading the code tells you: it has to be sorted.
 */
@RunWith(RobolectricTestRunner.class)
public class PimdirSummaryTest {
    @Test
    public void aMailSummaryCarriesTheEnvelopeAndNoFlags() throws Exception {
        JSONObject row =
                PimdirSummary.mail(
                                "<abc@host>", "Hello", "Alice", "Alice@Example.ORG",
                                "bob@example.org", "Mon, 5 Jan 2026 09:00:00 +0000", 1234, true)
                        .getJSONObject("mail");

        assertEquals("the Message-ID is stored bare", "abc@host", row.getString("message_id"));
        assertEquals("Hello", row.getString("subject"));
        assertEquals("2026-01-05T09:00:00Z", row.getString("date"));
        assertEquals(1234, row.getInt("size"));
        assertFalse("flags are the item's state, not its summary", row.has("flags"));

        // The sender column is the addr-spec, canonical (Annex A.6); the
        // display name rides beside it, so a reader gets an address where an
        // address goes and the row still has a name to render.
        assertEquals("alice@example.org", row.getString("sender"));
        assertEquals("Alice", row.getString("sender_name"));
        assertTrue(row.getBoolean("attachment"));
    }

    @Test
    public void aMailSummaryNamesEachAddressUnderItsRole() throws Exception {
        JSONArray addresses =
                PimdirSummary.mail(
                                null, "Hello", "Alice", "alice@example.org", "Bob@Example.org",
                                "Mon, 5 Jan 2026 09:00:00 +0000", 0, false)
                        .getJSONObject("mail")
                        .getJSONArray("addresses");

        assertEquals(2, addresses.length());
        assertEquals("from", addresses.getJSONObject(0).getString("role"));
        assertEquals(0, addresses.getJSONObject(0).getInt("position"));
        assertEquals("Alice", addresses.getJSONObject(0).getString("name"));
        assertEquals("to", addresses.getJSONObject(1).getString("role"));
        assertEquals("each role positions on its own", 0,
                addresses.getJSONObject(1).getInt("position"));
        assertEquals("bob@example.org", addresses.getJSONObject(1).getString("address"));
    }

    @Test
    public void aMailSummaryLeavesOutWhatTheMessageDoesNotHave() throws Exception {
        // An absent field is absent, not empty: the column is nullable and the
        // wire says unknown by leaving the key out, so nothing writes a zero
        // size or a blank sender into every row of a spine mirror.
        JSONObject row =
                PimdirSummary.mail(
                                null, "Hello", "", "alice@example.org", null,
                                "Mon, 5 Jan 2026 09:00:00 +0000", 0, false)
                        .getJSONObject("mail");

        assertFalse(row.has("message_id"));
        assertFalse(row.has("sender_name"));
        assertFalse(row.has("size"));
        assertEquals("one address, the sender's", 1, row.getJSONArray("addresses").length());
    }

    @Test
    public void mailSortsChronologicallyByByteOrder() {
        // The column is compared BINARY, so ordering the strings must order the
        // instants. A local offset would break this while naming the same time.
        String january = PimdirSummary.mailSortKey("Mon, 5 Jan 2026 09:00:00 +0000");
        String february = PimdirSummary.mailSortKey("Thu, 5 Feb 2026 09:00:00 +0000");
        String sameInstantOtherZone = PimdirSummary.mailSortKey("Mon, 5 Jan 2026 10:00:00 +0100");

        assertTrue(january.compareTo(february) < 0);
        assertEquals("the same instant is the same key", january, sameInstantOtherZone);
        assertEquals("2026-01-05T09:00:00Z", january);
    }

    @Test
    public void anUnreadableDateIsUnknownRatherThanGuessed() {
        // Empty has a defined position (the end of a newest-first listing);
        // inventing an epoch date would read as data instead of as absence.
        assertEquals("", PimdirSummary.mailSortKey("not a date"));
        assertEquals("", PimdirSummary.mailSortKey(null));

        String[] keys = {PimdirSummary.mailSortKey("bad"), "2026-01-05T09:00:00Z"};
        Arrays.sort(keys, Collections.reverseOrder());
        assertEquals("unknown sorts last descending", "", keys[1]);
    }

    @Test
    public void anAddressIsTheAddrSpecAloneLowercased() {
        // Annex A.6, and the same normalisation io-pimdir performs on a body:
        // the two derivations meet in one column, so a message summarised from
        // an envelope has to name the person the same way one read whole does.
        assertEquals("alice@example.org", PimdirSummary.canonical("Alice <Alice@Example.ORG>"));
        assertEquals("bob@x.y", PimdirSummary.canonical("mailto:Bob@x.y"));
        assertEquals("carol@x.y", PimdirSummary.canonical("  carol@x.y "));
    }

    @Test
    public void aCalendarKeyWidensTheCivilStampToTheSharedShape() {
        // The expander yields civil stamps because recurrence expands on
        // wall-clock time; the column wants the same fixed width mail uses, so a
        // date-range read pages a calendar through the same statements.
        assertEquals("2026-01-05T09:00:00Z", PimdirSummary.calendarSortKey("20260105T090000"));
        assertEquals("an all-day entry starts its day", "2026-02-14T00:00:00Z",
                PimdirSummary.calendarSortKey("20260214"));
        assertEquals("", PimdirSummary.calendarSortKey("nope"));

        assertTrue(
                PimdirSummary.calendarSortKey("20260105T090000")
                        .compareTo(PimdirSummary.calendarSortKey("20260105T100000"))
                        < 0);
    }

    @Test
    public void theThreeKindsShareOneOrderableShape() {
        // One column serves all three, so a mail key and a calendar key have to
        // be comparable with each other rather than merely self-consistent.
        String mail = PimdirSummary.mailSortKey("Mon, 5 Jan 2026 09:00:00 +0000");
        String event = PimdirSummary.calendarSortKey("20260105T090000");

        assertEquals("the same instant is the same key across kinds", mail, event);
        assertEquals(20, mail.length());
    }
}
