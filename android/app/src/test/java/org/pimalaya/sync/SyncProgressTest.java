package org.pimalaya.sync;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.R;
import org.pimalaya.account.PimDomain;
import org.pimalaya.pimdir.PimdirEngine;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.StubHost;
import org.pimalaya.ui.SyncDock;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

/**
 * The sync dock's model of a pass: its bar, counts and sheet rows.
 *
 * <p>Over the planned sections, as drawn on the dock.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncProgressTest {
    private static final String A = "a@example.org";
    private static final String B = "b@example.org";
    private static final String C = "c@example.org";

    private Host host;
    private SyncDock dock;
    private SyncProgress progress;

    @Before
    public void setUp() {
        host = Robolectric.buildActivity(DockHost.class).get();
        host.setTheme(R.style.AppTheme);
        host.setContentView(R.layout.activity_main);
        dock = new SyncDock(host);
        progress = new SyncProgress(host, null, () -> null, () -> dock, () -> true, updated -> {});
        progress.resetSyncPlan();
    }

    @Test
    public void theBarNeverMovesBackAcrossSections() throws Exception {
        progress.planSteps(List.of(A));
        progress.account(A);
        progress.collections(PimDomain.MAIL, 1, 1);
        assertEquals(1000, shown().done);

        // NOTE: a section the plan missed grows the whole, which would put
        // the bar back to half.
        progress.account(B);
        progress.collections(PimDomain.MAIL, 0, 2);
        progress.collections(PimDomain.MAIL, 1, 2);
        assertEquals(1000, shown().done);
    }

    @Test
    public void aCountOvertakenByAWorkerIsDropped() throws Exception {
        progress.account(A);
        progress.collections(PimDomain.MAIL, 3, 5);
        String three = host.getString(SyncSteps.collectionsOf(PimDomain.MAIL), 3, 5);
        assertEquals(three, shown().line);
        assertEquals(600, shown().done);

        progress.collections(PimDomain.MAIL, 2, 5);
        assertEquals(three, shown().line);
        // NOTE: half a step into the mailbox after the third: from two
        // landed the bar would stay at 600.
        progress.advance(PimDomain.MAIL, PimdirEngine.Progress.STAGE_SERVER, 1, 2);
        assertEquals(700, shown().done);
    }

    @Test
    public void rowsListTheReachedSectionsThenTheRestInPlanOrder() throws Exception {
        progress.planSteps(List.of(A, B, C));
        progress.account(A);
        progress.collections(PimDomain.MAIL, 0, 1);
        progress.account(C);
        progress.collections(PimDomain.MAIL, 0, 1);

        SyncDock.Pass pass = shown();
        assertEquals(3, pass.rows.size());
        assertRow(pass.rows.get(0), A, SyncDock.Row.DONE, host.getString(R.string.sync_dock_done));
        assertRow(pass.rows.get(1), C, SyncDock.Row.RUNNING, pass.line);
        assertRow(pass.rows.get(2), B, SyncDock.Row.NEXT, host.getString(R.string.sync_dock_next));
        assertEquals(host.getString(R.string.sync_dock_count, 2, 3), pass.count);
    }

    @Test
    public void aMissedSectionWeighsOneAndGrowsTheWhole() throws Exception {
        progress.planSteps(List.of(A));
        progress.account(C);
        progress.collections(PimDomain.MAIL, 1, 2);
        assertEquals(250, shown().done);

        progress.account(A);
        progress.collections(PimDomain.MAIL, 0, 1);
        assertEquals(500, shown().done);
        assertEquals(host.getString(R.string.sync_dock_count, 2, 2), shown().count);
    }

    private static void assertRow(SyncDock.Row row, String account, int state, String line) {
        assertEquals(account, row.account);
        assertEquals(state, row.state);
        assertEquals(line, row.line);
    }

    /** The pass the dock was last handed, once the main thread drew it. */
    private SyncDock.Pass shown() throws Exception {
        shadowOf(Looper.getMainLooper()).idle();
        // NOTE: the dock keeps the pass it draws to itself.
        Field pass = SyncDock.class.getDeclaredField("pass");
        pass.setAccessible(true);
        return (SyncDock.Pass) pass.get(dock);
    }

    /** An activity holding the dock's views. */
    public static class DockHost extends StubHost {}
}
