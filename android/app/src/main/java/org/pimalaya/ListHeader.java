package org.pimalaya;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.ImageView;
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
 *
 * <p>It also draws the sync strip into the app bar while its list is the
 * screen, as the bar's extension (activity_main.xml): a line in the bar's
 * lower part and a thin bar on its bottom edge, so a pass, or the mail
 * downloading behind the list, shows however far the list is scrolled,
 * and never moves the title or the rows.
 */
final class ListHeader {
    private final MainActivity host;
    private final int panel;
    final View view;
    private final ListView list;

    /** Whether this list shows the sync strip; the files list does not. */
    private final boolean strip;

    /** Whether the large title is scrolled out of sight. */
    private boolean scrolled;

    /** Where the list last stood, to tell the scroll's direction. */
    private int lastFirst;

    private int lastTop;

    /** Debounced search, so typing does not re-render per keystroke. */
    private Runnable pendingSearch;

    ListHeader(MainActivity host, ListView list, int panel, boolean strip) {
        this.host = host;
        this.panel = panel;
        this.list = list;
        this.strip = strip;
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

    /** Whether the strip shows a pass, or why one waits. */
    private boolean passing;

    /**
     * What the strip says of the pass: whose account (null before the pass
     * reaches one), the step, and the bar's {@code passDone} of
     * {@code passTotal}, a total of zero running it indeterminate and one
     * below zero hiding it (a wait).
     */
    private String passAccount;

    private String passDetail;

    private int passDone;

    private int passTotal;

    /** Whether older mail downloads in the background. */
    private boolean fill;

    /** The bodies the background download landed of those it planned. */
    private int fillLanded;

    private int fillPlanned;

    /** The background bar in thousandths, which only grows while it runs. */
    private int fillShown;

    /**
     * Says older mail is downloading in the background, on the strip while
     * no pass holds it: a line beside a glyph pulsing gently, holding still
     * when the system removes animations.
     */
    void filling(boolean shown) {
        if (shown == fill) {
            return;
        }
        fill = shown;
        if (!shown) {
            fillLanded = 0;
            fillPlanned = 0;
            fillShown = 0;
        }
        renderStrip();
    }

    /**
     * How many bodies the background download landed of those it planned,
     * the strip's bar while it shows, none for a plan of none.
     */
    void filled(int landed, int planned) {
        fillLanded = landed;
        fillPlanned = planned;
        if (fill) {
            renderStrip();
        }
    }

    /**
     * Shows the sync strip: the step the pass stands at, after it the
     * account it is on (none before it reaches one), over a bar filled to
     * {@code done} of {@code total}, or running indeterminate while the
     * pass cannot count (a total of zero).
     */
    void sync(String account, String detail, int done, int total) {
        passing = true;
        passAccount = account;
        passDetail = detail;
        passDone = done;
        passTotal = Math.max(0, total);
        renderStrip();
    }

    /**
     * Shows the strip saying why the sync waits ({@code detail}), with no
     * bar, until a sync starts or {@link #synced} hides it.
     */
    void waiting(String detail) {
        passing = true;
        passAccount = null;
        passDetail = detail;
        passTotal = -1;
        renderStrip();
    }

    /**
     * Ends the pass's strip, which then says what downloads in the
     * background, if anything.
     */
    void synced() {
        passing = false;
        renderStrip();
    }

    /**
     * Draws this list's strip into the app bar, while this list is the
     * screen (the bar is shared): the pass, else the background download,
     * else nothing. A list without a strip leaves the bar bare.
     */
    void renderStrip() {
        if (host.screen != panel) {
            return;
        }
        if (!strip || !passing && !fill) {
            hideStrip(host);
            return;
        }
        TextView line = host.findViewById(R.id.bar_strip_line);
        ProgressBar bar = host.findViewById(R.id.bar_strip_bar);
        if (passing) {
            line.setText(
                    passAccount == null
                            ? passDetail
                            : host.getString(R.string.sync_line_account, passDetail, passAccount));
            glyph(host, false);
            bar.setVisibility(passTotal < 0 ? View.GONE : View.VISIBLE);
            bar.setIndeterminate(passTotal == 0);
            if (passTotal > 0) {
                bar.setProgress(
                        (int) ((long) Math.min(passDone, passTotal) * bar.getMax() / passTotal));
            }
        } else {
            line.setText(R.string.mail_filling);
            glyph(host, true);
            if (fillPlanned > 0) {
                fillShown =
                        Math.max(
                                fillShown,
                                (int) ((long) Math.min(fillLanded, fillPlanned) * bar.getMax()
                                        / fillPlanned));
                bar.setIndeterminate(false);
                bar.setProgress(fillShown);
                bar.setVisibility(View.VISIBLE);
            } else {
                bar.setVisibility(View.GONE);
            }
        }
        host.findViewById(R.id.bar_strip).setVisibility(View.VISIBLE);
    }

    /**
     * Takes the sync strip off the app bar, for a screen that has none:
     * its line, its bar and the glyph's pulse.
     */
    static void hideStrip(MainActivity host) {
        glyph(host, false);
        host.findViewById(R.id.bar_strip).setVisibility(View.GONE);
        host.findViewById(R.id.bar_strip_bar).setVisibility(View.GONE);
    }

    /**
     * Shows or hides the strip's download glyph, pulsing while shown. The
     * pulse rides on the glyph's tag, the glyph being the bar's and not
     * one list's.
     */
    private static void glyph(MainActivity host, boolean shown) {
        ImageView glyph = host.findViewById(R.id.bar_strip_glyph);
        if (shown == (glyph.getVisibility() == View.VISIBLE)) {
            return;
        }
        if (glyph.getTag() instanceof ObjectAnimator) {
            ((ObjectAnimator) glyph.getTag()).cancel();
            glyph.setTag(null);
        }
        glyph.setAlpha(1f);
        glyph.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (!shown) {
            return;
        }
        glyph.setTooltipText(glyph.getContentDescription());
        if (ValueAnimator.areAnimatorsEnabled()) {
            ObjectAnimator pulse = ObjectAnimator.ofFloat(glyph, View.ALPHA, 1f, 0.3f);
            pulse.setDuration(900);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.start();
            glyph.setTag(pulse);
        }
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
