package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.accounts.Account;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Colors;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A mirrored calendar's {@code Calendars} row, as docs/calendar-mapping.md's
 * calendar level sets it: every column on insert, the user's own columns
 * left alone after it, a name or a colour rewritten only when the
 * collection's own changed since it was last projected; and the account's
 * event colours, the CSS named ones.
 */
@RunWith(RobolectricTestRunner.class)
public class CalendarRowsTest {
    private static final String EMAIL = "jane@example.com";
    private static final Account ACCOUNT = new Account(EMAIL, Accounts.TYPE);

    private static PimdirCollections.Stored calendar(
            String name, String color, String role, boolean writable) {
        return new PimdirCollections.Stored(
                "acc1:https://dav.example.com/cal/work/", EMAIL, name, null, color, role, writable);
    }

    @Test
    public void aNewCalendarsRowCarriesEveryColumn() {
        ContentValues values =
                CalendarRows.insert(
                        ACCOUNT, calendar("Work", "#3366CC", "default", true), true, "Europe/Paris");

        assertEquals(EMAIL, values.getAsString(Calendars.ACCOUNT_NAME));
        assertEquals(Accounts.TYPE, values.getAsString(Calendars.ACCOUNT_TYPE));
        assertEquals("acc1:https://dav.example.com/cal/work/", values.getAsString(Calendars._SYNC_ID));
        assertEquals("Work", values.getAsString(Calendars.NAME));
        assertEquals("Work", values.getAsString(Calendars.CALENDAR_DISPLAY_NAME));
        assertEquals(0xff3366cc, (int) values.getAsInteger(Calendars.CALENDAR_COLOR));
        assertEquals(
                Calendars.CAL_ACCESS_OWNER,
                (int) values.getAsInteger(Calendars.CALENDAR_ACCESS_LEVEL));
        assertEquals(EMAIL, values.getAsString(Calendars.OWNER_ACCOUNT));
        assertEquals(1, (int) values.getAsInteger(Calendars.IS_PRIMARY));
        assertEquals(1, (int) values.getAsInteger(Calendars.SYNC_EVENTS));
        assertEquals(1, (int) values.getAsInteger(Calendars.VISIBLE));
        assertEquals("Europe/Paris", values.getAsString(Calendars.CALENDAR_TIME_ZONE));
        assertEquals("1", values.getAsString(Calendars.ALLOWED_REMINDERS));
        assertEquals(5, (int) values.getAsInteger(Calendars.MAX_REMINDERS));
        assertEquals("0,1", values.getAsString(Calendars.ALLOWED_AVAILABILITY));
        assertEquals("0,1,2,3", values.getAsString(Calendars.ALLOWED_ATTENDEE_TYPES));
        assertEquals(1, (int) values.getAsInteger(Calendars.CAN_ORGANIZER_RESPOND));
        assertEquals(1, (int) values.getAsInteger(Calendars.CAN_MODIFY_TIME_ZONE));
        assertEquals(0, (int) values.getAsInteger(Calendars.CAN_PARTIALLY_UPDATE));
        assertEquals("Work", values.getAsString(Calendars.CAL_SYNC1));
        assertEquals(String.valueOf(0xff3366cc), values.getAsString(Calendars.CAL_SYNC2));
        assertEquals(21, values.size());
    }

    @Test
    public void aReadOnlyCalendarIsReadAccessAndNotPrimary() {
        ContentValues values =
                CalendarRows.insert(ACCOUNT, calendar("Holidays", null, null, false), false, null);

        assertEquals(
                Calendars.CAL_ACCESS_READ,
                (int) values.getAsInteger(Calendars.CALENDAR_ACCESS_LEVEL));
        assertEquals(0, (int) values.getAsInteger(Calendars.IS_PRIMARY));
    }

    @Test
    public void aZoneJavaTimeDoesNotKnowIsLeftEmpty() {
        ContentValues unknown =
                CalendarRows.insert(ACCOUNT, calendar("Work", null, null, true), false, "Mars/Olympus");
        ContentValues none =
                CalendarRows.insert(ACCOUNT, calendar("Work", null, null, true), false, null);

        assertTrue(unknown.containsKey(Calendars.CALENDAR_TIME_ZONE));
        assertNull(unknown.getAsString(Calendars.CALENDAR_TIME_ZONE));
        assertNull(none.getAsString(Calendars.CALENDAR_TIME_ZONE));
    }

    @Test
    public void aCalendarWithoutColourTakesItsAccountsAvatarHue() {
        Context context = RuntimeEnvironment.getApplication();
        InsetDrawable avatar = (InsetDrawable) Avatar.circle(context, EMAIL);
        int hue = ((GradientDrawable) avatar.getDrawable()).getColor().getDefaultColor();

        ContentValues values =
                CalendarRows.insert(ACCOUNT, calendar("Work", null, null, true), false, null);

        assertEquals(hue, (int) values.getAsInteger(Calendars.CALENDAR_COLOR));
    }

