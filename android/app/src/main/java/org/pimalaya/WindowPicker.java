package org.pimalaya;

import android.app.AlertDialog;
import android.content.Context;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.widget.DatePicker;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/**
 * Moving mail windows ({@link MailWindow}): the date picker the list's
 * footer and an account's settings open, "All my mail" beside it, and the
 * line saying what a date takes in.
 */
final class WindowPicker {
    /**
     * The total past which a metered network is told the larger bodies wait
     * for Wi-Fi: a guess, measured beside {@link MailBodies#CAP}.
     */
    static final long LARGE = 10L * 1024 * 1024;

    private WindowPicker() {}

    /**
     * Opens the picker over the windows of {@code emails}, {@code until}
     * their current edge (null for all mail): any day before it, and with
     * {@code later} any day up to today too, which frees the bodies below
     * it once confirmed ({@link #confirmRelease}). The line under it says
     * what {@code query}'s mailboxes hold between the day picked and
     * {@code until}, the bodies they download alone (read on {@code reads});
     * {@code shown} the mailboxes listed down to a day moved back to.
     */
    static void open(
            MainActivity host,
            Executor reads,
            MailStore.Query query,
            String until,
            List<String> emails,
            Predicate<String> shown,
            boolean later,
            Runnable after) {
        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);

        // NOTE: days are the device's, as the list's day headers are; a
        // window holds the instant its midnight is.
        ZoneId zone = ZoneId.systemDefault();
        DatePicker picker = new DatePicker(host);
        LocalDate latest = LocalDate.now(zone);
        if (!later && until != null) {
            LocalDate edge = MailWindow.dayOf(until, zone);
            latest = MailWindow.startOf(edge, zone).compareTo(until) < 0 ? edge : edge.minusDays(1);
        }
        picker.setMaxDate(latest.atStartOfDay(zone).toInstant().toEpochMilli());
        LocalDate shownDay = until == null ? latest : MailWindow.dayOf(until, zone);
        if (shownDay.isAfter(latest)) {
            shownDay = latest;
        }
        picker.updateDate(
                shownDay.getYear(), shownDay.getMonthValue() - 1, shownDay.getDayOfMonth());
        content.addView(picker);

        TextView info = new TextView(host);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.setTextColor(host.resolveColor(android.R.attr.textColorSecondary));
        info.setPadding(host.dp(24), 0, host.dp(24), host.dp(8));
        content.addView(info);

        // NOTE: no statement counts the bodies a release frees, so a day
        // later says what happens rather than how much.
        Runnable preview =
                () -> {
                    String date = dateOf(picker);
                    if (freeing(later, date, until)) {
                        info.setText(R.string.mail_window_frees);
                        return;
                    }
                    reads.execute(
                            () -> {
                                MailStore.Query counted = host.mail.downloading(query);
                                boolean listing = MailStore.listing(query, host.mail.edges());
                                MailStore.Sum sum = host.mail.sum(counted, date, until);
                                host.main.post(
                                        () -> {
                                            if (date.equals(dateOf(picker))) {
                                                info.setText(
                                                        info(
                                                                host,
                                                                sum,
                                                                listing,
                                                                host.online(),
                                                                host.metered()));
                                            }
                                        });
                            });
                };
        picker.setOnDateChangedListener((view, year, month, day) -> preview.run());
        preview.run();

        new AlertDialog.Builder(host)
                .setView(content)
                .setPositiveButton(
                        R.string.mail_window_confirm,
                        (dialog, which) -> {
                            String date = dateOf(picker);
                            if (freeing(later, date, until)) {
                                confirmRelease(host, emails, date, after);
                            } else {
                                apply(host, emails, date, shown, after);
                            }
                        })
                .setNeutralButton(
                        R.string.mail_window_all,
                        (dialog, which) -> apply(host, emails, null, shown, after))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Whether a picker allowing {@code later} moves a window from {@code until} later. */
    private static boolean freeing(boolean later, String date, String until) {
        return later && (until == null || date.compareTo(until) > 0);
    }

