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
 * The sync dock sits on top of the bottom navigation: showing it, with its
 * lines and its bar, moves neither the app bar's title nor the content's
 * top, the content ending above it.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncDockLayoutTest {
    @Test
    public void showingTheDockMovesNothing() {
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
        View nav = root.findViewById(R.id.bottom_nav);
        View dock = root.findViewById(R.id.sync_dock);

        lay(root);
        int barHeight = appBar.getHeight();
        int titleTop = top(title, root);
        int contentTop = top(content, root);
        int contentBottom = contentTop + content.getHeight();
        int navTop = top(nav, root);
        assertTrue(barHeight > 0);

        ((TextView) root.findViewById(R.id.sync_dock_account)).setText("me@example.org");
        TextView line = root.findViewById(R.id.sync_dock_line);
        line.setText("Syncing mail · Downloading 42 messages");
        line.setVisibility(View.VISIBLE);
        TextView count = root.findViewById(R.id.sync_dock_count);
        count.setText("1 of 2");
        count.setVisibility(View.VISIBLE);
        ((ProgressBar) root.findViewById(R.id.sync_bar)).setIndeterminate(true);
        dock.setVisibility(View.VISIBLE);
        lay(root);
        assertEquals(barHeight, appBar.getHeight());
        assertEquals(titleTop, top(title, root));
        assertEquals(contentTop, top(content, root));
        assertEquals(navTop, top(nav, root));
        assertTrue(dock.getHeight() > 0);
        assertEquals(top(dock, root) + dock.getHeight(), navTop);
        assertEquals(top(content, root) + content.getHeight(), top(dock, root));
        View bar = root.findViewById(R.id.sync_bar);
        assertEquals(top(dock, root), top(bar, root));

        dock.setVisibility(View.GONE);
        lay(root);
        assertEquals(titleTop, top(title, root));
        assertEquals(contentTop, top(content, root));
        assertEquals(contentBottom, top(content, root) + content.getHeight());
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
