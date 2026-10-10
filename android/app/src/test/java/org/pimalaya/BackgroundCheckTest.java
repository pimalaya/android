package org.pimalaya;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * New mail notifies only once turned on, which turns background sync on
 * when it was off since the background run is what notifies; the permission
 * is asked from Android 13 on, while it is not held.
 */
@RunWith(RobolectricTestRunner.class)
public class BackgroundCheckTest {
    private static final String JANE = "jane@example.com";

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void anAccountDoesNotNotifyUntilTurnedOn() {
        assertFalse(BackgroundCheck.notifies(context, JANE));

        BackgroundCheck.setNotifies(context, JANE, true);
        assertTrue(BackgroundCheck.notifies(context, JANE));

        BackgroundCheck.setNotifies(context, JANE, false);
        assertFalse(BackgroundCheck.notifies(context, JANE));
    }

    @Test
    public void notifyingTurnsBackgroundSyncOnAtTheDefault() {
        BackgroundCheck.setInterval(context, JANE, 0);

        BackgroundCheck.setNotifies(context, JANE, true);

        assertEquals(15, BackgroundCheck.interval(context, JANE));
    }

    @Test
    public void notifyingKeepsAChosenInterval() {
        BackgroundCheck.setInterval(context, JANE, 60);

        BackgroundCheck.setNotifies(context, JANE, true);

        assertEquals(60, BackgroundCheck.interval(context, JANE));
    }

    @Test
    public void notifyingOffLeavesBackgroundSyncAlone() {
        BackgroundCheck.setInterval(context, JANE, 0);

        BackgroundCheck.setNotifies(context, JANE, false);

        assertEquals(0, BackgroundCheck.interval(context, JANE));
    }

    @Test
    public void aForgottenAccountNotifiesNoMore() {
        BackgroundCheck.setNotifies(context, JANE, true);

        BackgroundCheck.forget(context, JANE);

        assertFalse(BackgroundCheck.notifies(context, JANE));
    }

    @Test
    public void beforeAndroid13NothingIsAsked() {
        assertEquals(0, BackgroundCheck.missing(32, permission -> false).length);
    }

    @Test
    public void fromAndroid13ThePermissionIsAskedWhileNotHeld() {
        assertArrayEquals(
                new String[] {Manifest.permission.POST_NOTIFICATIONS},
                BackgroundCheck.missing(33, permission -> false));
        assertEquals(0, BackgroundCheck.missing(33, permission -> true).length);
    }

    /** An unread inbox message dated {@code sortKey}, as a run reads it. */
    private static MailStore.StoredMessage unread(String id, String sortKey) {
        return new MailStore.StoredMessage(
                JANE, "inbox", "INBOX", "Inbox", id, "Subject", "", "a@example.org", sortKey, 1,
                "[]", 0, null, false, false, false);
    }

    @Test
    public void onlyWhatThePassAddedAboveTheInboxFloorNotifies() {
        java.util.Map<String, MailStore.StoredMessage> before = new java.util.LinkedHashMap<>();
        before.put("kept", unread("kept", "2026-10-10T07:00:00Z"));
        java.util.Map<String, MailStore.StoredMessage> now = new java.util.LinkedHashMap<>();
        now.put("new", unread("new", "2026-10-10T10:05:00Z"));
        now.put("delayed", unread("delayed", "2026-10-10T10:00:00Z"));
        now.put("kept", before.get("kept"));
        now.put("band", unread("band", "2025-03-01T08:00:00Z"));

        java.util.List<MailStore.StoredMessage> arrived =
                BackgroundJob.arrived(
                        before, now, java.util.Map.of("inbox", "2026-09-01T00:00:00Z"));
        assertEquals("new mail, delayed or not, and nothing of a band below the floor", 2,
                arrived.size());
        assertEquals("new", arrived.get(0).id);
        assertEquals("delayed", arrived.get(1).id);
        assertEquals(
                "an inbox with no floor holds no band",
                3,
                BackgroundJob.arrived(before, now, java.util.Map.of()).size());
    }
}
