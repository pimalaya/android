package org.pimalaya.pimdir;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The deletions phone passes held back from the server, by phone collection:
 * more than half of at least 20 members deleted on the phone at once.
 *
 * <p>In memory for the process, as {@link ParkedRounds}: the rows stay deleted
 * on the phone, so every pass finds them again, and a release or a restore
 * holds for one pass of its collection.
 */
public final class HeldDeletions {
    /** The fewest members a collection holds before its deletions are held. */
    static final int MASS = 20;

    /**
     * One collection's held deletions.
     *
     * @param collection the phone engine collection
     * @param handles the members deleted on the phone
     * @param members the members the store holds there
     */
    public record Held(String collection, List<String> handles, int members) {}

    private final Map<String, Held> held = new LinkedHashMap<>();

    /** The held deletions the user was asked about, as they were asked. */
    private final Map<String, Held> offered = new HashMap<>();

    /** The collections whose next pass lets their deletions go. */
    private final Set<String> released = new HashSet<>();

    /** The collections whose next pass puts their deleted members back. */
    private final Set<String> restored = new HashSet<>();

    /** Whether {@code deleted} of {@code members} is too many to let go unasked. */
    static boolean massive(int deleted, int members) {
        return members >= MASS && deleted * 2 > members;
    }

    /**
     * Records what one pass of {@code collection} held, null for nothing; the
     * pass spent the release it was given.
     */
    synchronized void passed(String collection, Held round) {
        released.remove(collection);
        if (round == null) {
            held.remove(collection);
            offered.remove(collection);
        } else {
            held.put(collection, round);
        }
    }

    /** Whether the next pass of {@code collection} lets its deletions go. */
    synchronized boolean releases(String collection) {
        return released.contains(collection);
    }

    /** Lets the deletions of {@code collections} go on their next pass. */
    public synchronized void release(Collection<String> collections) {
        released.addAll(collections);
    }

    /** Puts the deleted members of {@code collections} back on their next pass. */
    public synchronized void restore(Collection<String> collections) {
        restored.addAll(collections);
    }

    /** The held deletions of {@code collection} to put back now, or null. */
    synchronized Held restoring(String collection) {
        if (!restored.remove(collection)) {
            return null;
        }
        Held round = held.remove(collection);
        offered.remove(collection);
        return round;
    }

    /**
     * The held deletions the user was not asked about yet, or asked about with
     * other counts, marked asked.
     */
    public synchronized List<Held> unoffered() {
        List<Held> fresh = new ArrayList<>();
        for (Held round : held.values()) {
            Held asked = offered.get(round.collection());
            if (asked == null
                    || asked.handles().size() != round.handles().size()
                    || asked.members() != round.members()) {
                fresh.add(round);
                offered.put(round.collection(), round);
            }
        }
        return fresh;
    }
}
