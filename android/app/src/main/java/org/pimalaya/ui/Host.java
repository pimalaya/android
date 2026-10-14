package org.pimalaya.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.pimalaya.R;

/**
 * The activity the shared widgets draw in: its executors, theme helpers
 * and dialogs, and the chrome a list's scroll drives.
 */
public abstract class Host extends Activity {
    /** The single-thread io executor, the main-thread handler and the
     *  theme helper. */
    public final ExecutorService io = Executors.newSingleThreadExecutor();

    public final Handler main = new Handler(Looper.getMainLooper());
    public final Ui ui = new Ui(this);

    /** The screen currently shown: a flipper panel, or an overlay. */
    public int screen;

    /** Fades the bar's title in or out as a large title leaves or returns. */
    public abstract void showBarTitle(boolean shown);

    /** The large title heading one list. */
    public abstract ListHeader headerOf(int panel);

    /** Folds the extended FAB to its square glyph, or unfolds its label. */
    public abstract void foldFab(boolean folded);

    /** The name of a list domain's panel, a string resource. */
    public abstract int titleOf(int panel);

    /** Black or white, whichever reads on the accent colour. */
    public int accentContrast() {
        int accent = resolveColor(android.R.attr.colorAccent);
        return android.graphics.Color.luminance(accent) > 0.5f
                ? android.graphics.Color.BLACK
                : android.graphics.Color.WHITE;
    }

    /** Resolves a theme colour attribute to an ARGB int. */
    public int resolveColor(int attr) {
        return ui.resolveColor(attr);
    }

    public String message(Exception error, int fallback) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? getString(fallback) : message;
    }

    public void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /**
     * Posts a UI continuation that only runs while the activity is
     * alive: the io executor's sync outcomes land here, and one
     * arriving after a rotation or a finish would raise dialogs
     * against a dead window (BadTokenException).
     */
    public void postAlive(Runnable action) {
        main.post(
                () -> {
                    if (!isDestroyed()) {
                        action.run();
                    }
                });
    }

    /**
     * Shows an error in a dismissible dialog rather than a toast: the
     * full (often long, e.g. a server body) message stays on screen and
     * scrolls until acknowledged.
     */
    public void showError(Exception error, int fallback) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.error_title)
                .setMessage(message(error, fallback))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    public int dp(int value) {
        return ui.dp(value);
    }
}
