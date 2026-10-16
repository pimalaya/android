package org.pimalaya.calendar;

import android.accounts.Account;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Colors;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Reminders;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.pimalaya.phone.Accounts;
import org.pimalaya.phone.PhoneMirror;
import org.pimalaya.pimdir.DefaultCollection;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.ui.Avatar;

/**
 * The phone's calendars: an Android account per address, a row per calendar.
 *
 * <p>Written as the sync adapter, so no app sees an edit. Name and colour
 * change only with the collection's since last projected ({@code CAL_SYNC1},
 * {@code CAL_SYNC2}); {@code SYNC_EVENTS} and {@code VISIBLE} are the user's.
 */
public final class CalendarRows {
    /** The provider's own default, written explicitly. */
    private static final int MAX_REMINDERS = 5;

    private CalendarRows() {}

    /**
     * Aligns the phone's calendar accounts and rows with what it shows.
     *
     * <p>Blocks: run it off the main thread. A row leaves after its phone pass,
     * since the provider deletes a calendar with its phone edits. Without the
     * calendar permission only the accounts follow.
     */
    public static void reconcile(Context context, PimdirDb pimdir) throws Exception {
        List<PimdirCollections.Stored> all =
                new PimdirCollections(pimdir, context).list(PimdirSummary.CALENDAR);
        Map<String, List<PimdirCollections.Stored>> shown = new LinkedHashMap<>();
        for (PimdirCollections.Stored calendar : all) {
            if (PhoneCalendars.shown(context, calendar.accountEmail, calendar.id)) {
                shown.computeIfAbsent(calendar.accountEmail, key -> new ArrayList<>())
                        .add(calendar);
            }
        }

        boolean granted = PhoneMirror.CALENDAR.granted(context);
        if (granted) {
            for (Account account : Accounts.calendarAccounts(context)) {
                if (!shown.containsKey(Accounts.calendarAddress(context, account))) {
                    for (String collection : rows(context, account).keySet()) {
                        leave(pimdir, collection);
                    }
                }
            }
            // NOTE: an earlier build's account goes whole; its calendars
            // come back under the new one, projected whole.
            for (Account account : Accounts.legacyCalendarAccounts(context)) {
                for (String collection : rows(context, account).keySet()) {
                    leave(pimdir, collection);
                }
            }
        }
        Accounts.reconcileCalendars(context, shown.keySet());
        if (!granted) {
            return;
        }

        ContentResolver resolver = context.getContentResolver();
        for (Map.Entry<String, List<PimdirCollections.Stored>> entry : shown.entrySet()) {
            Account account = Accounts.findCalendars(context, entry.getKey());
            if (account == null) {
                continue;
            }
            seedColors(context, account);

            PimdirCollections.Stored primary =
                    DefaultCollection.of(context, entry.getKey(), PimdirSummary.CALENDAR, all);
            Map<String, Row> rows = rows(context, account);
            Set<String> kept = new HashSet<>();
            for (PimdirCollections.Stored calendar : entry.getValue()) {
                kept.add(calendar.id);
                boolean isPrimary = primary != null && primary.id.equals(calendar.id);
                // NOTE: the roster carries no calendar-timezone yet (no
                // backend lists it), so the column stays empty.
                Row row = rows.get(calendar.id);
                if (row == null) {
                    resolver.insert(
                            asSyncAdapter(Calendars.CONTENT_URI, account),
                            insert(account, calendar, isPrimary, null));
                    // NOTE: a new row holds no event whatever the store last
                    // knew of the phone (an account removed in the system's
                    // settings takes its calendars along), so it is projected
                    // whole.
                    new PimdirStorage(pimdir).forget(PimdirStorage.phoneCollection(calendar.id));
                    phonePass(pimdir, calendar.id);
                    continue;
                }
                // NOTE: an update notifies every calendar app, so one that
                // changes nothing is not written.
                ContentValues values = update(calendar, isPrimary, null, row);
                if (!unchanged(values, current(context, row.id, values.keySet()))) {
                    resolver.update(
                            asSyncAdapter(
                                    ContentUris.withAppendedId(Calendars.CONTENT_URI, row.id),
                                    account),
                            values,
                            null,
                            null);
                }
            }
            for (Map.Entry<String, Row> row : rows.entrySet()) {
                if (!kept.contains(row.getKey())) {
                    leave(pimdir, row.getKey());
                    resolver.delete(
                            asSyncAdapter(
                                    ContentUris.withAppendedId(
                                            Calendars.CONTENT_URI, row.getValue().id),
                                    account),
                            null,
                            null);
                }
            }
        }
    }

