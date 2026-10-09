package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * A write to a book or a calendar reaches the phone a second later, in one
 * pass per collection however many writes the second saw, and a write
 * landing while the pass waits for its thread joins it rather than queueing
 * another.
 */
@RunWith(RobolectricTestRunner.class)
public class PhoneQueueTest {
    private final List<String> passes = new ArrayList<>();
    private final List<Runnable> waiting = new ArrayList<>();
    private PhoneQueue queue;

    @Before
    public void setUp() {
        // NOTE: the runner holds its tasks, so a test says when the pass
        // thread gets to them.
        Executor runner = waiting::add;
        queue =
                new PhoneQueue(
                        new Handler(Looper.getMainLooper()),
                        runner,
                        passes::add,
                        collection -> passes.add("calendar " + collection));
    }

    private void idle(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private void runWaiting() {
        List<Runnable> tasks = new ArrayList<>(waiting);
        waiting.clear();
        for (Runnable task : tasks) {
            task.run();
        }
    }

    @Test
    public void aBurstIsOnePassPerBookASecondLater() {
        queue.queue("book-a");
        queue.queue("book-b");
        queue.queue("book-a");

        idle(PhoneQueue.DELAY - 1);
        assertTrue("nothing before the second is up", waiting.isEmpty());

        idle(1);
        runWaiting();
        assertEquals(List.of("book-a", "book-b"), passes);
    }

    @Test
    public void aWriteWhileThePassWaitsJoinsIt() {
        queue.queue("book-a");
        idle(PhoneQueue.DELAY);
        assertEquals(1, waiting.size());

        // NOTE: the pass thread is busy (an import running): the write
        // lands in the round it is about to drain.
        queue.queue("book-b");
        idle(PhoneQueue.DELAY);
        assertEquals("no second round queued", 1, waiting.size());

        runWaiting();
        assertEquals(List.of("book-a", "book-b"), passes);
    }

    @Test
    public void aWriteAfterThePassStartsTheNextRound() {
        queue.queue("book-a");
        idle(PhoneQueue.DELAY);
        runWaiting();

        queue.queue("book-a");
        idle(PhoneQueue.DELAY);
        runWaiting();
        assertEquals(List.of("book-a", "book-a"), passes);
    }

    @Test
    public void booksAndCalendarsWrittenTogetherShareOneRound() {
        queue.queueCalendar("acct/Work");
        queue.queue("book-a");
        queue.queueCalendar("acct/Work");

        idle(PhoneQueue.DELAY);
        assertEquals("one timer for both", 1, waiting.size());
        runWaiting();
        assertEquals(List.of("book-a", "calendar acct/Work"), passes);
    }
}
