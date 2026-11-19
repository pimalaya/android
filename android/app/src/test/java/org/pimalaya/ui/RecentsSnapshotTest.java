package org.pimalaya.ui;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.view.WindowManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Hiding the app in recent apps blanks captures only below Android 13. */
@RunWith(RobolectricTestRunner.class)
public class RecentsSnapshotTest {
    @Test
    @Config(sdk = 32)
    public void belowAndroid13HidingSecuresTheWindow() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        RecentsSnapshot.setHidden(activity, true);
        assertEquals("hidden", FLAG, secure(activity));

        RecentsSnapshot.setHidden(activity, false);
        assertEquals("shown again", 0, secure(activity));
    }

    @Test
    public void fromAndroid13HidingLeavesCapturesAlone() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        RecentsSnapshot.setHidden(activity, true);

        assertEquals("screenshots still work", 0, secure(activity));
    }

    private static final int FLAG = WindowManager.LayoutParams.FLAG_SECURE;

    private static int secure(Activity activity) {
        return activity.getWindow().getAttributes().flags & FLAG;
    }
}
