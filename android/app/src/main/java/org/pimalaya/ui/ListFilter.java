package org.pimalaya.ui;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A list's filters as its {@link FilterPanel} edits them
 * (cairn/changes/one-filter-panel): a set of choices per section, the applied
 * ones kept across restarts, a draft while the panel is open.
 *
 * <p>The view alone: what syncs is each account's settings'.
 */
public final class ListFilter implements FilterPanel.Draft {
    /** One set of choices per section, by key; none on narrows nothing. */
    public static final class State {
        private final Map<String, Set<String>> sets = new LinkedHashMap<>();

        /** The choices on in {@code section}, to read or change. */
        public Set<String> set(String section) {
            return sets.computeIfAbsent(section, key -> new LinkedHashSet<>());
        }

        /** Whether any section narrows. */
        public boolean active() {
            for (Set<String> set : sets.values()) {
                if (!set.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        private void take(State other) {
            sets.clear();
            for (Map.Entry<String, Set<String>> entry : other.sets.entrySet()) {
                sets.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            }
        }
    }

    private static final String PREFERENCES = "list-filter";

    private final Context context;
    private final String list;
    private final String[] sections;
    private final State applied = new State();

    /** The panel's draft, null while it is folded. */
    private State draft;

    /** The filters of {@code list}, over {@code sections}, as last kept. */
    public ListFilter(Context context, String list, String... sections) {
        this.context = context;
        this.list = list;
        this.sections = sections;
        SharedPreferences kept = context.getSharedPreferences(PREFERENCES, 0);
        for (String section : sections) {
            applied.set(section).addAll(kept.getStringSet(list + "." + section, Set.of()));
        }
    }

    /** The filters the rows follow. */
    public State applied() {
        return applied;
    }

    /** The filters the panel shows: its draft while open, else the applied. */
    public State edited() {
        return draft != null ? draft : applied;
    }

    /** Keeps the applied filters across restarts. */
    public void save() {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFERENCES, 0).edit();
        for (String section : sections) {
            editor.putStringSet(list + "." + section, new LinkedHashSet<>(applied.set(section)));
        }
        editor.apply();
    }

    @Override
    public void open() {
        draft = new State();
        draft.take(applied);
    }

    @Override
    public void apply() {
        applied.take(draft);
        draft = null;
        save();
    }

    @Override
    public void cancel() {
        draft = null;
    }

    @Override
    public void reset() {
        for (Set<String> set : draft.sets.values()) {
            set.clear();
        }
    }
}
