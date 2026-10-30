package org.pimalaya.task;

import static org.junit.Assert.assertFalse;

import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.R;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * A Tasks row takes the list's tap, its tick aside.
 *
 * <p>A list hands a tap to a row only when nothing in it takes focus
 * (AbsListView), and an ImageButton makes itself focusable whatever its
 * layout says, so the row must block its descendants.
 */
@RunWith(RobolectricTestRunner.class)
public class TaskRowTest {
    @Test
    public void aRowLetsTheListTakeItsTap() {
        View row =
                LayoutInflater.from(
                                new ContextThemeWrapper(
                                        RuntimeEnvironment.getApplication(), R.style.AppTheme))
                        .inflate(
                                R.layout.item_task,
                                new FrameLayout(RuntimeEnvironment.getApplication()),
                                false);

        assertFalse(row.hasFocusable());
    }
}
