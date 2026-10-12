package org.pimalaya;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The sync dock, on top of the bottom navigation (activity_main.xml): what
 * a pass is on, or the mail downloading in the background, whichever list
 * is shown and however far it is scrolled, the way a music app docks its
 * player.
 *
 * <p>It shows on the lists only, while something runs: one bar along its
 * top edge, the account over a line naming the domain and the engine's
 * step, the count of the pass's accounts. The content ends above it, so
 * the large title and the rows never move; the extended add button rises
 * by its height while it shows. A tap opens the pass's sheet: its
 * progress, a row per account and the pass's last lines, kept in memory
 * for the pass under way only.
 */
final class SyncDock {
    /** One account of the pass, as the sheet lists it. */
    static final class Row {
        static final int RUNNING = 0;
        static final int NEXT = 1;
        static final int DONE = 2;

        String account;

        /** What it is on while running; the domain or nothing otherwise. */
        String line;

        int state;
    }

    /** What the dock says of a pass, or of why one waits. */
    static final class Pass {
        /** The first line: the account, or what runs before one is reached. */
        String title;

        /** The second line, the domain then the step; null for none. */
        String line;

        /**
         * The bar at {@code done} of {@code total}, a total of zero running
         * it indeterminate and one below zero hiding it.
         */
        int done;

        int total;

        /** How far the pass is through its accounts (*1 of 2*); null for one. */
        String count;

        /** Whether the pass downloads older mail, the glyph then standing for the spinner. */
        boolean download;

        List<Row> rows = List.of();
    }

    /** The lines the sheet keeps of a pass. */
    static final int LOG = 50;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final MainActivity host;

    private final View dock;

    /** The pass shown, null when none runs. */
    private Pass pass;

    /** Whether a list is the screen, the only screens the dock shows on. */
    private boolean onList;

    /** Whether older mail downloads in the background. */
    private boolean fill;

    /** The bodies the background download landed of those it planned. */
    private int fillLanded;

    private int fillPlanned;

    /** The background bar in thousandths, which only grows while it runs. */
    private int fillShown;

    /** The pass's last lines, newest first, each after its time. */
    private final ArrayDeque<String[]> log = new ArrayDeque<>();

    /** Bumped as a line is kept, so the sheet redraws its log only then. */
    private int logVersion;

    private int logShown = -1;

    /** The open sheet, null when closed. */
    private Dialog sheet;

    /** The download glyph's pulse while the background download shows. */
    private ObjectAnimator pulse;

    SyncDock(MainActivity host) {
        this.host = host;
        dock = host.findViewById(R.id.sync_dock);
        dock.setOnClickListener(view -> open());
    }

    /** Says whether a list is the screen, where the dock shows (main thread). */
    void onList(boolean shown) {
        onList = shown;
        render();
    }

    /** A pass starts: the last one's lines go. */
    void started() {
        log.clear();
        logVersion++;
    }

    /** Shows what a pass is on. */
    void pass(Pass pass) {
        this.pass = pass;
        render();
    }

    /**
     * Ends the pass, the dock then saying what downloads in the background,
     * if anything; the sheet closes.
     */
    void synced() {
        pass = null;
        if (sheet != null) {
            sheet.dismiss();
        }
        render();
    }

    /**
     * Keeps a line of the pass for the sheet, at its time, the last
     * {@link #LOG} only; a line repeating the last one is dropped.
     */
    void log(String line) {
        if (!log.isEmpty() && log.peekFirst()[1].equals(line)) {
            return;
        }
        log.addFirst(new String[] {LocalTime.now().format(TIME), line});
        while (log.size() > LOG) {
            log.removeLast();
        }
        logVersion++;
        if (sheet != null) {
            renderSheet();
        }
    }

