package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
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
 * An account's frontier: the one a step leaves (imports and wrong clocks
 * aside), the one first chunks leave, how it moves (back only, raised by a
 * narrowed bound, never older than the bound), and whether a step may bring
 * more.
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
    public void aStepMayBringMoreUntilAllMailOrTheBound() {
        assertFalse("no frontier yet: the first step is owed", MailWindow.more(context, ACCOUNT));
        MailWindow.set(context, ACCOUNT, "2026-09-15T00:00:00Z");
        assertTrue(MailWindow.more(context, ACCOUNT));
        MailScope.set(context, ACCOUNT, 1);
        MailWindow.set(context, ACCOUNT, MailScope.since(1));
        assertFalse("at the bound's floor", MailWindow.more(context, ACCOUNT));
        MailScope.set(context, ACCOUNT, 0);
        MailWindow.set(context, ACCOUNT, null);
        assertFalse("every message held", MailWindow.more(context, ACCOUNT));

        assertNull(MailWindow.reach(context, ACCOUNT));
        MailWindow.reach(context, ACCOUNT, 2_000L);
        MailWindow.reach(context, ACCOUNT, 3_000L);
        assertEquals(
                "the oldest reception kept",
                Long.valueOf(2_000L),
                MailWindow.reach(context, ACCOUNT));
        MailWindow.forget(context, ACCOUNT);
        assertNull(MailWindow.reach(context, ACCOUNT));
    }

    /**
     * Off Gmail, where mailboxes step one by one after the account's first
     * step, a mailbox with a frontier of its own reads that one alone, even
     * newer than its account's: one ticked later starts where its own first
     * chunk reached.
     */
    @Test
    public void offGmailAMailboxReadsItsOwnFrontier() {
        String sent = PimdirAccount.collectionId(ACCOUNT, "Sent");
        String inbox = PimdirAccount.collectionId(ACCOUNT, "INBOX");
        MailWindow.stepped(context, ACCOUNT, null, new MailStep.Outcome("2026-08-01T00:00:00Z", false, 50));
        MailWindow.moveBackMailbox(context, sent, "2026-10-01T00:00:00Z");

        assertEquals("2026-10-01T00:00:00Z", MailWindow.since(context, ACCOUNT, sent));
        assertEquals("2026-08-01T00:00:00Z", MailWindow.since(context, ACCOUNT, inbox));
        assertTrue(MailWindow.more(context, ACCOUNT, sent));

        MailWindow.stepped(context, ACCOUNT, null, new MailStep.Outcome(null, true, 3));
        assertTrue("its own, whatever its account's", MailWindow.more(context, ACCOUNT, sent));
        assertFalse(MailWindow.more(context, ACCOUNT, inbox));
    }

    @Test
    public void aStepOverOneMailboxMovesItsOwnFrontierAndNotTheAccounts() {
        // NOTE: a Gmail account, whose mailboxes read the older of the two.
        MailStore.markAllMail(context, ACCOUNT, true);
        String clients = PimdirAccount.collectionId(ACCOUNT, "Clients");
        String inbox = PimdirAccount.collectionId(ACCOUNT, "INBOX");
        MailWindow.stepped(context, ACCOUNT, null, new MailStep.Outcome("2026-10-01T00:00:00Z", false, 50));
        assertEquals("2026-10-01T00:00:00Z", MailWindow.since(context, ACCOUNT));

        MailWindow.stepped(
                context, ACCOUNT, clients, new MailStep.Outcome("2026-09-01T00:00:00Z", false, 50));
        assertEquals("the account's stays", "2026-10-01T00:00:00Z", MailWindow.since(context, ACCOUNT));
        assertEquals("2026-09-01T00:00:00Z", MailWindow.since(context, ACCOUNT, clients));
        assertEquals(
                "another mailbox reads its account's",
                "2026-10-01T00:00:00Z",
                MailWindow.since(context, ACCOUNT, inbox));
        assertTrue(MailWindow.more(context, ACCOUNT, clients));

        // NOTE: the older of the two: an account's step past it wins.
        MailWindow.stepped(context, ACCOUNT, null, new MailStep.Outcome("2026-08-01T00:00:00Z", false, 50));
        assertEquals("2026-08-01T00:00:00Z", MailWindow.since(context, ACCOUNT, clients));

        // A mailbox stepped alone to its end has no more, its account still has.
        MailWindow.stepped(context, ACCOUNT, clients, new MailStep.Outcome(null, true, 3));
        assertNull(MailWindow.since(context, ACCOUNT, clients));
        assertFalse(MailWindow.more(context, ACCOUNT, clients));
        assertTrue(MailWindow.more(context, ACCOUNT));
        assertTrue(MailWindow.more(context, ACCOUNT, inbox));

        // Moving the account's later drops a mailbox's own older one.
        MailWindow.set(context, ACCOUNT, "2026-09-15T00:00:00Z");
        MailWindow.dropMailboxes(context, ACCOUNT, "2026-09-15T00:00:00Z");
        assertEquals("2026-09-15T00:00:00Z", MailWindow.since(context, ACCOUNT, clients));

        MailWindow.stepped(
                context, ACCOUNT, clients, new MailStep.Outcome("2026-09-01T00:00:00Z", false, 50));
        MailWindow.forget(context, ACCOUNT);
        MailWindow.set(context, ACCOUNT, "2026-10-01T00:00:00Z");
        assertEquals(
                "forgotten with the account",
                "2026-10-01T00:00:00Z",
                MailWindow.since(context, ACCOUNT, clients));
    }

    @Test
    public void aStepsFrontierSkipsWhatWasDatedADayBeforeItsReception() {
        long received = java.time.Instant.parse("2026-10-09T12:00:00Z").toEpochMilli();
        assertEquals(
                "an import received with the step is held, not the frontier",
                "2026-10-08T20:00:00Z",
                MailWindow.frontier(
                        List.of(
                                new MailStep.Brought("2026-10-09T11:00:00Z", received),
                                new MailStep.Brought("2019-03-01T08:00:00Z", received),
                                new MailStep.Brought("2026-10-08T20:00:00Z", received),
                                new MailStep.Brought("", received)), false));
        assertEquals(
                "a reception unknown takes the date",
                "2019-03-01T08:00:00Z",
                MailWindow.frontier(
                        List.of(
                                new MailStep.Brought("2026-10-09T11:00:00Z", received),
                                new MailStep.Brought("2019-03-01T08:00:00Z", null)), false));
        assertEquals(
                "every one imported: the oldest of all, so the list moves",
                "2018-01-01T00:00:00Z",
                MailWindow.frontier(
                        List.of(
                                new MailStep.Brought("2019-03-01T08:00:00Z", received),
                                new MailStep.Brought("2018-01-01T00:00:00Z", received)), false));
        assertNull("nothing brought", MailWindow.frontier(List.of(), false));
    }

    @Test
    public void aStepByReceptionNeverReachesBelowItsOldestReception() {
        long late = java.time.Instant.parse("2026-10-09T15:00:00Z").toEpochMilli();
        long oldest = java.time.Instant.parse("2026-10-09T14:00:00.500Z").toEpochMilli();
        List<MailStep.Brought> brought =
                List.of(
                        new MailStep.Brought("2026-10-09T09:00:00Z", late),
                        new MailStep.Brought("2026-10-09T13:59:58Z", oldest));
        assertEquals(
                "what was received before 14:00 may be dated after 09:00",
                "2026-10-09T14:00:00Z",
                MailWindow.frontier(brought, true));
        assertEquals(
                "a step by date keeps the oldest timely date",
                "2026-10-09T09:00:00Z",
                MailWindow.frontier(brought, false));
        assertEquals(
                "a reception after every date changes nothing in order",
                "2026-10-09T09:00:00Z",
                MailWindow.frontier(
                        List.of(new MailStep.Brought("2026-10-09T09:00:00Z", late - 6 * 3_600_000L)),
                        true));
    }

    @Test
    public void aDateIsMidnightOnTheDeviceClock() {
        // NOTE: the bound is midnight on the device's clock too.
        assertEquals(
                MailWindow.startOf(LocalDate.of(2026, 7, 1), PARIS),
                MailScope.since(3, TODAY, PARIS));
        assertEquals(
                "2026-09-30T22:00:00Z", MailWindow.initial(true, null, List.of(), TODAY, PARIS));
        assertEquals(LocalDate.of(2026, 10, 1), MailWindow.dayOf("2026-09-30T22:00:00Z", PARIS));
    }

    @Test
    public void aJmapStepsAnchorsAreKeptPerViewAndForgottenWithTheAccount() {
        assertTrue(MailWindow.anchors(context, ACCOUNT, null).isEmpty());

        MailWindow.anchor(context, ACCOUNT, null, new JSONArray().put("e49").put("e48"));
        MailWindow.anchor(context, ACCOUNT, "Clients", new JSONArray().put("c9"));
        MailWindow.anchor(context, ACCOUNT, null, new JSONArray());

        assertEquals(List.of("e49", "e48"), MailWindow.anchors(context, ACCOUNT, null));
        assertEquals(List.of("c9"), MailWindow.anchors(context, ACCOUNT, "Clients"));
        assertEquals(
                "a view never stepped starts from the account's",
                List.of("e49", "e48"),
                MailWindow.anchors(context, ACCOUNT, "INBOX"));

        MailWindow.forget(context, ACCOUNT);
        assertTrue(MailWindow.anchors(context, ACCOUNT, null).isEmpty());
        assertTrue(MailWindow.anchors(context, ACCOUNT, "Clients").isEmpty());
    }
}
