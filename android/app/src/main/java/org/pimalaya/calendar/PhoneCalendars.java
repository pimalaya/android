package org.pimalaya.calendar;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Which calendars the phone's calendar apps show, per account and calendar.
 *
 * <p>Kept as the accounts shown and the calendars kept off, since the setup
 * chooses before the first sync lists them: a calendar listed later joins like
 * its siblings. Keyed by collection id, app state pimdir has no column for.
 */
public final class PhoneCalendars {
    private static final String PREFS = "phone-calendars";

    /** The addresses whose calendars the phone shows. */
    private static final String ACCOUNTS = "accounts";

    /** The calendars of those kept off it, by collection id. */
    private static final String OFF = "off";

    private PhoneCalendars() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Set<String> read(Context context, String key) {
        return new HashSet<>(prefs(context).getStringSet(key, Set.of()));
    }

    /** Whether the phone shows the account's calendar. */
    public static boolean shown(Context context, String account, String calendar) {
        return read(context, ACCOUNTS).contains(account) && !read(context, OFF).contains(calendar);
    }

    /**
     * The setup's choice for the account.
     *
     * <p>On, its calendars show on the phone, those kept off before staying
     * off.
     */
    public static void setAccount(Context context, String account, boolean on) {
        Set<String> accounts = read(context, ACCOUNTS);
        if (on) {
            accounts.add(account);
        } else {
            accounts.remove(account);
        }
        prefs(context).edit().putStringSet(ACCOUNTS, accounts).apply();
    }

    /**
     * One calendar's switch.
     *
     * <p>Turned on in an account the phone does not show, the account comes
     * with that calendar alone, its {@code siblings} kept off.
     */
    public static void set(
            Context context,
            String account,
            String calendar,
            boolean on,
            Collection<String> siblings) {
        Set<String> accounts = read(context, ACCOUNTS);
        Set<String> off = read(context, OFF);
        if (on) {
            if (accounts.add(account)) {
                off.addAll(siblings);
            }
            off.remove(calendar);
        } else {
            off.add(calendar);
        }
        prefs(context).edit().putStringSet(ACCOUNTS, accounts).putStringSet(OFF, off).apply();
    }

    /** Drops what was kept of an account that is gone. */
    public static void forget(Context context, String account, Collection<String> calendars) {
        Set<String> accounts = read(context, ACCOUNTS);
        Set<String> off = read(context, OFF);
        accounts.remove(account);
        off.removeAll(calendars);
        prefs(context).edit().putStringSet(ACCOUNTS, accounts).putStringSet(OFF, off).apply();
    }
}
