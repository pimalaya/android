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
}
