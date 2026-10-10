package org.pimalaya;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
    public void onlyWhatArrivedAfterTheNewestNotifiedNotifies() {
        java.util.Map<String, MailStore.StoredMessage> before = new java.util.LinkedHashMap<>();
        before.put("kept", unread("kept", "2026-10-10T07:00:00Z"));
        java.util.Map<String, MailStore.StoredMessage> now = new java.util.LinkedHashMap<>();
        now.put("new", unread("new", "2026-10-10T09:00:00Z"));
        now.put("kept", before.get("kept"));
        now.put("filled", unread("filled", "2025-03-01T08:00:00Z"));

        java.util.List<MailStore.StoredMessage> arrived =
                BackgroundJob.arrived(before, now, "2026-10-10T08:00:00Z");
        assertEquals(1, arrived.size());
        assertEquals("new", arrived.get(0).id);
        assertEquals(
                "with nothing notified yet, everything new",
                2,
                BackgroundJob.arrived(before, now, null).size());

        String runAt = "2026-10-10T10:00:00Z";
        assertEquals(
                "the newest notified",
                "2026-10-10T09:00:00Z",
                BackgroundJob.watermark("2026-10-10T08:00:00Z", arrived, runAt));
        assertEquals(
                "a spam dated 2099 is kept no later than the run",
                runAt,
                BackgroundJob.watermark(
                        "2026-10-10T08:00:00Z",
                        java.util.List.of(unread("spam", "2099-01-01T00:00:00Z")),
                        runAt));
        assertNull(BackgroundJob.watermark(null, java.util.List.of(), runAt));

        BackgroundCheck.setNotifiedUpTo(context, JANE, "2026-10-10T09:00:00Z");
        assertEquals("2026-10-10T09:00:00Z", BackgroundCheck.notifiedUpTo(context, JANE));
        BackgroundCheck.setNotifies(context, JANE, true);
        assertNull("turned on, it is seeded afresh", BackgroundCheck.notifiedUpTo(context, JANE));
        BackgroundCheck.setNotifiedUpTo(context, JANE, "2026-10-10T09:00:00Z");
        BackgroundCheck.forget(context, JANE);
        assertNull(BackgroundCheck.notifiedUpTo(context, JANE));
    }
}
