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
 * The meta and sort-key conventions the app writes into a pimdir store.
 *
 * <p>The sort keys are what these are really about. The column is TEXT compared
 * byte for byte, so a key is correct only if its <em>ordering</em> is, and that
 * is not something reading the code tells you: it has to be sorted.
 */
@RunWith(RobolectricTestRunner.class)
public class PimdirMetaTest {
    @Test
    public void aMailSummaryCarriesTheEnvelopeAndNoFlags() throws Exception {
        String meta =
                PimdirMeta.mail(
                        "<abc@host>", "Hello", "Alice", "alice@example.org", "bob@example.org",
                        "Mon, 5 Jan 2026 09:00:00 +0000", 1234, true);
        JSONObject parsed = new JSONObject(meta);

        assertEquals(1, parsed.getInt("v"));
        assertEquals("the Message-ID is stored bare", "abc@host", parsed.getString("message_id"));
        assertEquals("Hello", parsed.getString("subject"));
        assertEquals("2026-01-05T09:00:00Z", parsed.getString("date"));
        assertEquals(1234, parsed.getInt("size"));
        assertFalse("flags are the item's state, not its summary", parsed.has("flags"));

        // The spec's `from` is the address; the display name rides beside
        // it, so a reader that knows only the convention still gets the
        // field it expects rather than a name where an address goes.
        assertEquals("alice@example.org", parsed.getString("from"));
        assertEquals("Alice", parsed.getString("from_name"));
        assertTrue(parsed.getBoolean("attachment"));
    }

    @Test
    public void aMailSummaryLeavesOutWhatTheMessageDoesNotHave() throws Exception {
        // An absent field is absent, not empty or false: the summary is
        // read with optString and optBoolean, so writing the negatives
        // would only grow every row of a spine mirror.
        JSONObject parsed =
                new JSONObject(
                        PimdirMeta.mail(
                                null, "Hello", "", "alice@example.org", null,
                                "Mon, 5 Jan 2026 09:00:00 +0000", 0, false));

        assertFalse(parsed.has("from_name"));
        assertFalse(parsed.has("attachment"));
        assertFalse(parsed.has("size"));
    }

    @Test
    public void mailSortsChronologicallyByByteOrder() {
        // The column is compared BINARY, so ordering the strings must order the
        // instants. A local offset would break this while naming the same time.
        String january = PimdirMeta.mailSortKey("Mon, 5 Jan 2026 09:00:00 +0000");
        String february = PimdirMeta.mailSortKey("Thu, 5 Feb 2026 09:00:00 +0000");
        String sameInstantOtherZone = PimdirMeta.mailSortKey("Mon, 5 Jan 2026 10:00:00 +0100");

        assertTrue(january.compareTo(february) < 0);
        assertEquals("the same instant is the same key", january, sameInstantOtherZone);
        assertEquals("2026-01-05T09:00:00Z", january);
    }

    @Test
    public void anUnreadableDateIsUnknownRatherThanGuessed() {
        // Empty has a defined position (the end of a newest-first listing);
        // inventing an epoch date would read as data instead of as absence.
        assertEquals("", PimdirMeta.mailSortKey("not a date"));
        assertEquals("", PimdirMeta.mailSortKey(null));

        String[] keys = {PimdirMeta.mailSortKey("bad"), "2026-01-05T09:00:00Z"};
        Arrays.sort(keys, Collections.reverseOrder());
        assertEquals("unknown sorts last descending", "", keys[1]);
    }

    @Test
    public void aContactSummaryCarriesItsDisplayNameAndEmails() throws Exception {
        JSONObject index = new JSONObject();
        index.put("uid", "urn:uuid:4fbe");
        index.put("name", "Jane Doe");
        index.put("emails", new JSONArray().put("jane@example.org"));

        JSONObject parsed = new JSONObject(PimdirMeta.contact(index, 421));

        assertEquals(1, parsed.getInt("v"));
        assertEquals("urn:uuid:4fbe", parsed.getString("uid"));
        assertEquals("Jane Doe", parsed.getString("fn"));
        assertEquals("jane@example.org", parsed.getJSONArray("emails").getString(0));
        assertEquals(421, parsed.getInt("size"));
    }

