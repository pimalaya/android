package org.pimalaya;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A grouped list's layout: rows split into titled sections, each drawn
 * as one rounded card.
 *
 * <p>The three domains share it, so a day of mail, a letter of contacts
 * and a day of the agenda are the same object. It flattens the rows into
 * the positions an adapter serves, a header opening each section, and
 * knows which corners each row rounds: a card is its rows' backgrounds
 * put end to end, the first rounding its top and the last its bottom,
 * since a ListView has no container to round around them.
 *
 * <p>Rows keep the order they are handed in, and a section opens wherever
 * the label changes, so the caller sorts.
 */
final class CardSections<T> {
    /** A header's label at its position, null at a row's. */
    private final List<String> headers = new ArrayList<>();

    /** A row at its position, null at a header's. */
    private final List<T> rows = new ArrayList<>();

    /** The background each position draws with. */
    private final List<Integer> shapes = new ArrayList<>();

    /** Lays the rows out under their sections. */
    void fill(List<T> items, Function<T, String> sectionOf) {
        headers.clear();
        rows.clear();
        shapes.clear();

        String current = null;
        int opened = 0;
        for (int index = 0; index < items.size(); index++) {
            T item = items.get(index);
            String section = sectionOf.apply(item);
            boolean first = index == 0 || !section.equals(current);
            if (first) {
                current = section;
                headers.add(section);
                rows.add(null);
                shapes.add(0);
                opened = rows.size();
            }

            boolean last =
                    index == items.size() - 1
                            || !sectionOf.apply(items.get(index + 1)).equals(section);
            headers.add(null);
            rows.add(item);
            shapes.add(shapeOf(rows.size() - 1 == opened, last));
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

    int size() {
        return rows.size();
    }

    boolean isHeader(int position) {
        return headers.get(position) != null;
    }

    String header(int position) {
        return headers.get(position);
    }

    /** The row at a position, null at a header. */
    T row(int position) {
        return position >= 0 && position < rows.size() ? rows.get(position) : null;
    }

    /** Where a section opens, or -1 when no row falls in it. */
    int positionOf(String section) {
        return headers.indexOf(section);
    }

    /** A header's view, recycled when it can be. */
    View headerView(int position, View recycled, ViewGroup parent) {
        View view = recycled;
        if (view == null) {
            view =
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_section, parent, false);
        }
        ((TextView) view).setText(headers.get(position));
        return view;
    }

    /** Rounds a row's card for its place in the section. */
    void shape(View row, int position) {
        row.findViewById(R.id.row_card).setBackgroundResource(shapes.get(position));
    }
}