    /**
     * One shown calendar's phone pass, offline ({@link CalendarPhonePass}).
     *
     * <p>Runs before its rows leave, on Android's upload syncs ({@code
     * CalendarSyncService}), on the app's return, in the background run and
     * after a zone change. Answers whether it brought a calendar-app edit in.
     */
    public static boolean phonePass(PimdirDb pimdir, String collection) {
        return CalendarPhonePass.pass(pimdir, collection);
    }

    /** Every calendar the phone shows by collection id, of any account. */
    public static List<String> shown(Context context) {
        List<String> shown = new ArrayList<>();
        for (Account account : Accounts.calendarAccounts(context)) {
            shown.addAll(rows(context, account).keySet());
        }
        return shown;
    }

    /**
     * A calendar leaving the phone: a last phone pass, then forgotten.
     *
     * <p>The store forgets what the phone held of it, since the provider
     * deletes its events with its row. Shown again, it is projected whole.
     */
    private static void leave(PimdirDb pimdir, String collection) throws Exception {
        phonePass(pimdir, collection);
        new PimdirStorage(pimdir).forget(PimdirStorage.phoneCollection(collection));
    }

    /** A {@code Calendars} row's current values of some columns, as text. */
    private static Map<String, String> current(Context context, long id, Set<String> columns) {
        Map<String, String> current = new HashMap<>();
        String[] projection = columns.toArray(new String[0]);
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                ContentUris.withAppendedId(Calendars.CONTENT_URI, id),
                                projection,
                                null,
                                null,
                                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                for (int column = 0; column < projection.length; column++) {
                    current.put(projection[column], cursor.getString(column));
                }
            }
        }
        return current;
    }

    /** Whether a row already holds an update's values, compared as text. */
    static boolean unchanged(ContentValues values, Map<String, String> current) {
        for (String column : values.keySet()) {
            if (!current.containsKey(column)
                    || !Objects.equals(values.getAsString(column), current.get(column))) {
                return false;
            }
        }
        return true;
    }

    /** One of the account's {@code Calendars} rows, as last projected. */
    static final class Row {
        final long id;

        /** The collection's name last projected ({@code CAL_SYNC1}). */
        final String name;

        /** The colour last projected ({@code CAL_SYNC2}). */
        final String color;

        Row(long id, String name, String color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }
    }

    /** The account's {@code Calendars} rows, keyed by {@code _SYNC_ID}. */
    public static Map<String, Row> rows(Context context, Account account) {
        Map<String, Row> rows = new LinkedHashMap<>();
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                Calendars.CONTENT_URI,
                                new String[] {
                                    Calendars._ID,
                                    Calendars._SYNC_ID,
                                    Calendars.CAL_SYNC1,
                                    Calendars.CAL_SYNC2
                                },
                                Calendars.ACCOUNT_NAME
                                        + " = ? AND "
                                        + Calendars.ACCOUNT_TYPE
                                        + " = ?",
                                new String[] {account.name, account.type},
                                null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (!cursor.isNull(1)) {
                    rows.put(
                            cursor.getString(1),
                            new Row(cursor.getLong(0), cursor.getString(2), cursor.getString(3)));
                }
            }
        }
        return rows;
    }

    /** A new calendar's row. */
    static ContentValues insert(
            Account account, PimdirCollections.Stored calendar, boolean primary, String zone) {
        ContentValues values = managed(calendar, primary, zone);
        values.put(Calendars.ACCOUNT_NAME, account.name);
        values.put(Calendars.ACCOUNT_TYPE, account.type);
        values.put(Calendars._SYNC_ID, calendar.id);
        values.put(Calendars.SYNC_EVENTS, 1);
        values.put(Calendars.VISIBLE, 1);
        named(values, calendar);
        colored(values, calendar);
        return values;
    }

    /**
     * A shown calendar's update against its last projected name and colour.
     *
     * <p>{@code last} holds them; either is written only when the collection's
     * own changed.
     */
    static ContentValues update(
            PimdirCollections.Stored calendar, boolean primary, String zone, Row last) {
        String lastName = last.name;
        String lastColor = last.color;

        ContentValues values = managed(calendar, primary, zone);
        if (!calendar.name.equals(lastName)) {
            named(values, calendar);
        }
        if (!String.valueOf(color(calendar)).equals(lastColor)) {
            colored(values, calendar);
        }
        return values;
    }

    /** The columns every projection writes, insert and update alike. */
    private static ContentValues managed(
            PimdirCollections.Stored calendar, boolean primary, String zone) {
        ContentValues values = new ContentValues();
        values.put(
                Calendars.CALENDAR_ACCESS_LEVEL,
                calendar.writable ? Calendars.CAL_ACCESS_OWNER : Calendars.CAL_ACCESS_READ);
        values.put(Calendars.OWNER_ACCOUNT, calendar.accountEmail);
        values.put(Calendars.IS_PRIMARY, primary ? 1 : 0);
        values.put(
                Calendars.CALENDAR_TIME_ZONE,
                zone != null && ZoneId.getAvailableZoneIds().contains(zone) ? zone : null);
        values.put(Calendars.ALLOWED_REMINDERS, String.valueOf(Reminders.METHOD_ALERT));
        values.put(Calendars.MAX_REMINDERS, MAX_REMINDERS);
        values.put(
                Calendars.ALLOWED_AVAILABILITY,
                Events.AVAILABILITY_BUSY + "," + Events.AVAILABILITY_FREE);
        values.put(
                Calendars.ALLOWED_ATTENDEE_TYPES,
                Attendees.TYPE_NONE
                        + ","
                        + Attendees.TYPE_REQUIRED
                        + ","
                        + Attendees.TYPE_OPTIONAL
                        + ","
                        + Attendees.TYPE_RESOURCE);
        values.put(Calendars.CAN_ORGANIZER_RESPOND, 1);
        values.put(Calendars.CAN_MODIFY_TIME_ZONE, 1);
        // NOTE: at 1 the provider keeps a hidden copy of every row an app
        // edits, which sync-adapter queries would see.
        values.put(Calendars.CAN_PARTIALLY_UPDATE, 0);
        return values;
    }

    private static void named(ContentValues values, PimdirCollections.Stored calendar) {
        values.put(Calendars.NAME, calendar.name);
        values.put(Calendars.CALENDAR_DISPLAY_NAME, calendar.name);
        values.put(Calendars.CAL_SYNC1, calendar.name);
    }

    private static void colored(ContentValues values, PimdirCollections.Stored calendar) {
        int color = color(calendar);
        values.put(Calendars.CALENDAR_COLOR, color);
        values.put(Calendars.CAL_SYNC2, String.valueOf(color));
    }

    /** Its colour, else its account's avatar hue, as the agenda draws it. */
    private static int color(PimdirCollections.Stored calendar) {
        return Avatar.colorOf(calendar.color, calendar.accountEmail);
    }

    /**
     * Seeds the account's event colours before any event names one.
     *
     * <p>The provider refuses an {@code EVENT_COLOR_KEY} absent from them, and
     * calendar apps offer only those.
     */
    private static void seedColors(Context context, Account account) throws Exception {
        Set<String> present = new HashSet<>();
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                Colors.CONTENT_URI,
                                new String[] {Colors.COLOR_KEY},
                                Colors.ACCOUNT_NAME
                                        + " = ? AND "
                                        + Colors.ACCOUNT_TYPE
                                        + " = ? AND "
                                        + Colors.COLOR_TYPE
                                        + " = ?",
                                new String[] {
                                    account.name,
                                    account.type,
                                    String.valueOf(Colors.TYPE_EVENT)
                                },
                                null)) {
            while (cursor != null && cursor.moveToNext()) {
                present.add(cursor.getString(0));
            }
        }

        ArrayList<ContentProviderOperation> inserts = new ArrayList<>();
        for (ContentValues values : colors(account, present)) {
            inserts.add(
                    ContentProviderOperation.newInsert(asSyncAdapter(Colors.CONTENT_URI, account))
                            .withValues(values)
                            .build());
        }
        if (!inserts.isEmpty()) {
            context.getContentResolver().applyBatch(CalendarContract.AUTHORITY, inserts);
        }
    }

    /** The account's event colours not in {@code present}, by CSS name. */
    static List<ContentValues> colors(Account account, Set<String> present) {
        List<ContentValues> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> named : CssColors.NAMED.entrySet()) {
            if (present.contains(named.getKey())) {
                continue;
            }
            ContentValues values = new ContentValues();
            values.put(Colors.ACCOUNT_NAME, account.name);
            values.put(Colors.ACCOUNT_TYPE, account.type);
            values.put(Colors.COLOR_TYPE, Colors.TYPE_EVENT);
            values.put(Colors.COLOR_KEY, named.getKey());
            values.put(Colors.COLOR, named.getValue());
            rows.add(values);
        }
        return rows;
    }

    /**
     * The URI as the account's sync adapter writes it.
     *
     * <p>The provider refuses a sync-adapter write without the account.
     */
    static Uri asSyncAdapter(Uri uri, Account account) {
        return uri.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
                .build();
    }
}
