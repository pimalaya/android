package org.pimalaya.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.function.Consumer;
import org.pimalaya.R;

/**
 * A list's large title: the domain's name over a line, scrolling with rows.
 *
 * <p>Filters sit under it (list_header.xml). It owns the list's scroll: the
 * bar's title fades in once this one scrolls away, and the extended add button
 * folds while scrolling down.
 */
public final class ListHeader {
    private final Host host;
    private final int panel;
    /** The header view, the list's first row. */
    public final View view;
    final ListView list;

    /** Whether the large title is scrolled out of sight. */
    private boolean scrolled;

    /** Where the list last stood, to tell the scroll's direction. */
    private int lastFirst;

    private int lastTop;

    /** Debounced search, so typing does not re-render per keystroke. */
    private Runnable pendingSearch;

    /** The header of {@code list}, the list shown on {@code panel}. */
    public ListHeader(Host host, ListView list, int panel) {
        this.host = host;
        this.panel = panel;
        this.list = list;
        view = LayoutInflater.from(host).inflate(R.layout.list_header, list, false);
        list.addHeaderView(view, null, false);
        ((TextView) view.findViewById(R.id.header_title)).setText(host.titleOf(panel));

        list.setOnScrollListener(
                new AbsListView.OnScrollListener() {
                    @Override
                    public void onScrollStateChanged(AbsListView v, int state) {}

                    @Override
                    public void onScroll(AbsListView v, int first, int count, int total) {
                        onScrolled(first);
                    }
                });
    }

    /** Centres a list's empty state in what this header leaves below it. */
    public void empty(View empty) {
        view.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    int below = Math.max(0, bottom);
                    if (empty.getPaddingTop() != below) {
                        // NOTE: posted, a layout pass not being the place
                        // to request another.
                        empty.post(
                                () ->
                                        empty.setPadding(
                                                empty.getPaddingLeft(),
                                                below,
                                                empty.getPaddingRight(),
                                                empty.getPaddingBottom()));
                    }
                });
    }

    /** The row a click landed on, net of this header. */
    public int rowAt(int position) {
        return position - list.getHeaderViewsCount();
    }

    void title(String title) {
        ((TextView) view.findViewById(R.id.header_title)).setText(title);
    }

    /** Sets the supporting line under the title, gone when empty. */
    public void meta(String meta) {
        TextView line = view.findViewById(R.id.header_meta);
        line.setText(meta);
        line.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * Whether the list stands scrolled to its end, which the dock would cover.
     *
     * <p>A list not scrolled is not.
     */
    boolean atEnd() {
        return list.canScrollVertically(-1) && !list.canScrollVertically(1);
    }

    /** Scrolls the list on by {@code by} pixels. */
    void scroll(int by) {
        list.scrollListBy(by);
    }

    /** Whether the bar should carry the title, the large one being gone. */
    public boolean scrolled() {
        return scrolled;
    }

    /** Shows the search field, each settled query trimmed and lower-cased. */
    public void search(int hint, Consumer<String> onQuery) {
        view.findViewById(R.id.header_search).setVisibility(View.VISIBLE);
        EditText input = view.findViewById(R.id.header_search_input);
        View clear = view.findViewById(R.id.header_search_clear);
        input.setHint(hint);
        clear.setOnClickListener(v -> input.setText(""));
        input.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                        String query = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                        if (pendingSearch != null) {
                            host.main.removeCallbacks(pendingSearch);
                        }
                        pendingSearch = () -> onQuery.accept(query);
                        host.main.postDelayed(pendingSearch, 250);
                    }
                });
    }

    /** Empties the search field (its watcher settles the query). */
    public void clearSearch() {
        ((EditText) view.findViewById(R.id.header_search_input)).setText("");
    }

    /** The chip row, shown, for the caller to fill. */
    public LinearLayout chips() {
        view.findViewById(R.id.header_chips_scroll).setVisibility(View.VISIBLE);
        return view.findViewById(R.id.header_chips);
    }

    /**
     * The week's card under its month and number, for the caller to fill.
     *
     * <p>The arrows and the number are the caller's to wire.
     */
    public LinearLayout week(CharSequence month, CharSequence number) {
        view.findViewById(R.id.header_week).setVisibility(View.VISIBLE);
        ((TextView) view.findViewById(R.id.header_month)).setText(month);
        ((TextView) view.findViewById(R.id.header_week_number)).setText(number);
        return view.findViewById(R.id.header_days);
    }

    private void onScrolled(int first) {
        View top = list.getChildAt(0);
        int offset = top == null ? 0 : top.getTop();

        // NOTE: gone once the title's baseline has passed under the bar,
        // which is when the bar's copy stops repeating it.
        View title = view.findViewById(R.id.header_title);
        boolean gone = first > 0 || view.getTop() + title.getBottom() <= 0;
        if (gone != scrolled) {
            scrolled = gone;
            if (host.screen == panel) {
                host.showBarTitle(gone);
            }
        }

        // NOTE: a few pixels of slack, so a finger resting on the list
        // does not flap the label.
        boolean down = first > lastFirst || (first == lastFirst && offset < lastTop - 8);
        boolean up = first < lastFirst || (first == lastFirst && offset > lastTop + 8);
        boolean atTop = first == 0 && offset >= 0;
        if (host.screen == panel && (down || up || atTop)) {
            host.foldFab(down && !atTop);
        }
        if (down || up) {
            lastFirst = first;
            lastTop = offset;
        }
    }
}
