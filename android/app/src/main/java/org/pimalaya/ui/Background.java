package org.pimalaya.ui;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Work run off the main thread, its outcome handed back on it. */
public final class Background {
    private Background() {}

    /** The work itself: answers its value, or throws. */
    @FunctionalInterface
    public interface Work<T> {
        T run() throws Exception;
    }

    /**
     * Runs work on the executor; done or failed run on the main thread
     * through postAlive, dropped once destroyed. The executor is the
     * caller's: this owns none.
     */
    static <T> void run(
            Host host,
            Executor on,
            Work<T> work,
            Consumer<T> done,
            Consumer<Exception> failed) {
        on.execute(
                () -> {
                    T value;
                    try {
                        value = work.run();
                    } catch (Exception error) {
                        host.postAlive(() -> failed.accept(error));
                        return;
                    }
                    host.postAlive(() -> done.accept(value));
                });
    }
}
