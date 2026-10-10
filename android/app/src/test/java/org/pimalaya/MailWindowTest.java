package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

/**
 * An account's download window: the value a first sync leaves, how it
 * moves (back only, raised by a narrowed bound, never older than the
 * bound), and the date the list's footer offers next.
 */
@RunWith(RobolectricTestRunner.class)
public class MailWindowTest {
    private static final String ACCOUNT = "account-1";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void aFirstSyncLeavesTheInboxFloorElseTheMostRecentOne() {
        List<String> floors = Arrays.asList("2026-09-20T08:00:00Z", null, "2026-10-02T08:00:00Z");
        assertEquals(
                "the inbox's first chunk",
                "2026-10-15T08:00:00Z",
                MailWindow.initial(false, "2026-10-15T08:00:00Z", floors, TODAY, UTC));
        assertEquals(
                "account-wide, or no inbox: the most recent floor",
                "2026-10-02T08:00:00Z",
                MailWindow.initial(false, null, floors, TODAY, UTC));
        assertNull(
                "every mailbox fitted in its first chunk: all mail",
                MailWindow.initial(false, null, Arrays.asList(null, null), TODAY, UTC));
        assertEquals(
                "no mail at all: the first of the month",
                "2026-10-01T00:00:00Z",
                MailWindow.initial(true, null, List.of(), TODAY, UTC));
    }

    @Test
    public void aWindowMovesBackOnlyAndNeverBelowTheBound() {
        assertFalse(MailWindow.held(context, ACCOUNT));
        MailWindow.set(context, ACCOUNT, "2026-09-15T00:00:00Z");
        assertTrue(MailWindow.held(context, ACCOUNT));

        MailWindow.moveBack(context, ACCOUNT, "2026-10-01T00:00:00Z");
        assertEquals("never forward", "2026-09-15T00:00:00Z", MailWindow.since(context, ACCOUNT));
        MailWindow.moveBack(context, ACCOUNT, "2026-06-01T00:00:00Z");
        assertEquals("2026-06-01T00:00:00Z", MailWindow.since(context, ACCOUNT));

        // NOTE: read under the bound, never older than its floor, and raised
        // to it by a narrowing.
        MailScope.set(context, ACCOUNT, 1);
        String floor = MailScope.since(1);
        assertEquals(floor, MailWindow.since(context, ACCOUNT));
        MailWindow.raise(context, ACCOUNT, floor);
        MailScope.set(context, ACCOUNT, 0);
        assertEquals(floor, MailWindow.since(context, ACCOUNT));

        MailWindow.moveBack(context, ACCOUNT, null);
        assertNull("all mail", MailWindow.since(context, ACCOUNT));
        MailWindow.moveBack(context, ACCOUNT, "2026-06-01T00:00:00Z");
        assertNull("all mail is as far back as it goes", MailWindow.since(context, ACCOUNT));

        MailWindow.forget(context, ACCOUNT);
        assertFalse(MailWindow.held(context, ACCOUNT));
    }

    @Test
    public void aDatePickedBelowTheBoundWidensItToTheNarrowestCoveringIt() {
        assertEquals(1, MailScope.covering("2026-09-15T00:00:00Z", TODAY, UTC));
        assertEquals(12, MailScope.covering("2026-03-03T00:00:00Z", TODAY, UTC));
        assertEquals(0, MailScope.covering("2020-01-01T00:00:00Z", TODAY, UTC));
        assertEquals("all my mail", 0, MailScope.covering(null, TODAY, UTC));
        // NOTE: the bound is midnight on the device's clock too, so a window
        // on its first day in Paris needs no wider one.
        String julyInParis = MailWindow.startOf(LocalDate.of(2026, 7, 1), PARIS);
        assertEquals(julyInParis, MailScope.since(3, TODAY, PARIS));
        assertEquals(3, MailScope.covering(julyInParis, TODAY, PARIS));
    }

    @Test
    public void theFooterOffersTheFirstOfTheNextMonthHoldingMail() {
        assertEquals(
                "an empty September is skipped",
                "2026-08-01T00:00:00Z",
                MailWindow.next("2026-08-20T10:00:00Z", "2026-10-01T00:00:00Z", false, UTC));
        assertEquals(
                "nothing stored below, headers still to list",
                "2026-10-01T00:00:00Z",
                MailWindow.next(null, "2026-10-15T08:00:00Z", true, UTC));
        assertEquals(
                "2026-09-01T00:00:00Z", MailWindow.next(null, "2026-10-01T00:00:00Z", true, UTC));
        assertNull(
                "nothing below and nothing to list",
                MailWindow.next(null, "2026-10-01T00:00:00Z", false, UTC));
        assertNull(MailWindow.next(null, null, true, UTC));
    }

    @Test
    public void aDateIsMidnightOnTheDeviceClock() {
        // NOTE: "Load since 1 October" in Paris starts at its midnight, two
        // hours before UTC's, so a message of 1 October 00:30 there is in.
        assertEquals(
                "2026-09-30T22:00:00Z",
                MailWindow.next("2026-10-05T10:00:00Z", "2026-10-15T00:00:00Z", false, PARIS));
        assertEquals(
                "a message of 1 October 00:30 in Paris is in October",
                "2026-09-30T22:00:00Z",
                MailWindow.next("2026-09-30T22:30:00Z", "2026-10-15T00:00:00Z", false, PARIS));
        assertEquals(
                "2026-08-31T22:00:00Z",
                MailWindow.next(null, "2026-09-30T22:00:00Z", true, PARIS));
        assertEquals(
                "2026-09-30T22:00:00Z", MailWindow.initial(true, null, List.of(), TODAY, PARIS));
        assertEquals(LocalDate.of(2026, 10, 1), MailWindow.dayOf("2026-09-30T22:00:00Z", PARIS));
    }
}
