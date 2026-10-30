package org.pimalaya.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.pimalaya.R;

/**
 * The activity the shared widgets draw in: executors, theme and dialogs.
 *
 * <p>Also the chrome a list's scroll drives.
 */
public abstract class Host extends Activity {
    /** The single-thread io executor. */
    public final ExecutorService io = Executors.newSingleThreadExecutor();

    /** The main thread's handler. */
    public final Handler main = new Handler(Looper.getMainLooper());
    /** The theme and density helpers. */
    public final Ui ui = new Ui(this);

    /**
     * The executor of what someone waits on, such as a message being opened.
     *
     * <p>Never queued behind the background fill or body download on
     * {@link #io}. Its store writes take {@code Locks.store}, the store keeping
     * one writer.
     */
    public final ExecutorService interactive = Executors.newSingleThreadExecutor();

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

    /** Navigates forward: the panels slide in from the right. */
    public abstract void show(int panel);

    /** Navigates back: the panels slide in from the left. */
    public abstract void showBack(int panel);

    /** Swaps the screen in place, no slide. */
    public abstract void showInstant(int panel);

    /** Raises a whole-frame overlay over the current screen. */
    public abstract void openOverlay(int panel);

    /** Fades an overlay back out, then hides it. */
    public abstract void closeOverlay(int panel);

    /** Configures the persistent app bar and FAB for a screen. */
    public abstract void applyChrome(int panel);

    /** Dismisses the soft keyboard, e.g. when leaving the edit form. */
    public abstract void hideKeyboard();

    /** Raises the soft keyboard for the focused field. */
    public abstract void showKeyboard();

    /** Enables or disables an auth continue FAB, dimming its disc. */
    public abstract void setFabEnabled(int id, boolean enabled);

    /** Toggles an auth continue FAB between its arrow and an on-disc loader. */
    public abstract void setAuthLoading(int buttonId, int progressId, boolean loading);

    /** Gates a conflict form's save FAB: saves once nothing awaits review. */
    public abstract void gateSave(boolean ready, int icon, int description);

    /**
     * Titles the bar with a list domain's name once its title scrolled away.
     */
    public abstract void showDomainTitle(int panel);

    /** Retitles the bar, for a page whose own edits change its title. */
    public abstract void updateBarTitle(String title);

    /** A list's add button, inert while the list works (import, export). */
    public abstract void setListBusy(boolean busy);

    /** The composer's send button, inert while the message is written out. */
    public abstract void setSending(boolean sending);

    /** The mail item's unread count, hidden at zero. */
    public abstract void showMailBadge(int unread);

    /** Rebuilds the drawer's account cards. */
    public abstract void reloadHome();

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

    /** An error's message, or the {@code fallback} string when it has none. */
    public String message(Exception error, int fallback) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? getString(fallback) : message;
    }

    /** Shows a long toast. */
    public void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /**
     * Posts a UI continuation that only runs while the activity is alive.
     *
     * <p>Sync outcomes from the io executor land here; one arriving after a
     * rotation or finish would raise dialogs on a dead window
     * (BadTokenException).
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
     * Runs work on {@code on}, handing its outcome back on the main thread.
     *
     * <p>{@code done} gets the value, {@code failed} the failure, both dropped
     * once the activity is destroyed ({@link Background}).
     */
    public <T> void background(
            Executor on,
            Background.Work<T> work,
            Consumer<T> done,
            Consumer<Exception> failed) {
        Background.run(this, on, work, done, failed);
    }

    /**
     * Shows an error in a dismissible dialog rather than a toast.
     *
     * <p>The full message (often a long server body) stays and scrolls until
     * acknowledged.
     */
    public void showError(Exception error, int fallback) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.error_title)
                .setMessage(message(error, fallback))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Converts dp to pixels. */
    public int dp(int value) {
        return ui.dp(value);
    }
}
