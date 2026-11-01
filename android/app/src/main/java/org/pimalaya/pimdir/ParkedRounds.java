package org.pimalaya.pimdir;

import org.pimalaya.client.SyncReport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rounds a sync parked rather than closed (pimdir SYNC §5, §8), by engine
 * collection: a listing naming no member while the store binds some, or one
 * finding more than half of at least 20 bound members absent.
 *
 * <p>In memory for the process: the engine decides a parked round again on
 * every run, and an acceptance holds for one run of its collection, never
 * stored ({@code accept_absences}).
 */
public final class ParkedRounds {
    /**
     * One parked round.
     *
     * @param collection the engine collection it parked
     * @param absent the bound members its listing left out
     * @param bound the bound members it counted
     */
    public record Parked(String collection, int absent, int bound) {}

    private final Map<String, Parked> parked = new LinkedHashMap<>();

    /** The parked rounds the user was asked about, as they were asked. */
    private final Map<String, Parked> offered = new HashMap<>();

    /** The collections whose next run accepts the absences it finds. */
    private final Set<String> accepted = new HashSet<>();

    /**
     * Records what one sync of {@code collection} did: a parked round kept for
     * the user, any other forgetting it. Either way the run spent the
     * acceptance it was given.
     */
    public synchronized void synced(String collection, SyncReport report) {
        accepted.remove(collection);
        if (report.parked()) {
            parked.put(collection, new Parked(collection, report.absent(), report.bound()));
        } else {
            parked.remove(collection);
            offered.remove(collection);
        }
    }

    /** Whether the next run of {@code collection} accepts its absences. */
    public synchronized boolean accepts(String collection) {
        return accepted.contains(collection);
    }

    /** Accepts the absences of {@code collections} for their next run. */
    public synchronized void accept(Collection<String> collections) {
        accepted.addAll(collections);
    }

    /**
     * The parked rounds the user was not asked about yet, or asked about with
     * other counts, marked asked: a round parked again as it was is not asked
     * about twice in a process.
     */
    public synchronized List<Parked> unoffered() {
        List<Parked> fresh = new ArrayList<>();
        for (Parked round : parked.values()) {
            if (!round.equals(offered.get(round.collection()))) {
                fresh.add(round);
                offered.put(round.collection(), round);
            }
        }
        return fresh;
    }
}
