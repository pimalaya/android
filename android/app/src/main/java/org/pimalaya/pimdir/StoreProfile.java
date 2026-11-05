package org.pimalaya.pimdir;

import org.pimalaya.BuildConfig;
import org.pimalaya.client.PimdirSql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a write batch spends its time, statement by statement: debug builds
 * only, for the slow-write log (io-pimdir's
 * cairn/changes/a-page-lands-in-few-statements).
 *
 * <p>Per thread, between {@link #begin} and {@link #end}: the store's writer
 * profiles its batch, and a reader on another thread adds nothing. A read
 * counts from its cursor's open to its close, the rows it fills included.
 */
public final class StoreProfile {
    /** The lines a profile reads out, its costliest statements. */
    private static final int SHOWN = 12;

    private static final ThreadLocal<StoreProfile> running = new ThreadLocal<>();

    /** Nanoseconds and runs, by statement text. */
    private final Map<String, long[]> spent = new HashMap<>();

    private final long began = System.nanoTime();
    private long ended;

    private StoreProfile() {}

    /** Starts profiling this thread's statements; nothing in a release build. */
    public static void begin() {
        if (BuildConfig.DEBUG) {
            running.set(new StoreProfile());
        }
    }

    /** Stops this thread's profile and answers it, null when none ran. */
    public static StoreProfile end() {
        StoreProfile profile = running.get();
        running.remove();
        if (profile != null) {
            profile.ended = System.nanoTime();
        }
        return profile;
    }

    /** Whether this thread profiles, so a read wraps its cursor only then. */
    static boolean on() {
        return BuildConfig.DEBUG && running.get() != null;
    }

    /** Adds one run of a statement, by its text, to this thread's profile. */
    static void add(String sql, long nanos) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        StoreProfile profile = running.get();
        if (profile == null) {
            return;
        }
        long[] entry = profile.spent.computeIfAbsent(sql, key -> new long[2]);
        entry[0] += nanos;
        entry[1]++;
    }

    /** The statements' time by canonical name, costliest first. */
    public List<Map.Entry<String, long[]>> byName() {
        Map<String, long[]> named = new HashMap<>();
        for (Map.Entry<String, long[]> entry : spent.entrySet()) {
            String name = PimdirSql.nameOf(entry.getKey());
            if (name == null) {
                String text = entry.getKey().replaceAll("\\s+", " ").trim();
                name = text.length() > 40 ? text.substring(0, 40) + "…" : text;
            }
            long[] sum = named.computeIfAbsent(name, key -> new long[2]);
            sum[0] += entry.getValue()[0];
            sum[1] += entry.getValue()[1];
        }
        List<Map.Entry<String, long[]>> sorted = new ArrayList<>(named.entrySet());
        sorted.sort((left, right) -> Long.compare(right.getValue()[0], left.getValue()[0]));
        return sorted;
    }

    /** The time inside statements, in nanoseconds. */
    public long inStatements() {
        long total = 0;
        for (long[] entry : spent.values()) {
            total += entry[0];
        }
        return total;
    }

    @Override
    public String toString() {
        long total = (ended == 0 ? System.nanoTime() : ended) - began;
        long inside = inStatements();
        StringBuilder line = new StringBuilder();
        line.append("statements ").append(inside / 1_000_000).append(" ms of ")
                .append(total / 1_000_000).append(" ms:");
        List<Map.Entry<String, long[]>> sorted = byName();
        for (int index = 0; index < Math.min(SHOWN, sorted.size()); index++) {
            Map.Entry<String, long[]> entry = sorted.get(index);
            line.append(' ').append(entry.getKey()).append(' ')
                    .append(entry.getValue()[1]).append("x ")
                    .append(entry.getValue()[0] / 1_000_000).append(" ms;");
        }
        return line.toString();
    }
}
