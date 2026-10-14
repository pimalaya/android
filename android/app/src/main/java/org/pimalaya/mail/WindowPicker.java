package org.pimalaya.mail;

import android.app.AlertDialog;
import android.content.Context;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.widget.DatePicker;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import org.pimalaya.R;
import org.pimalaya.ui.Host;

/**
 * Moving mail frontiers later ({@link MailWindow}): a date picker and its line.
 *
 * <p>Opened from account settings, it frees bodies below the day picked. Moving
 * one back is the list's "Load more" alone ({@link MailStep}).
 */
public final class WindowPicker {
    private WindowPicker() {}

    /**
     * Opens the picker over the frontiers of {@code emails}.
     *
     * <p>Any day from {@code until} (null for all mail) to today; a later one
     * frees bodies once confirmed ({@link #confirmRelease}), the line counting
     * what {@code query}'s mailboxes hold below it, read on {@code reads}.
     */
    public static void open(
            Host host,
            MailStore mail,
            MailSync sync,
            Executor reads,
            MailStore.Query query,
            String until,
            List<String> emails,
            Runnable after) {
        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);

        // NOTE: days are the device's, as the list's day headers are; a
        // frontier holds the instant its midnight is.
        ZoneId zone = ZoneId.systemDefault();
        DatePicker picker = new DatePicker(host);
        LocalDate today = LocalDate.now(zone);
        picker.setMaxDate(today.atStartOfDay(zone).toInstant().toEpochMilli());
        LocalDate shownDay = today;
        if (until != null) {
            shownDay = MailWindow.dayOf(until, zone);
            picker.setMinDate(shownDay.atStartOfDay(zone).toInstant().toEpochMilli());
        }
        picker.updateDate(
                shownDay.getYear(), shownDay.getMonthValue() - 1, shownDay.getDayOfMonth());
        content.addView(picker);

        TextView info = new TextView(host);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.setTextColor(host.resolveColor(android.R.attr.textColorSecondary));
        info.setPadding(host.dp(24), 0, host.dp(24), host.dp(8));
        content.addView(info);

        Runnable preview =
                () -> {
                    String date = dateOf(picker);
                    if (!later(date, until)) {
                        info.setText("");
                        return;
                    }
                    reads.execute(
                            () -> {
                                // NOTE: a mailbox kept whole keeps its bodies.
                                MailStore.Sum freed =
                                        mail.sum(
                                                query.only(
                                                        collection ->
                                                                !MailOffline.whole(
                                                                        host, collection)),
                                                null,
                                                date,
                                                1);
                                host.postAlive(
                                        () -> {
                                            if (date.equals(dateOf(picker))) {
                                                info.setText(frees(host, freed));
                                            }
                                        });
                            });
                };
        picker.setOnDateChangedListener((view, year, month, day) -> preview.run());
        preview.run();

        new AlertDialog.Builder(host)
                .setView(content)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> {
                            String date = dateOf(picker);
                            if (later(date, until)) {
                                confirmRelease(host, sync, emails, date, after);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** What a frontier moved later frees, as the picker's line says it. */
    static String frees(Context context, MailStore.Sum freed) {
        if (freed.count == 0) {
            return context.getString(R.string.mail_window_frees);
        }
        return context.getResources()
                .getQuantityString(
                        R.plurals.mail_window_frees_some,
                        (int) Math.min(Integer.MAX_VALUE, freed.count),
                        NumberFormat.getIntegerInstance().format(freed.count),
                        Formatter.formatShortFileSize(context, freed.size));
    }

    /** Whether {@code date} moves a frontier at {@code until} later. */
    private static boolean later(String date, String until) {
        return until == null || date.compareTo(until) > 0;
    }

    /**
     * Asks before moving frontiers later to {@code date}, freeing bodies.
     *
     * <p>Headers and search kept ({@link MailSync#releaseWindow}).
     */
    private static void confirmRelease(
            Host host, MailSync sync, List<String> emails, String date, Runnable after) {
        new AlertDialog.Builder(host)
                .setTitle(R.string.mail_window_release_title)
                .setMessage(host.getString(R.string.mail_window_release_message, label(host, date)))
                .setPositiveButton(
                        R.string.mail_window_release,
                        (dialog, which) -> sync.releaseWindow(emails, date, after))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * A frontier's date as read ("1 September"), "All my mail" for none.
     *
     * <p>The year only when not this one; on the device's clock, whose midnight
     * the frontier is.
     */
    public static String label(Context context, String date) {
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

    /** The day a picker shows, as the instant a frontier holds. */
    private static String dateOf(DatePicker picker) {
        return MailWindow.startOf(
                LocalDate.of(picker.getYear(), picker.getMonth() + 1, picker.getDayOfMonth()),
                ZoneId.systemDefault());
    }
}
