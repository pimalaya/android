package org.pimalaya.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.pimalaya.R;

/**
 * A grouped list's layout: rows split into titled sections, each one card.
 *
 * <p>It flattens rows into adapter positions, a header opening each section,
 * and says which corners each row rounds (a ListView has no container to
 * round). Sections open where the label changes, so the caller sorts.
 */
public final class CardSections<T> {
    /** A header's label at its position, null at a row's. */
    private final List<String> headers = new ArrayList<>();

    /** A row at its position, null at a header's. */
    private final List<T> rows = new ArrayList<>();

    /** The background each position draws with. */
    private final List<Integer> shapes = new ArrayList<>();

    /** The title of the group a header opens, null where it opens none. */
    private final List<CharSequence> groups = new ArrayList<>();

    /** Lays the rows out under their sections. */
    public void fill(List<T> items, Function<T, String> sectionOf) {
        fill(items, sectionOf, item -> null, key -> null);
    }

    /**
     * Lays the rows out under their sections, the sections under groups.
     *
     * <p>A section opens a group, titled {@code titleOf} its key, where its
     * first row's {@code groupOf} differs from the section's before it; a
     * null key is the group already on screen, so it opens none.
     */
    public void fill(
            List<T> items,
            Function<T, String> sectionOf,
            Function<T, String> groupOf,
            Function<String, CharSequence> titleOf) {
        headers.clear();
        rows.clear();
        shapes.clear();
        groups.clear();

        String current = null;
        String group = null;
        int opened = 0;
        for (int index = 0; index < items.size(); index++) {
            T item = items.get(index);
            String section = sectionOf.apply(item);
            boolean first = index == 0 || !section.equals(current);
            if (first) {
                current = section;
                String key = groupOf.apply(item);
                boolean opens = key != null && !key.equals(group);
                group = key;
                headers.add(section);
                rows.add(null);
                shapes.add(0);
                groups.add(opens ? titleOf.apply(key) : null);
                opened = rows.size();
            }

            boolean last =
                    index == items.size() - 1
                            || !sectionOf.apply(items.get(index + 1)).equals(section);
            headers.add(null);
            rows.add(item);
            shapes.add(shapeOf(rows.size() - 1 == opened, last));
            groups.add(null);
        }
    }

    private static int shapeOf(boolean first, boolean last) {
        if (first && last) {
            return R.drawable.row_card_single;
        }
        if (first) {
            return R.drawable.row_card_top;
        }
        return last ? R.drawable.row_card_bottom : R.drawable.row_card_middle;
    }

    /** The number of positions, headers included. */
    public int size() {
        return rows.size();
    }

    /** Whether a position is a section's header. */
    public boolean isHeader(int position) {
        return headers.get(position) != null;
    }

    /** Whether a position is a header opening a group. */
    public boolean opensGroup(int position) {
        return groups.get(position) != null;
    }

    /** The header label at a position, null for a row. */
    public String header(int position) {
        return headers.get(position);
    }

    /** The row at a position, null at a header. */
    public T row(int position) {
        return position >= 0 && position < rows.size() ? rows.get(position) : null;
    }

    /** Where a section opens, or -1 when no row falls in it. */
    int positionOf(String section) {
        return headers.indexOf(section);
    }

    /**
     * A header's view, recycled when it can be; one opening a group carries
     * the group's title over its label.
     */
    public View headerView(int position, View recycled, ViewGroup parent) {
        CharSequence group = groups.get(position);
        View view = recycled;
        if (view == null) {
            view =
                    LayoutInflater.from(parent.getContext())
                            .inflate(
                                    group != null
                                            ? R.layout.item_section_group
                                            : R.layout.item_section,
                                    parent,
                                    false);
        }
        if (group != null) {
            ((TextView) view.findViewById(R.id.group_title)).setText(group);
        }
        ((TextView) view.findViewById(R.id.section_label)).setText(headers.get(position));
        return view;
    }

    /** Whether a row is the first of its card, which no hairline tops. */
    public boolean opensCard(int position) {
        int shape = shapes.get(position);
        return shape == R.drawable.row_card_top || shape == R.drawable.row_card_single;
    }

    /** Rounds a row's card for its place in the section. */
    public void shape(View row, int position) {
        row.findViewById(R.id.row_card).setBackgroundResource(shapes.get(position));
    }
}