    /** Says older mail is downloading in the background, while no pass runs. */
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
        render();
    }

    /**
     * How many bodies the background download landed of those it planned,
     * the dock's bar while it shows, none for a plan of none.
     */
    void filled(int landed, int planned) {
        fillLanded = landed;
        fillPlanned = planned;
        if (fill) {
            render();
        }
    }

    /** What the dock shows now: the pass, else the background download, else null. */
    private Pass shown() {
        if (pass != null) {
            return pass;
        }
        if (!fill) {
            return null;
        }
        Pass background = new Pass();
        background.title = host.getString(R.string.mail_filling);
        background.download = true;
        background.total = -1;
        if (fillPlanned > 0) {
            fillShown =
                    Math.max(
                            fillShown,
                            (int) ((long) Math.min(fillLanded, fillPlanned) * 1000 / fillPlanned));
            background.done = fillShown;
            background.total = 1000;
        }
        return background;
    }

    private void render() {
        Pass shown = onList ? shown() : null;
        boolean visible = shown != null;
        boolean was = dock.getVisibility() == View.VISIBLE;
        if (!visible) {
            pulse(false);
            if (sheet != null) {
                sheet.dismiss();
            }
            if (was) {
                dock.setVisibility(View.GONE);
                rise(false);
            }
            return;
        }

        ((TextView) dock.findViewById(R.id.sync_dock_account)).setText(shown.title);
        TextView line = dock.findViewById(R.id.sync_dock_line);
        line.setText(shown.line);
        line.setVisibility(shown.line == null ? View.GONE : View.VISIBLE);
        TextView count = dock.findViewById(R.id.sync_dock_count);
        count.setText(shown.count);
        count.setVisibility(shown.count == null ? View.GONE : View.VISIBLE);
        dock.findViewById(R.id.sync_dock_spinner)
                .setVisibility(shown.download ? View.GONE : View.VISIBLE);
        dock.findViewById(R.id.sync_dock_glyph)
                .setVisibility(shown.download ? View.VISIBLE : View.GONE);
        pulse(pass == null);
        dock.setContentDescription(
                host.getString(
                        shown.download
                                ? R.string.sync_dock_fill_details
                                : R.string.sync_dock_details));
        bar(dock.findViewById(R.id.sync_bar), shown);

        if (!was) {
            keepEnd();
            dock.setVisibility(View.VISIBLE);
            rise(true);
        }
        if (sheet != null) {
            renderSheet();
        }
    }

    /** Draws a pass's bar: hidden, indeterminate or filled. */
    private static void bar(ProgressBar bar, Pass shown) {
        bar.setVisibility(shown.total < 0 ? View.INVISIBLE : View.VISIBLE);
        bar.setIndeterminate(shown.total == 0);
        if (shown.total > 0) {
            bar.setProgress(
                    (int) ((long) Math.min(shown.done, shown.total) * bar.getMax() / shown.total));
        }
    }

    /**
     * Keeps the end of a list scrolled to it in sight as the dock takes its
     * bottom: *Load more* stays just above the dock. A list at its top, or
     * not scrolled, does not move.
     */
    private void keepEnd() {
        ListHeader header = host.headerOf(host.screen);
        if (!header.atEnd()) {
            return;
        }
        View list = header.list;
        list.addOnLayoutChangeListener(
                new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(
                            View v,
                            int left,
                            int top,
                            int right,
                            int bottom,
                            int oldLeft,
                            int oldTop,
                            int oldRight,
                            int oldBottom) {
                        list.removeOnLayoutChangeListener(this);
                        int lost = oldBottom - bottom;
                        if (lost > 0) {
                            // NOTE: posted, a layout pass not being the
                            // place to scroll.
                            list.post(() -> header.scroll(lost));
                        }
                    }
                });
    }

    /** Raises the extended add button by the dock's height, or brings it back. */
    private void rise(boolean shown) {
        View fab = host.findViewById(R.id.fab_extended);
        int by =
                Math.max(
                        dock.getHeight(),
                        host.getResources().getDimensionPixelSize(R.dimen.sync_dock_height));
        fab.animate().translationY(shown ? -by : 0).setDuration(200);
    }

    /**
     * Pulses the download glyph while the background download shows,
     * holding it still when the system removes animations.
     */
    private void pulse(boolean on) {
        ImageView glyph = dock.findViewById(R.id.sync_dock_glyph);
        boolean wanted = on && glyph.getVisibility() == View.VISIBLE;
        if (wanted == (pulse != null)) {
            return;
        }
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
            glyph.setAlpha(1f);
        }
        if (wanted && ValueAnimator.areAnimatorsEnabled()) {
            pulse = ObjectAnimator.ofFloat(glyph, View.ALPHA, 1f, 0.3f);
            pulse.setDuration(900);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.start();
        }
    }

    /** Opens the sheet over the list, closing on the scrim or its chevron. */
    private void open() {
        if (sheet != null) {
            return;
        }
        Dialog dialog = new Dialog(host, R.style.SyncSheet);
        dialog.setContentView(R.layout.sync_sheet);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.BOTTOM);
        dialog.findViewById(R.id.sync_sheet_collapse).setOnClickListener(view -> dialog.dismiss());
        dialog.setOnDismissListener(
                closed -> {
                    sheet = null;
                    logShown = -1;
                });
        capScroll(dialog.findViewById(R.id.sync_sheet_scroll));
        sheet = dialog;
        renderSheet();
        dialog.show();
    }

    /**
     * Holds the sheet's lower part to half the screen, scrolling past it,
     * so the scrim above stays to tap.
     */
    private void capScroll(View scroll) {
        int most = host.getResources().getDisplayMetrics().heightPixels / 2;
        View content = ((ViewGroup) scroll).getChildAt(0);
        content.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    int height =
                            bottom - top > most ? most : ViewGroup.LayoutParams.WRAP_CONTENT;
                    ViewGroup.LayoutParams params = scroll.getLayoutParams();
                    if (params.height != height) {
                        params.height = height;
                        scroll.post(() -> scroll.setLayoutParams(params));
                    }
                });
    }

    /** Draws the sheet: title, percentage, bar, the accounts and the log. */
    private void renderSheet() {
        Pass shown = shown();
        if (shown == null) {
            return;
        }
        ((TextView) sheet.findViewById(R.id.sync_sheet_title))
                .setText(pass == null ? R.string.mail_filling : R.string.sync_dock_title);
        ProgressBar bar = sheet.findViewById(R.id.sync_sheet_bar);
        bar(bar, shown);
        TextView percent = sheet.findViewById(R.id.sync_sheet_percent);
        if (shown.total > 0) {
            percent.setText(
                    host.getString(
                            R.string.sync_dock_percent,
                            (int) ((long) Math.min(shown.done, shown.total) * 100 / shown.total)));
            percent.setVisibility(View.VISIBLE);
        } else {
            percent.setVisibility(View.GONE);
        }

        LinearLayout accounts = sheet.findViewById(R.id.sync_sheet_accounts);
        accounts.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(sheet.getContext());
        for (Row row : shown.rows) {
            if (accounts.getChildCount() > 0) {
                View divider = new View(sheet.getContext());
                divider.setBackgroundResource(R.color.divider_light);
                LinearLayout.LayoutParams params =
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
                params.setMarginStart(host.dp(52));
                accounts.addView(divider, params);
            }
            View view = inflater.inflate(R.layout.sync_sheet_row, accounts, false);
            TextView account = view.findViewById(R.id.sync_row_account);
            account.setText(row.account);
            account.setTypeface(
                    null,
                    row.state == Row.RUNNING
                            ? Typeface.BOLD
                            : Typeface.NORMAL);
            ((TextView) view.findViewById(R.id.sync_row_line)).setText(row.line);
            view.findViewById(R.id.sync_row_spinner)
                    .setVisibility(row.state == Row.RUNNING ? View.VISIBLE : View.GONE);
            ImageView mark = view.findViewById(R.id.sync_row_mark);
            if (row.state == Row.RUNNING) {
                mark.setVisibility(View.GONE);
            } else if (row.state == Row.DONE) {
                mark.setImageResource(R.drawable.ic_check);
                mark.setImageTintList(
                        ColorStateList.valueOf(
                                host.resolveColor(android.R.attr.colorAccent)));
            } else {
                mark.setImageResource(R.drawable.sync_ring);
            }
            accounts.addView(view);
        }
        accounts.setVisibility(shown.rows.isEmpty() ? View.GONE : View.VISIBLE);

        if (logShown == logVersion) {
            return;
        }
        logShown = logVersion;
        LinearLayout lines = sheet.findViewById(R.id.sync_sheet_log);
        lines.removeAllViews();
        int secondary = host.resolveColor(android.R.attr.textColorSecondary);
        for (String[] entry : new ArrayList<>(log)) {
            TextView text = new TextView(sheet.getContext());
            SpannableStringBuilder spans = new SpannableStringBuilder(entry[0]);
            spans.setSpan(
                    new ForegroundColorSpan(secondary), 0, entry[0].length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spans.append("  ").append(entry[1]);
            text.setText(spans);
            text.setTextSize(13);
            text.setLineSpacing(0, 1.1f);
            text.setFontFeatureSettings("tnum");
            text.setTextColor(host.resolveColor(android.R.attr.textColorPrimary));
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            if (lines.getChildCount() > 0) {
                params.topMargin = host.dp(6);
            }
            lines.addView(text, params);
        }
        boolean any = !log.isEmpty();
        lines.setVisibility(any ? View.VISIBLE : View.GONE);
        sheet.findViewById(R.id.sync_sheet_recent).setVisibility(any ? View.VISIBLE : View.GONE);
    }
}
