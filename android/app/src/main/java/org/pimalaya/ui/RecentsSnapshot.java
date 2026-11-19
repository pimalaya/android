package org.pimalaya.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.WindowManager;

/**
 * Whether the app switcher shows the app's last screen; shown until hidden in
 * the settings.
 *
 * <p>From Android 13 the snapshot alone is blanked. Below, only
 * {@code FLAG_SECURE} blanks it, which also blanks screenshots and screen
 * recordings of the app.
 */
public final class RecentsSnapshot {
    private static final String KEY = "hidden";

    private RecentsSnapshot() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("recents", Context.MODE_PRIVATE);
    }

    /** Whether the snapshot is hidden; off by default. */
    public static boolean hidden(Context context) {
        return prefs(context).getBoolean(KEY, false);
    }

    /** Hides the snapshot or shows it again, on {@code activity} at once. */
    public static void setHidden(Activity activity, boolean on) {
        prefs(activity).edit().putBoolean(KEY, on).apply();
        apply(activity);
    }

    /** Whether hiding also blanks screenshots and screen recordings. */
    public static boolean blanksCaptures() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU;
    }

    /** Brings {@code activity}'s window in line with the setting. */
    public static void apply(Activity activity) {
        boolean on = hidden(activity);
        if (!blanksCaptures()) {
            activity.setRecentsScreenshotEnabled(!on);
        } else if (on) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }
}
