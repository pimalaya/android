package org.pimalaya.task;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import io.requery.android.database.sqlite.SQLiteDatabase;

import org.pimalaya.account.LocalBook;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Task;
import org.pimalaya.pimdir.PimdirItems;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The to-dos of every calendar, read the GTD way (cairn/changes/gtd).
 *
 * <p>No store of its own: a task is a VTODO the calendar sync already keeps,
 * its list, contexts and project read off the object at load time
 * ({@link CalendarClient#readTasks}), as the contacts list reads a phone off
 * its card.
 */
public final class TaskStore {
    /** Where the calendar captures go to is kept, in the Tasks preferences. */
    private static final String TARGET = "capture_target";

    private final CalendarParts parts;

    /** The task view of the calendar store. */
    public TaskStore(CalendarParts parts) {
        this.parts = parts;
    }

    /** One to-do, the object it is read from and the calendar holding it. */
    public static final class Stored {
        public final EventStore.StoredEvent event;
        public final EventStore.StoredCalendar calendar;
        public final Task task;

        Stored(EventStore.StoredEvent event, EventStore.StoredCalendar calendar, Task task) {
            this.event = event;
            this.calendar = calendar;
            this.task = task;
        }
    }

    /**
     * Every to-do of the calendars the filter shows, open and closed.
     *
     * <p>Off the main thread: it reads every calendar object.
     */
    public List<Stored> load(Context context) {
        MergedFilter filter = MergedFilter.of(context, PimDomain.CALENDAR);
        Map<String, EventStore.StoredCalendar> byCollection = new HashMap<>();
        for (EventStore.StoredCalendar calendar : parts.events().loadCalendars()) {
            byCollection.put(calendar.id, calendar);
        }

        Map<String, EventStore.StoredEvent> byKey = new LinkedHashMap<>();
        Map<String, String> objects = new LinkedHashMap<>();
        for (EventStore.StoredEvent event : parts.events().loadEvents()) {
            EventStore.StoredCalendar calendar = byCollection.get(event.collectionId);
            if (calendar == null || !filter.accepts(calendar.accountEmail, calendar.id)) {
                continue;
            }
            // NOTE: a to-do is told by its text before the bridge parses it,
            // so an agenda of thousands of events costs one scan, not as
            // many parses.
            if (!event.ical.contains("BEGIN:VTODO")) {
                continue;
            }
            String key = event.collectionId + "\n" + event.id;
            byKey.put(key, event);
            objects.put(key, event.ical);
        }

        List<Stored> tasks = new ArrayList<>();
        try {
            for (Map.Entry<String, Task> read : CalendarClient.readTasks(objects).entrySet()) {
                EventStore.StoredEvent event = byKey.get(read.getKey());
                tasks.add(new Stored(event, byCollection.get(event.collectionId), read.getValue()));
            }
        } catch (Exception error) {
            Log.w("pimalaya", "task listing failed", error);
        }
        return tasks;
    }

    /**
     * One to-do read the GTD way, null when it does not read as one.
     *
     * <p>What an agenda row opens: the row holds the object already.
     */
    public static Stored read(EventStore.StoredEvent event, EventStore.StoredCalendar calendar) {
        try {
            Task task = CalendarClient.readTasks(Map.of("", event.ical)).get("");
            return task == null ? null : new Stored(event, calendar, task);
        } catch (Exception error) {
            Log.w("pimalaya", "task read failed: " + event.id, error);
            return null;
        }
    }

    /**
     * Whether a calendar can hold tasks in this first draft.
     *
     * <p>CalDAV only (VTODO, RFC 4791): Graph and Google keep tasks in a
     * service of their own, JMAP calendar writes are not wired, and the phone's
     * local calendar takes no write.
     */
    public static boolean takesTasks(EventStore.StoredCalendar calendar) {
        String url = calendar.url == null ? "" : calendar.url;
        return calendar.writable
                && (url.startsWith("https://") || url.startsWith("http://"))
                && !LocalBook.is(url);
    }

    /**
     * When a to-do was captured, epoch milliseconds; 0 when unknown.
     *
     * <p>Its {@code CREATED}, else {@code DTSTAMP}: what Waiting For counts its
     * days from, as an item enters it when its request is sent.
     */
    public static long capturedAt(Task task) {
        String stamp = task.created();
        if (stamp == null || stamp.length() < 15) {
            return 0;
        }
        try {
            return java.time.LocalDateTime.parse(
                            stamp.substring(0, 15),
                            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                    .toInstant(java.time.ZoneOffset.UTC)
                    .toEpochMilli();
        } catch (java.time.DateTimeException error) {
            return 0;
        }
    }

    /**
     * The UIDs of the Waiting For items a reply arrived to.
     *
     * <p>A mail whose {@code In-Reply-To} names the message the item came
     * from: computed from the store, never written, so it holds whichever
     * client closes the item.
     */
    public Set<String> replied(List<Stored> tasks) {
        Set<String> replied = new HashSet<>();
        SQLiteDatabase db = new PimdirItems(parts.pimdir()).readable();
        for (Stored stored : tasks) {
            Task task = stored.task;
            if (!Task.WAITING.equals(task.list()) || task.closed() || task.source() == null) {
                continue;
            }
            try (Cursor cursor =
                    db.rawQuery(
                            "SELECT 1 FROM mail_summary m"
                                    + " JOIN items i ON i.collection = m.collection"
                                    + " AND i.link_id = m.link_id"
                                    + " WHERE i.deleted = 0 AND m.in_reply_to IS NOT NULL"
                                    + " AND EXISTS (SELECT 1 FROM json_each(m.in_reply_to)"
                                    + " WHERE value = ?) LIMIT 1",
                            new String[] {task.source()})) {
                if (cursor.moveToNext()) {
                    replied.add(task.uid());
                }
            } catch (Exception error) {
                Log.w("pimalaya", "reply lookup failed: " + task.uid(), error);
            }
        }
        return replied;
    }

    /**
     * Where the mail of a {@code Message-ID} is filed: its collection and link
     * id, null when the store holds no copy.
     */
    public String[] mailOf(String messageId) {
        try (Cursor cursor =
                new PimdirItems(parts.pimdir())
                        .readable()
                        .rawQuery(
                                "SELECT m.collection, m.link_id FROM mail_summary m"
                                        + " JOIN items i ON i.collection = m.collection"
                                        + " AND i.link_id = m.link_id"
                                        + " WHERE i.deleted = 0 AND m.message_id = ? LIMIT 1",
                                new String[] {messageId})) {
            return cursor.moveToNext()
                    ? new String[] {cursor.getString(0), cursor.getString(1)}
                    : null;
        }
    }

    /**
     * The calendars a capture can go to, the one captures go to first.
     *
     * <p>That is the one last picked, in the sheet or in account settings,
     * else a default calendar.
     */
    public List<EventStore.StoredCalendar> targets(Context context) {
        return targets(parts.events(), context);
    }

    /** The same over {@code events}, for a page holding no calendar parts. */
    public static List<EventStore.StoredCalendar> targets(EventStore events, Context context) {
        List<EventStore.StoredCalendar> targets = targetsOf(events);
        String chosen = captureTarget(context);
        for (int index = 0; index < targets.size(); index++) {
            if (targets.get(index).id.equals(chosen)) {
                targets.add(0, targets.remove(index));
            }
        }
        return targets;
    }

    /** The calendar captures go to, as last picked; null when never picked. */
    public static String captureTarget(Context context) {
        return context.getSharedPreferences(TasksList.PREFERENCES, Context.MODE_PRIVATE)
                .getString(TARGET, null);
    }

    /** Sends the next captures to {@code calendarId}, kept on the device. */
    public static void captureTo(Context context, String calendarId) {
        context.getSharedPreferences(TasksList.PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(TARGET, calendarId)
                .apply();
    }

    /** The calendars a capture can go to, defaults first. */
    public List<EventStore.StoredCalendar> targets() {
        return targetsOf(parts.events());
    }

    private static List<EventStore.StoredCalendar> targetsOf(EventStore events) {
        List<EventStore.StoredCalendar> targets = new ArrayList<>();
        for (EventStore.StoredCalendar calendar : events.loadCalendars()) {
            if (takesTasks(calendar)) {
                if (calendar.isDefault) {
                    targets.add(0, calendar);
                } else {
                    targets.add(calendar);
                }
            }
        }
        return targets;
    }
}
