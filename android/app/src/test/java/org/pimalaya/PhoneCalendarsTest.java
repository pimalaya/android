package org.pimalaya;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * Which calendars the phone shows: an account's, once its setup's switch was
 * on, including those listed after, less those its settings keep off; a
 * calendar turned on in an account the phone does not show brings it alone.
 */
@RunWith(RobolectricTestRunner.class)
public class PhoneCalendarsTest {
    private static final String JANE = "jane@example.com";
    private static final String JOHN = "john@example.org";

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void anAccountTurnedOnShowsTheCalendarsListedLater() {
        PhoneCalendars.setAccount(context, JANE, true);

        assertTrue(PhoneCalendars.shown(context, JANE, "work"));
        assertFalse(PhoneCalendars.shown(context, JOHN, "home"));
    }

    @Test
    public void aCalendarTurnedOffStaysOffItsAccountSetUpAgain() {
        PhoneCalendars.setAccount(context, JANE, true);
        PhoneCalendars.set(context, JANE, "work", false, List.of("work", "home"));

        PhoneCalendars.setAccount(context, JANE, true);

        assertFalse(PhoneCalendars.shown(context, JANE, "work"));
        assertTrue(PhoneCalendars.shown(context, JANE, "home"));
    }

    @Test
    public void aCalendarTurnedOnInAnAccountNotShownComesAlone() {
        PhoneCalendars.set(context, JANE, "work", true, List.of("work", "home"));

        assertTrue(PhoneCalendars.shown(context, JANE, "work"));
        assertFalse(PhoneCalendars.shown(context, JANE, "home"));
    }

    @Test
    public void anAccountTurnedOffShowsNone() {
        PhoneCalendars.setAccount(context, JANE, true);
        PhoneCalendars.setAccount(context, JANE, false);

        assertFalse(PhoneCalendars.shown(context, JANE, "work"));
    }

    @Test
    public void aForgottenAccountKeepsNothing() {
        PhoneCalendars.setAccount(context, JANE, true);
        PhoneCalendars.set(context, JANE, "work", false, List.of("work", "home"));

        PhoneCalendars.forget(context, JANE, List.of("work", "home"));
        assertFalse(PhoneCalendars.shown(context, JANE, "home"));

        PhoneCalendars.setAccount(context, JANE, true);
        assertTrue(PhoneCalendars.shown(context, JANE, "work"));
    }
}
