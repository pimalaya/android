package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.service.notification.NotificationListenerService;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Flags;
import org.pimalaya.mail.MailStore;
import org.pimalaya.sync.BackgroundCheck;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** A locked phone shows no sender or subject until the setting says so. */
@RunWith(RobolectricTestRunner.class)
public class LockedNotificationTest {
    private static final String JANE = "jane@example.com";

    @Test
    public void aLockedPhoneShowsOnlyTheCountUntilTurnedOn() {
        Application context = RuntimeEnvironment.getApplication();
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        NotificationManager manager = context.getSystemService(NotificationManager.class);

        BackgroundJob.notify(context, JANE, List.of(unread("1")));

        assertEquals(
                "the channel redacts whatever the phone's setting",
                Notification.VISIBILITY_PRIVATE,
                manager.getNotificationChannel("hidden-mail-" + JANE).getLockscreenVisibility());
        Notification locked =
                shadowOf(manager).getNotification(("inbox/1").hashCode()).publicVersion;
        assertEquals("1 new message", locked.extras.getString(Notification.EXTRA_TITLE));
        assertNull(locked.extras.getCharSequence(Notification.EXTRA_TEXT));

        BackgroundCheck.setDetailsLocked(context, true);
        BackgroundJob.notify(context, JANE, List.of(unread("2")));

        assertNull(manager.getNotificationChannel("hidden-mail-" + JANE));
        assertEquals(
                "the phone's own setting decides",
                NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE,
                manager.getNotificationChannel("mail-" + JANE).getLockscreenVisibility());
    }

    private static MailStore.StoredMessage unread(String id) {
        return MailStore.StoredMessage.synced(
                new MailStore.StoredMessage.MessageHeader(
                        JANE, "inbox", "INBOX", id, "Subject", "Ann", "ann@example.org"),
                new MailStore.StoredMessage.Listing(
                        "Inbox", "2026-11-07T08:00:00Z", 1, Flags.none(), 0, null, false));
    }
}
