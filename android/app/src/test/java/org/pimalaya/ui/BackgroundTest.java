package org.pimalaya.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

/** Background work: run on its executor, outcome handed back while alive. */
@RunWith(RobolectricTestRunner.class)
public class BackgroundTest {
    private ActivityController<StubHost> controller;
    private Host host;

    /** What the executor was handed and has not run yet. */
    private final List<Runnable> queued = new ArrayList<>();

    private final Executor on = queued::add;

    /** What reached done and failed, and on which looper. */
    private final List<Object> done = new ArrayList<>();

    private final List<Exception> failed = new ArrayList<>();
    private Looper doneOn;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(StubHost.class).create();
        host = controller.get();
    }

    @Test
    public void doneReceivesTheValueOnTheMainLooperAfterTheExecutorRan() {
        host.background(on, () -> "value", this::done, failed::add);
        assertTrue(done.isEmpty());

        runQueued();
        assertTrue(done.isEmpty());

        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of("value"), done);
        assertSame(Looper.getMainLooper(), doneOn);
        assertTrue(failed.isEmpty());
    }

    @Test
    public void failedReceivesTheThrownExceptionAndDoneDoesNotRun() {
        IllegalStateException thrown = new IllegalStateException("refused");
        host.background(
                on,
                () -> {
                    throw thrown;
                },
                this::done,
                failed::add);

        runQueued();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(thrown), failed);
        assertTrue(done.isEmpty());
        assertNull(doneOn);
    }

    @Test
    public void nothingRunsOnceTheActivityIsDestroyedBeforeThePostDrains() {
        host.background(on, () -> "value", this::done, failed::add);
        host.background(
                on,
                () -> {
                    throw new IllegalStateException("refused");
                },
                this::done,
                failed::add);

        runQueued();
        controller.destroy();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(done.isEmpty());
        assertTrue(failed.isEmpty());
    }

    private void done(Object value) {
        done.add(value);
        doneOn = Looper.myLooper();
    }

    private void runQueued() {
        List<Runnable> work = new ArrayList<>(queued);
        queued.clear();
        for (Runnable runnable : work) {
            runnable.run();
        }
    }
}
