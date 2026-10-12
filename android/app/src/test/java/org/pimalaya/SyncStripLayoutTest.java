package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The sync strip is the app bar's extension: showing it, with its line, its
 * glyph and its bar, moves neither the bar's title nor the content below.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncStripLayoutTest {
    @Test
    public void showingTheStripMovesNothing() {
        View activity =
                LayoutInflater.from(
                                new ContextThemeWrapper(
                                        RuntimeEnvironment.getApplication(), R.style.AppTheme))
                        .inflate(R.layout.activity_main, null);
        // NOTE: the main column alone, the drawer around it laying out only
        // once attached to a window.
        View root = (View) activity.findViewById(R.id.flipper).getParent();
        ((TextView) root.findViewById(R.id.bar_title)).setText("Mail");
        View appBar = root.findViewById(R.id.app_bar);
        View title = root.findViewById(R.id.bar_title);
        View content = root.findViewById(R.id.flipper);

        lay(root);
        int barHeight = appBar.getHeight();
        int titleTop = top(title, root);
        int contentTop = top(content, root);
        assertTrue(barHeight > 0);

        ((TextView) root.findViewById(R.id.bar_strip_line)).setText("Syncing mail · me@example.org");
        root.findViewById(R.id.bar_strip_glyph).setVisibility(View.VISIBLE);
        root.findViewById(R.id.bar_strip).setVisibility(View.VISIBLE);
        ProgressBar bar = root.findViewById(R.id.bar_strip_bar);
        bar.setIndeterminate(true);
        bar.setVisibility(View.VISIBLE);
        lay(root);
        assertEquals(barHeight, appBar.getHeight());
        assertEquals(titleTop, top(title, root));
        assertEquals(contentTop, top(content, root));
        View line = root.findViewById(R.id.bar_strip);
        assertTrue(line.getHeight() > 0 && bar.getHeight() > 0);
        assertTrue(top(line, root) + line.getHeight() <= top(appBar, root) + barHeight);
        assertEquals(top(appBar, root) + barHeight, top(bar, root) + bar.getHeight());

        root.findViewById(R.id.bar_strip).setVisibility(View.GONE);
        bar.setVisibility(View.GONE);
        lay(root);
        assertEquals(titleTop, top(title, root));
        assertEquals(contentTop, top(content, root));
    }

    private static void lay(View root) {
        root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2160, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2160);
    }

    /** A view's top within {@code root}. */
    private static int top(View view, View root) {
        int top = 0;
        for (View at = view; at != root; at = (View) at.getParent()) {
            top += at.getTop();
        }
        return top;
    }
}