    @Test
    public void aContactSummaryAlsoCarriesWhatAListRowNeeds() throws Exception {
        JSONObject index = new JSONObject();
        index.put("name", "Jane Doe");
        index.put("phone", "+33123456789");
        index.put("info", "Acme Corp");
        index.put("hash", "0f1e2d3c4b5a6978");

        JSONObject parsed = new JSONObject(PimdirMeta.contact(index, 0));

        // Additions to the SPEC.md 13 convention, so a row renders without
        // parsing a vCard. A reader that does not know them ignores them.
        assertEquals("+33123456789", parsed.getString("phone"));
        assertEquals("Acme Corp", parsed.getString("info"));
        assertEquals("0f1e2d3c4b5a6978", parsed.getString("hash"));
        assertFalse("no size means unknown, not zero", parsed.has("size"));
        assertFalse("an absent field is absent, not empty", parsed.has("uid"));
    }

    @Test
    public void contactsSortCasefoldedSoWritersDoNotInterleave() {
        // Two connectors disagreeing about casing would otherwise file 'alice'
        // and 'Alice' in different places in one address book.
        assertEquals("alice", PimdirMeta.contactSortKey("  Alice  "));
        assertEquals(PimdirMeta.contactSortKey("ALICE"), PimdirMeta.contactSortKey("alice"));

        String[] keys = {
            PimdirMeta.contactSortKey("Bob"),
            PimdirMeta.contactSortKey("alice"),
            PimdirMeta.contactSortKey(null),
        };
        Arrays.sort(keys);
        assertEquals("a nameless contact is visible at the head", "", keys[0]);
        assertEquals("alice", keys[1]);
        assertEquals("bob", keys[2]);
    }

    @Test
    public void aCalendarKeyWidensTheCivilStampToTheSharedShape() {
        // The expander yields civil stamps because recurrence expands on
        // wall-clock time; the column wants the same fixed width mail uses, so a
        // date-range read pages a calendar through the same statements.
        assertEquals("2026-01-05T09:00:00Z", PimdirMeta.calendarSortKey("20260105T090000"));
        assertEquals("an all-day entry starts its day", "2026-02-14T00:00:00Z",
                PimdirMeta.calendarSortKey("20260214"));
        assertEquals("", PimdirMeta.calendarSortKey("nope"));

        assertTrue(
                PimdirMeta.calendarSortKey("20260105T090000")
                        .compareTo(PimdirMeta.calendarSortKey("20260105T100000"))
                        < 0);
    }

    @Test
    public void theThreeKindsShareOneOrderableShape() {
        // One column serves all three, so a mail key and a calendar key have to
        // be comparable with each other rather than merely self-consistent.
        String mail = PimdirMeta.mailSortKey("Mon, 5 Jan 2026 09:00:00 +0000");
        String event = PimdirMeta.calendarSortKey("20260105T090000");

        assertEquals("the same instant is the same key across kinds", mail, event);
        assertEquals(20, mail.length());
    }

    @Test
    public void aCalendarSummaryCarriesWhatAnAgendaRowRenders() throws Exception {
        String meta =
                PimdirMeta.calendar(
                        "ev-1", "Standup", "Room 2", "2026-01-05T09:00:00Z",
                        "2026-01-05T09:15:00Z", false);
        JSONObject parsed = new JSONObject(meta);

        assertEquals(1, parsed.getInt("v"));
        assertEquals("Standup", parsed.getString("summary"));
        assertEquals("Room 2", parsed.getString("location"));
        assertFalse("all_day is written only when true", parsed.has("all_day"));

        JSONObject allDay =
                new JSONObject(PimdirMeta.calendar("ev-2", "Holiday", null, "2026-02-14T00:00:00Z",
                        null, true));
        assertTrue(allDay.getBoolean("all_day"));
        assertFalse("an absent field is omitted, not null", allDay.has("location"));
    }
}