    /**
     * Asks before moving the windows of {@code emails} later to {@code date},
     * which frees the bodies below it, headers and search kept
     * ({@link MainActivity#releaseWindow}).
     */
    private static void confirmRelease(
            MainActivity host, List<String> emails, String date, Runnable after) {
        new AlertDialog.Builder(host)
                .setTitle(R.string.mail_window_release_title)
                .setMessage(host.getString(R.string.mail_window_release_message, label(host, date)))
                .setPositiveButton(
                        R.string.mail_window_release,
                        (dialog, which) -> host.releaseWindow(emails, date, after))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Moves the windows of {@code emails} back to {@code date}, null for all
     * mail ({@link MainActivity#moveWindow}), saying so when a sync period
     * had to widen to reach it.
     */
    static void apply(
            MainActivity host,
            List<String> emails,
            String date,
            Predicate<String> shown,
            Runnable after) {
        host.moveWindow(
                emails,
                date,
                shown,
                widened -> {
                    if (widened) {
                        Toast.makeText(host, R.string.mail_window_widened, Toast.LENGTH_LONG)
                                .show();
                    }
                    if (after != null) {
                        after.run();
                    }
                });
    }

    /**
     * What a window moved back takes in, as the footer's line says it: the
     * messages and their size, the sizes the store does not know told
     * apart; that older headers are still syncing; that the download waits
     * for a network, or on a metered one that a large total waits for Wi-Fi.
     */
    static String info(
            Context context, MailStore.Sum sum, boolean syncing, boolean online, boolean metered) {
        List<String> parts = new ArrayList<>();
        if (sum.count > 0) {
            String count = NumberFormat.getIntegerInstance().format(sum.count);
            int quantity = (int) Math.min(Integer.MAX_VALUE, sum.count);
            String size = Formatter.formatShortFileSize(context, sum.size);
            android.content.res.Resources resources = context.getResources();
            if (sum.unknown == 0) {
                parts.add(
                        resources.getQuantityString(
                                R.plurals.mail_window_size, quantity, count, size));
            } else if (sum.unknown == sum.count) {
                parts.add(
                        resources.getQuantityString(
                                R.plurals.mail_window_unknown, quantity, count));
            } else {
                parts.add(
                        resources.getQuantityString(
                                R.plurals.mail_window_unknown_some,
                                quantity,
                                count,
                                size,
                                NumberFormat.getIntegerInstance().format(sum.unknown)));
            }
        }
        if (syncing) {
            parts.add(context.getString(R.string.mail_window_syncing));
        }
        if (!online) {
            parts.add(context.getString(R.string.mail_window_offline));
        } else if (metered && sum.size > LARGE) {
            parts.add(context.getString(R.string.mail_window_wifi));
        }
        return String.join(" · ", parts);
    }

    /**
     * A window's date as a person reads it ("1 September"), the year only
     * when it is not this one; "All my mail" for none. Read on the device's
     * clock, whose midnight the window is.
     */
    static String label(Context context, String date) {
        if (date == null) {
            return context.getString(R.string.mail_window_all);
        }
        LocalDate day = MailWindow.dayOf(date, ZoneId.systemDefault());
        Locale locale = Locale.getDefault();
        boolean thisYear = day.getYear() == LocalDate.now().getYear();
        String pattern =
                DateFormat.getBestDateTimePattern(locale, thisYear ? "dMMMM" : "dMMMMyyyy");
        return DateTimeFormatter.ofPattern(pattern, locale).format(day);
    }

    /** The day a picker shows, as the instant a window holds. */
    private static String dateOf(DatePicker picker) {
        return MailWindow.startOf(
                LocalDate.of(picker.getYear(), picker.getMonth() + 1, picker.getDayOfMonth()),
                ZoneId.systemDefault());
    }
}
