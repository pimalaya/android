package org.pimalaya.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The pool's own rules, apart from any engine.
 *
 * <p>A lone worker stays on the caller's thread, a joiner numbers a session for
 * a run, keys no worker took carry the error. Robolectric only for {@code
 * android.util.Log}.
 */
@RunWith(RobolectricTestRunner.class)
public class WorkerPoolTest {
    /** A session that holds nothing. */
    private static final class Session implements AutoCloseable {
        @Override
        public void close() {}
    }

    @Test
    public void aLoneWorkerRunsOnTheCallersThread() {
        Thread caller = Thread.currentThread();
        List<Thread> ran = Collections.synchronizedList(new ArrayList<>());
        try (WorkerPool<Session> pool = new WorkerPool<>(3, null, Session::new, null)) {
            WorkerPool.Outcome outcome =
                    pool.run("one key", List.of("a"), (session, key, remote) ->
                            ran.add(Thread.currentThread()));
            assertTrue(outcome.failures.isEmpty());
            assertEquals(1, outcome.sessions);
        }
        try (WorkerPool<Session> pool = new WorkerPool<>(1, null, Session::new, null)) {
            pool.run("one worker", List.of("b", "c"), (session, key, remote) ->
                    ran.add(Thread.currentThread()));
        }
        assertEquals(List.of(caller, caller, caller), ran);
    }

    @Test
    public void theJoinerSeesTheRunNumberThenZero() {
        Session mine = new Session();
        List<Long> joined = new ArrayList<>();
        List<Long> during = new ArrayList<>();
        try (WorkerPool<Session> pool =
                new WorkerPool<>(
                        1,
                        mine,
                        Session::new,
                        (session, run) -> {
                            assertSame(mine, session);
                            joined.add(run);
                        })) {
            pool.run("joined", List.of("a", "b"), (session, key, remote) ->
                    during.add(joined.get(joined.size() - 1)));
        }
        assertEquals(2, joined.size());
        assertTrue("a run is numbered from 1: " + joined, joined.get(0) > 0);
        assertEquals("left on 0 after the run", Long.valueOf(0), joined.get(1));
        assertEquals("every key ran inside the run", List.of(joined.get(0), joined.get(0)), during);
    }

    @Test
    public void anUnopenedSlotsErrorLandsOnTheKeysNoWorkerTook() {
        IllegalStateException refused = new IllegalStateException("could not connect");
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        WorkerPool.Outcome outcome;
        try (WorkerPool<Session> pool =
                new WorkerPool<>(
                        2,
                        null,
                        () -> {
                            throw refused;
                        },
                        null)) {
            outcome = pool.run("unopened", List.of("a", "b", "c"), (session, key, remote) ->
                    ran.add(key));
        }
        assertTrue("no worker took a key", ran.isEmpty());
        assertEquals(3, outcome.failures.size());
        List<String> keys = new ArrayList<>();
        for (WorkerPool.Failure failure : outcome.failures) {
            keys.add(failure.key);
            assertSame(refused, failure.error);
        }
        assertEquals("in the order given", List.of("a", "b", "c"), keys);
        assertSame(refused, outcome.failure());
    }

    @Test
    public void anInterruptedRunClosesNoSessionBeforeItsWorkersLetGo() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        boolean[] interruptKept = new boolean[1];
        Thread caller =
                new Thread(
                        () -> {
                            try (WorkerPool<AutoCloseable> pool =
                                    new WorkerPool<>(
                                            2, null, () -> () -> events.add("closed"), null)) {
                                pool.run("rotated", List.of("a", "b"), (session, key, remote) -> {
                                    if (key.equals("a")) {
                                        started.countDown();
                                        release.await(5, TimeUnit.SECONDS);
                                        events.add("a done");
                                    }
                                });
                                interruptKept[0] = Thread.currentThread().isInterrupted();
                            }
                        });
        caller.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(300);
        assertTrue("the run waits for its worker", caller.isAlive());
        assertFalse("no session closed under a running key", events.contains("closed"));

        release.countDown();
        caller.join(5_000);
        assertEquals(List.of("a done", "closed", "closed"), events);
        assertTrue("the interrupt is the caller's again", interruptKept[0]);
    }

    @Test
    public void anInterruptedRunTakesNoFurtherKey() throws Exception {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        WorkerPool.Outcome[] outcome = new WorkerPool.Outcome[1];
        Thread caller =
                new Thread(
                        () -> {
                            try (WorkerPool<Session> pool =
                                    new WorkerPool<>(2, null, Session::new, null)) {
                                outcome[0] =
                                        pool.run("rotated", List.of("a", "b", "c", "d"),
                                                (session, key, remote) -> {
                                                    started.countDown();
                                                    release.await(5, TimeUnit.SECONDS);
                                                    ran.add(key);
                                                });
                            }
                        });
        caller.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(300);
        release.countDown();
        caller.join(5_000);

        Collections.sort(ran);
        assertEquals(List.of("a", "b"), ran);
        List<String> failed = new ArrayList<>();
        for (WorkerPool.Failure failure : outcome[0].failures) {
            failed.add(failure.key);
            assertTrue(failure.error instanceof InterruptedException);
        }
        assertEquals(List.of("c", "d"), failed);
    }
}
