package org.pimalaya;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * A list's large title: the domain's name over a supporting line,
 * scrolling with the rows, with whatever the domain filters with under
 * it (list_header.xml).
 *
 * <p>It owns its list's scroll, which drives two things in the shared
 * chrome. The bar's own title stays blank while this one is on screen
 * and fades in once it scrolls away, so the name is never shown twice
 * and never lost. The extended add button folds to its glyph while the
 * list scrolls down and unfolds when it comes back up, the way a
 * reader's attention moves.
 */
final class ListHeader {
    private final MainActivity host;
    private final int panel;
    final View view;
    private final ListView list;

    /** Whether the large title is scrolled out of sight. */
    private boolean scrolled;

    /** Where the list last stood, to tell the scroll's direction. */
    private int lastFirst;

    private int lastTop;

    /** Debounced search, so typing does not re-render per keystroke. */
    private Runnable pendingSearch;

    ListHeader(MainActivity host, ListView list, int panel) {
        this.host = host;
        this.panel = panel;
        this.list = list;
        view = LayoutInflater.from(host).inflate(R.layout.list_header, list, false);
        list.addHeaderView(view, null, false);
        ((TextView) view.findViewById(R.id.header_title)).setText(Domains.titleOf(panel));

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

    /**
     * Keeps a list's empty state centred in what this header leaves below
     * it, rather than in the whole screen where the header would cover it.
     */
    void empty(View empty) {
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
    int rowAt(int position) {
        return position - list.getHeaderViewsCount();
    }

    void title(String title) {
        ((TextView) view.findViewById(R.id.header_title)).setText(title);
    }

    void meta(String meta) {
        ((TextView) view.findViewById(R.id.header_meta)).setText(meta);
    }

    /**
     * Shows the sync strip in place of the meta line: the account the pass
     * is on (none before it reaches one), the step it stands at, over a bar filled to {@code done} of {@code total}, or running
     * indeterminate while the pass cannot count (a total of zero).
     */
    void sync(String account, String detail, int done, int total) {
        TextView whose = view.findViewById(R.id.header_sync_account);
        whose.setText(account);
        whose.setVisibility(account == null ? View.GONE : View.VISIBLE);
        view.findViewById(R.id.header_meta).setVisibility(View.GONE);
        view.findViewById(R.id.header_sync).setVisibility(View.VISIBLE);
        ((TextView) view.findViewById(R.id.header_sync_detail)).setText(detail);
        ProgressBar bar = view.findViewById(R.id.header_sync_bar);
        bar.setIndeterminate(total <= 0);
        if (total > 0) {
            bar.setProgress(done * bar.getMax() / total);
        }
    }

    /** Puts the meta line back once the sync is over. */
    void synced() {
        view.findViewById(R.id.header_sync).setVisibility(View.GONE);
        view.findViewById(R.id.header_meta).setVisibility(View.VISIBLE);
    }

    /** Whether the bar should carry the title, the large one being gone. */
    boolean scrolled() {
        return scrolled;
    }

    /**
     * Shows the search field, handing each settled query (trimmed, lower
     * cased) to {@code onQuery}.
     */
    void search(int hint, Consumer<String> onQuery) {
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
                        String query = s.toString().trim().toLowerCase();
                        if (pendingSearch != null) {
                            host.main.removeCallbacks(pendingSearch);
                        }
                        pendingSearch = () -> onQuery.accept(query);
                        host.main.postDelayed(pendingSearch, 250);
                    }
                });
    }

    /** Empties the search field (its watcher settles the query). */
    void clearSearch() {
        ((EditText) view.findViewById(R.id.header_search_input)).setText("");
    }

    /** The chip row, shown, for the caller to fill. */
    LinearLayout chips() {
        view.findViewById(R.id.header_chips_scroll).setVisibility(View.VISIBLE);
        return view.findViewById(R.id.header_chips);
    }

    /**
     * The week's card, shown under its month line and its number, for the
     * caller to fill; the arrows and the number are the caller's to wire.
     */
    LinearLayout week(CharSequence month, CharSequence number) {
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
