package org.pimalaya.task;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONArray;
import org.pimalaya.account.LocalBook;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.PimdirSql;
import org.pimalaya.client.Task;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirItems;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The to-dos of every calendar, read the GTD way
 * (cairn/changes/gtd-by-categories).
 *
 * <p>No store of its own: a task is a VTODO the calendar sync already keeps,
 * listed from its {@code task_summary} row, its categories and its {@code
 * contact} address ({@link EventStore#loadTasks}, pimdir STORAGE Annex A.4),
 * read the GTD way by {@link CalendarClient#tasksOf}: no body is read for the
 * list. A page reads its body ({@link Stored#full}), its description and what
 * an edit patches.
 */
public final class TaskStore {
    /** The calendar the last capture went to, in the Tasks preferences. */
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
        /** The to-do as its summary holds it, its description empty. */
        public final Task task;

        /** The to-do read off its body, once asked for. */
        private Task full;

        Stored(EventStore.StoredEvent event, EventStore.StoredCalendar calendar, Task task) {
            this.event = event;
            this.calendar = calendar;
            this.task = task;
        }

        /**
         * The to-do read off its body, with what its summary does not hold
         * (its {@code DESCRIPTION}); the summary's when the body reads as
         * none.
         */
        public synchronized Task full() {
            if (full == null) {
                Stored read = read(event, calendar);
                full = read == null ? task : read.task;
            }
            return full;
        }
    }

    /**
     * Every to-do of the calendars the filter shows, open and closed.
     *
     * <p>Off the main thread: it reads the store's summaries, no body.
     */
    public List<Stored> load(Context context) {
        MergedFilter filter = MergedFilter.of(context, PimDomain.CALENDAR);
        Map<String, EventStore.StoredCalendar> byCollection = new HashMap<>();
        for (EventStore.StoredCalendar calendar : parts.events().loadCalendars()) {
            byCollection.put(calendar.id, calendar);
        }

        List<Stored> tasks = new ArrayList<>();
        try {
            EventStore.TaskRows rows = parts.events().loadTasks();
            for (Map.Entry<String, Task> read :
                    CalendarClient.tasksOf(rows.rows, rows.categories).entrySet()) {
                EventStore.StoredEvent event = rows.events.get(read.getKey());
                EventStore.StoredCalendar calendar =
                        event == null ? null : byCollection.get(event.collectionId);
                if (calendar == null || !filter.accepts(calendar.accountEmail, calendar.id)) {
                    continue;
                }
                tasks.add(new Stored(event, calendar, read.getValue()));
            }
        } catch (Exception error) {
            Log.w("pimalaya", "task listing failed", error);
        }
        return tasks;
    }

    /**
     * {@code stored} as the store holds it now, null when it holds it no
     * more: what a page goes on with after a sync replaced the body it read.
     *
     * <p>Off the main thread: it reads the body.
     */
    public Stored reread(Stored stored) {
        EventStore.StoredEvent event =
                parts.events().loadEvent(stored.event.collectionId, stored.event.id);
        return event == null ? null : read(event, stored.calendar);
    }

    /**
     * One to-do read the GTD way, null when it does not read as one.
     *
     * <p>What an agenda row opens: the row holds the object already.
     */
    public static Stored read(EventStore.StoredEvent event, EventStore.StoredCalendar calendar) {
        try {
            Task task = CalendarClient.readTasks(Map.of("", event.ical())).get("");
            return task == null ? null : new Stored(event, calendar, task);
        } catch (Exception error) {
            Log.w("pimalaya", "task read failed: " + event.id, error);
            return null;
        }
    }

    /**
     * Whether a calendar can hold tasks.
     *
     * <p>A CalDAV calendar (VTODO, RFC 4791), or a To Do or Google task list,
     * the services Microsoft and Google keep tasks in apart from their
     * calendars; JMAP calendar writes are not wired, and the phone's local
     * calendar takes no write. And not one whose server states it takes none
     * ({@code x-vtodo} {@code none}, pimdir STORAGE §15.6), as a Google CalDAV
     * calendar does.
     */
    public static boolean takesTasks(EventStore.StoredCalendar calendar) {
        return calendar.writable
                && (EventStore.isCaldav(calendar.url) || EventStore.isTaskList(calendar.url))
                && !LocalBook.is(calendar.url)
                && !calendar.refusesTasks;
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
     * <p>A live mail whose {@code In-Reply-To} names the message the item came
     * from, the person's own under {@code sent} or {@code drafts} answering
     * nothing: io-pimdir's {@code PimdirReader::replied}, its canonical
     * {@code list_replied} run once for every item. Computed from the store,
     * never written, so it holds whichever client closes the item.
     */
    public Set<String> replied(List<Stored> tasks) {
        Map<String, Set<String>> waiting = new HashMap<>();
        for (Stored stored : tasks) {
            Task task = stored.task;
            if (!task.in(Task.WAITING) || task.closed() || task.source() == null) {
                continue;
            }
            String id = bare(task.source());
            if (!id.isEmpty()) {
                waiting.computeIfAbsent(id, any -> new HashSet<>()).add(task.uid());
            }
        }
        Set<String> replied = new HashSet<>();
        if (waiting.isEmpty()) {
            return replied;
        }
        PimdirSql.Bound bound =
                PimdirSql.bind(
                        "LIST_REPLIED",
                        Map.of("ids", new JSONArray(waiting.keySet()).toString()));
        SQLiteDatabase db = new PimdirItems(parts.pimdir()).readable();
        try (Cursor cursor = PimdirDb.query(db, bound)) {
            while (cursor.moveToNext()) {
                replied.addAll(waiting.getOrDefault(cursor.getString(0), Set.of()));
            }
        } catch (Exception error) {
            Log.w("pimalaya", "reply lookup failed", error);
        }
        return replied;
    }

    /**
     * A {@code Message-ID} as {@code list_replied} compares it, as io-pimdir's
     * reader takes it: trimmed, one pair of enclosing angle brackets stripped.
     */
    private static String bare(String messageId) {
        String id = messageId.trim();
        if (id.startsWith("<") && id.endsWith(">") && id.length() > 1) {
            id = id.substring(1, id.length() - 1);
        }
        return id.trim();
    }

    /**
     * Where the mail of a {@code Message-ID} is filed: its collection and link
     * id, null when the store holds no copy.
     */
    public String[] mailOf(String messageId) {
        try (Cursor cursor =
                PimdirDb.query(
                        new PimdirItems(parts.pimdir()).readable(),
                        "MAIL_OF_MESSAGE_ID",
                        PimdirSql.values("message_id", messageId))) {
            return cursor.moveToNext()
                    ? new String[] {cursor.getString(0), cursor.getString(1)}
                    : null;
        }
    }

    /**
     * The calendars a capture can go to, the one the last capture went to
     * first.
     *
     * <p>No default: a person with several picks one in the sheet once, and
     * the next capture goes there too.
     */
    public List<EventStore.StoredCalendar> targets(Context context) {
        List<EventStore.StoredCalendar> targets = targetsOf(parts.events());
        String chosen = captureTarget(context);
        for (int index = 0; index < targets.size(); index++) {
            if (targets.get(index).id.equals(chosen)) {
                targets.add(0, targets.remove(index));
            }
        }
        return targets;
    }

    /** The calendar the last capture went to; null before the first. */
    public static String captureTarget(Context context) {
        return context.getSharedPreferences(TasksList.PREFERENCES, Context.MODE_PRIVATE)
                .getString(TARGET, null);
    }

    /** Keeps {@code calendarId} as the one the next capture goes to. */
    public static void captureTo(Context context, String calendarId) {
        context.getSharedPreferences(TasksList.PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(TARGET, calendarId)
                .apply();
    }

    /**
     * The calendars the Tasks filter offers, in the store's order: every one
     * the calendar filter shows but those stating they take no to-do.
     *
     * <p>Off the main thread, as {@link #load}.
     */
    public List<EventStore.StoredCalendar> calendars(Context context) {
        MergedFilter filter = MergedFilter.of(context, PimDomain.CALENDAR);
        List<EventStore.StoredCalendar> shown = new ArrayList<>();
        for (EventStore.StoredCalendar calendar : parts.events().loadCalendars()) {
            if (!calendar.refusesTasks && filter.accepts(calendar.accountEmail, calendar.id)) {
                shown.add(calendar);
            }
        }
        return shown;
    }

    /** The calendars a capture can go to, in the store's order. */
    public List<EventStore.StoredCalendar> targets() {
        return targetsOf(parts.events());
    }

    private static List<EventStore.StoredCalendar> targetsOf(EventStore events) {
        List<EventStore.StoredCalendar> targets = new ArrayList<>();
        for (EventStore.StoredCalendar calendar : events.loadCalendars()) {
            if (takesTasks(calendar)) {
                targets.add(calendar);
            }
        }
        return targets;
    }
}
