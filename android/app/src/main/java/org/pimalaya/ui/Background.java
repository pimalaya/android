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
     * Runs work on the caller's executor; outcomes land through postAlive.
     *
     * <p>Done or failed run on the main thread, dropped once destroyed. This
     * owns no executor.
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