    @Test
    public void anUnchangedCollectionLeavesThePhonesNameAndColour() {
        PimdirCollections.Stored work = calendar("Work", "#3366CC", null, true);

        ContentValues values =
                CalendarRows.update(work, false, null, "Work", String.valueOf(0xff3366cc));

        for (String column :
                new String[] {
                    Calendars.NAME,
                    Calendars.CALENDAR_DISPLAY_NAME,
                    Calendars.CAL_SYNC1,
                    Calendars.CALENDAR_COLOR,
                    Calendars.CAL_SYNC2,
                    Calendars.SYNC_EVENTS,
                    Calendars.VISIBLE,
                    Calendars._SYNC_ID
                }) {
            assertFalse(column, values.containsKey(column));
        }
        assertEquals(
                Calendars.CAL_ACCESS_OWNER,
                (int) values.getAsInteger(Calendars.CALENDAR_ACCESS_LEVEL));
    }

    @Test
    public void aRenamedCollectionRenamesTheRowAlone() {
        PimdirCollections.Stored work = calendar("Office", "#3366CC", null, true);

        ContentValues values =
                CalendarRows.update(work, false, null, "Work", String.valueOf(0xff3366cc));

        assertEquals("Office", values.getAsString(Calendars.NAME));
        assertEquals("Office", values.getAsString(Calendars.CALENDAR_DISPLAY_NAME));
        assertEquals("Office", values.getAsString(Calendars.CAL_SYNC1));
        assertFalse(values.containsKey(Calendars.CALENDAR_COLOR));
        assertFalse(values.containsKey(Calendars.CAL_SYNC2));
    }

    @Test
    public void aRecolouredCollectionRecoloursTheRowAlone() {
        PimdirCollections.Stored work = calendar("Work", "#CC3366", null, true);

        ContentValues values =
                CalendarRows.update(work, false, null, "Work", String.valueOf(0xff3366cc));

        assertEquals(0xffcc3366, (int) values.getAsInteger(Calendars.CALENDAR_COLOR));
        assertEquals(String.valueOf(0xffcc3366), values.getAsString(Calendars.CAL_SYNC2));
        assertFalse(values.containsKey(Calendars.NAME));
        assertFalse(values.containsKey(Calendars.CAL_SYNC1));
    }

    @Test
    public void aRowNeverProjectedTakesBoth() {
        ContentValues values =
                CalendarRows.update(calendar("Work", "#3366CC", null, true), false, null, null, null);

        assertEquals("Work", values.getAsString(Calendars.NAME));
        assertEquals(0xff3366cc, (int) values.getAsInteger(Calendars.CALENDAR_COLOR));
    }

    @Test
    public void aCollectionTurnedReadOnlyTurnsTheRowRead() {
        ContentValues values =
                CalendarRows.update(
                        calendar("Work", "#3366CC", null, false),
                        false,
                        null,
                        "Work",
                        String.valueOf(0xff3366cc));

        assertEquals(
                Calendars.CAL_ACCESS_READ,
                (int) values.getAsInteger(Calendars.CALENDAR_ACCESS_LEVEL));
    }

    @Test
    public void theColoursAreTheCssNamedOnes() {
        List<ContentValues> colors = CalendarRows.colors(ACCOUNT, Set.of());

        assertEquals(148, colors.size());
        Set<String> keys = new HashSet<>();
        for (ContentValues values : colors) {
            assertEquals(EMAIL, values.getAsString(Colors.ACCOUNT_NAME));
            assertEquals(Accounts.TYPE, values.getAsString(Colors.ACCOUNT_TYPE));
            assertEquals(Colors.TYPE_EVENT, (int) values.getAsInteger(Colors.COLOR_TYPE));
            assertEquals(0xff, values.getAsInteger(Colors.COLOR) >>> 24);
            keys.add(values.getAsString(Colors.COLOR_KEY));
            if ("rebeccapurple".equals(values.getAsString(Colors.COLOR_KEY))) {
                assertEquals(0xff663399, (int) values.getAsInteger(Colors.COLOR));
            }
            if ("lightgoldenrodyellow".equals(values.getAsString(Colors.COLOR_KEY))) {
                assertEquals(0xfffafad2, (int) values.getAsInteger(Colors.COLOR));
            }
        }
        assertEquals(148, keys.size());
        assertTrue(keys.contains("grey"));
        assertTrue(keys.contains("gray"));
    }

    @Test
    public void theColoursAlreadySeededAreNotAddedAgain() {
        List<ContentValues> colors = CalendarRows.colors(ACCOUNT, Set.of("red", "blue"));

        assertEquals(146, colors.size());
        for (ContentValues values : colors) {
            assertFalse(Set.of("red", "blue").contains(values.getAsString(Colors.COLOR_KEY)));
        }
        assertTrue(CalendarRows.colors(ACCOUNT, CssColors.NAMED.keySet()).isEmpty());
    }

    @Test
    public void anUpdateTheRowAlreadyHoldsIsNotWritten() {
        ContentValues values =
                CalendarRows.update(calendar("Work", "#3366CC", null, true), true, null, "Work",
                        String.valueOf(0xff3366cc));
        Map<String, String> current = new HashMap<>();
        for (String column : values.keySet()) {
            current.put(column, values.getAsString(column));
        }

        assertTrue(CalendarRows.unchanged(values, current));

        current.put(Calendars.IS_PRIMARY, "0");
        assertFalse(CalendarRows.unchanged(values, current));
        current.remove(Calendars.IS_PRIMARY);
        assertFalse("a column the row does not hold yet", CalendarRows.unchanged(values, current));
    }
}
