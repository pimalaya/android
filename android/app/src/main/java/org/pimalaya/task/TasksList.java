package org.pimalaya.task;

import android.content.SharedPreferences;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.pimalaya.R;
import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.Zones;
import org.pimalaya.client.Task;
import org.pimalaya.client.TaskEdit;
import org.pimalaya.ui.CardSections;
import org.pimalaya.ui.Chips;
import org.pimalaya.ui.Dates;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.ListHeader;
import org.pimalaya.ui.Panels;
import org.pimalaya.ui.SectionsAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Tasks tab: the open tasks of every calendar the filter shows, the GTD
 * lists beside them (cairn/changes/tasks-for-everyone).
 *
 * <p>Two chip rows (cairn/changes/task-filter-rows). The category row holds
 * the GTD lists, at most one on, each a reserved category (pimdir GUIDE §17),
 * then the person's own categories, any number on. The status row under it,
 * at most one on, keeps the tasks under way, done or cancelled ({@code
 * STATUS}), the open tasks with none on. With nothing on, the next actions,
 * merged across accounts and calendars. A row's tick closes it, or reopens it
 * once closed; a tap opens its page ({@link TaskPage}), or clarifies it from
 * the Inbox.
 */
public final class TasksList {
    /** Days after which a weekly review is due again. */
    static final long REVIEW_DAYS = 7;

    /** Where the last weekly review's day is kept, per device. */
    static final String PREFERENCES = "tasks";

    static final String REVIEWED = "reviewed_at";

    /**
     * The statuses the first row filters on ({@link TaskOrder#lists}); none on
     * is the open tasks.
     */
    private static final String[] STATUSES = {
        TaskOrder.PROGRESS, TaskOrder.DONE, TaskOrder.CANCELLED,
    };

    private static final int[] STATUS_LABELS = {
        R.string.task_list_progress, R.string.task_list_done, R.string.task_list_cancelled,
    };

    private static final int[] STATUS_ICONS = {
        R.drawable.ic_task_progress, R.drawable.ic_task_done, R.drawable.ic_cancel,
    };

    /**
     * The GTD lists heading the category row, in GTD's order; none on is Next
     * among the open tasks, any state under a status.
     */
    private static final String[] LISTS = {
        Task.INBOX, Task.WAITING, Task.SOMEDAY,
    };

    private static final int[] LABELS = {
        R.string.task_list_inbox, R.string.task_list_waiting, R.string.task_list_someday,
    };

    private static final int[] ICONS = {
        R.drawable.ic_task_inbox, R.drawable.ic_task_waiting, R.drawable.ic_task_someday,
    };

    private final Host host;
    private final TaskStore store;
    private final TaskWrites writes;
    private final CardSections<Row> sections = new CardSections<>();
    private final Adapter adapter = new Adapter();

    /** Told the Inbox count each time the tab draws, for the widget. */
    public java.util.function.IntConsumer inboxCounted;

    /**
     * Told the open next actions each time the tab draws, due first, for the
     * Next widget.
     */
    public java.util.function.Consumer<List<Task>> nextListed;

    /** A task asked for before the first load, opened once it lands. */
    private String pendingUid;

    /** Whether a load has landed. */
    private boolean loaded;

    /** Opened on a row tap. */
    TaskPage page;

    /** Opened from the bar's Clarify button. */
    Clarify clarify;

    /** Opened from the bar's Weekly review button. */
    WeeklyReview review;

    private ListHeader header;
    private List<TextView> statusChips = new ArrayList<>();
    private LinearLayout categoryRow;

    /** The status on: one of {@link #STATUSES}, null for the open tasks. */
    private String status;

    /** The GTD list on: one of {@link #LISTS}, or Next with none on. */
    private String shown = Task.NEXT;

    /**
     * The categories on, folded, every list narrowed to the tasks carrying
     * any of them; empty for every task.
     */
    private final Set<String> categoriesOn = new LinkedHashSet<>();

    /** The Inbox count of the last draw, for the bar's Clarify button. */
    private int inboxCount;

    /** Every to-do of the last load. */
    private List<TaskStore.Stored> all = new ArrayList<>();

    /** The UIDs of Waiting For items a reply arrived to. */
    private Set<String> replied = Set.of();

    private final ExecutorService reads = Executors.newSingleThreadExecutor();
    private int generation;

    /** One row: a task, and what its line says. */
    static final class Row {
        final TaskStore.Stored stored;
        final String section;

        Row(TaskStore.Stored stored, String section) {
            this.stored = stored;
            this.section = section;
        }
    }

    /** The tab over the calendar store. */
    public TasksList(Host host, CalendarParts parts) {
        this.host = host;
        this.store = new TaskStore(parts);
        this.writes = new TaskWrites(parts);
    }

    /** The store the tab reads, shared with the screens it opens. */
    TaskStore store() {
        return store;
    }

    /** Wires the page, its screens and its header. */
    public void setUp(TaskPage page, Clarify clarify, WeeklyReview review) {
        this.page = page;
        this.clarify = clarify;
        this.review = review;

        ListView list = host.findViewById(R.id.tasks_list);
        header = new ListHeader(host, list, Panels.TASKS);
        header.empty(host.findViewById(R.id.tasks_empty));

        // NOTE: the header's chip row holds the categories, the GTD lists
        // first, drawn again on every render; the statuses take a second row
        // under it, which narrows whatever the categories pick.
        categoryRow = header.chips();
        HorizontalScrollView scroll = new HorizontalScrollView(host);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(host.dp(16), host.dp(10), host.dp(16), 0);
        scroll.setClipToPadding(false);
        LinearLayout statusRow = new LinearLayout(host);
        scroll.addView(statusRow);
        ((ViewGroup) header.view).addView(scroll);
        statusChips =
                Chips.exclusive(
                        host,
                        statusRow,
                        STATUS_LABELS,
                        STATUS_ICONS,
                        picked -> {
                            status = picked < 0 ? null : STATUSES[picked];
                            render();
                            list.setSelection(0);
                        });

        list.setAdapter(adapter);
        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    Row row = sections.row(header.rowAt(position));
                    if (row == null) {
                        return;
                    }
                    if (Task.INBOX.equals(shown) && !row.stored.task.closed()) {
                        clarify.open(List.of(row.stored));
                    } else {
                        page.open(row.stored);
                    }
                });
    }

    /** The list's header, which the bar's title follows. */
    public ListHeader header() {
        return header;
    }

    /**
     * The list shown, as the tab opens on it, among the open tasks, every
     * category shown: Next is no chip on.
     */
    public void show(String list) {
        shown = list;
        status = null;
        categoriesOn.clear();
        reload();
    }

    /** The bar's Clarify: the Inbox, one item at a time. */
    public void clarifyInbox() {
        clarify.open(inbox());
    }

    /** The bar's Weekly review. */
    public void openReview() {
        review.open();
    }

    /** Whether the Inbox holds an open item, for the bar's Clarify button. */
    public boolean hasInbox() {
        return inboxCount > 0;
    }

    /**
     * Reloads every to-do, then the replies to Waiting For.
     *
     * <p>Off the main thread, the last reload winning.
     */
    public void reload() {
        int asked = ++generation;
        reads.execute(
                () -> {
                    List<TaskStore.Stored> read = store.load(host);
                    Set<String> answered = store.replied(read);
                    host.postAlive(
                            () -> {
                                if (asked != generation) {
                                    return;
                                }
                                all = read;
                                replied = answered;
                                loaded = true;
                                render();
                                openPending();
                            });
                });
    }

    /**
     * Opens a task by its UID on its page, after the first load when it has
     * not landed yet (the Next widget's rows).
     */
    public void openTask(String uid) {
        pendingUid = uid;
        if (loaded) {
            openPending();
        }
    }

    private void openPending() {
        String uid = pendingUid;
        if (uid == null) {
            return;
        }
        pendingUid = null;
        for (TaskStore.Stored stored : all) {
            if (stored.task.uid().equals(uid)) {
                page.open(stored);
                return;
            }
        }
    }

    /** The open next actions in Next's order ({@link TaskOrder#NEXT}). */
    private static List<Task> nextActions(List<TaskStore.Stored> open) {
        List<TaskStore.Stored> next = new ArrayList<>();
        for (TaskStore.Stored stored : open) {
            if (stored.task.in(Task.NEXT)) {
                next.add(stored);
            }
        }
        next.sort(TaskOrder.NEXT);
        List<Task> tasks = new ArrayList<>();
        for (TaskStore.Stored stored : next) {
            tasks.add(stored.task);
        }
        return tasks;
    }

    /** The open to-dos in the Inbox, oldest first. */
    List<TaskStore.Stored> inbox() {
        List<TaskStore.Stored> inbox = new ArrayList<>();
        for (TaskStore.Stored stored : all) {
            if (!stored.task.closed() && stored.task.in(Task.INBOX)) {
                inbox.add(stored);
            }
        }
        inbox.sort(TaskOrder.CAPTURED);
        return inbox;
    }

    /** Every open to-do of the last load. */
    List<TaskStore.Stored> open() {
        List<TaskStore.Stored> open = new ArrayList<>();
        for (TaskStore.Stored stored : all) {
            if (!stored.task.closed()) {
                open.add(stored);
            }
        }
        return open;
    }

    /** Whether a Waiting For item had a reply. */
    boolean replied(TaskStore.Stored stored) {
        return replied.contains(stored.task.uid());
    }

    /** Closes or reopens a to-do, then reloads. */
    void done(TaskStore.Stored stored, boolean done) {
        write(stored, TaskEdit.at(TaskWrites.now()).withDone(done));
    }

    private void write(TaskStore.Stored stored, TaskEdit edit) {
        host.io.execute(
                () -> {
                    try {
                        writes.edit(stored, edit);
                    } catch (Exception error) {
                        Log.w("pimalaya", "task edit failed: " + stored.event.id, error);
                        host.postAlive(() -> host.showError(error, R.string.task_save_failed));
                        return;
                    }
                    host.postAlive(this::reload);
                });
    }

    /** Whether {@code status} lists closed tasks: Done or Cancelled. */
    private static boolean closedStatus(String status) {
        return TaskOrder.DONE.equals(status) || TaskOrder.CANCELLED.equals(status);
    }

    /** {@code label} with its count, none when nothing or a closed status. */
    private String counted(int label, int count, String inStatus) {
        String text = host.getString(label);
        // NOTE: the closed statuses only grow, so they count nothing.
        return count > 0 && !closedStatus(inStatus) ? text + "  " + count : text;
    }

    private void render() {
        List<TaskStore.Stored> open = open();
        inboxCount = 0;
        int progress = 0;
        for (TaskStore.Stored stored : open) {
            inboxCount += stored.task.in(Task.INBOX) ? 1 : 0;
            if (TaskOrder.lists(TaskOrder.PROGRESS, shown, stored.task) && narrowed(stored)) {
                progress++;
            }
        }
        for (int index = 0; index < STATUSES.length; index++) {
            TextView chip = statusChips.get(index);
            chip.setText(counted(STATUS_LABELS[index], progress, STATUSES[index]));
            Chips.draw(host, chip, STATUS_ICONS[index], STATUSES[index].equals(status));
        }
        renderCategories();

        header.meta(reviewLine());

        if (inboxCounted != null) {
            inboxCounted.accept(inboxCount);
        }
        if (nextListed != null) {
            nextListed.accept(nextActions(open));
        }
        if (host.screen == Panels.TASKS) {
            host.findViewById(R.id.tasks_clarify)
                    .setVisibility(inboxCount > 0 ? View.VISIBLE : View.GONE);
        }

        List<Row> rows = rowsOf();
        sections.fill(rows, row -> row.section);
        adapter.notifyDataSetChanged();
        host.findViewById(R.id.tasks_empty).setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        ((TextView) host.findViewById(R.id.tasks_empty_text)).setText(emptyOf());
    }

    /** Whether a to-do passes the category filter, compared folded. */
    private boolean narrowed(TaskStore.Stored stored) {
        return TaskOrder.carriesAny(stored, categoriesOn);
    }

    private List<Row> rowsOf() {
        List<TaskStore.Stored> listed = new ArrayList<>();
        for (TaskStore.Stored stored : all) {
            if (TaskOrder.lists(status, shown, stored.task) && narrowed(stored)) {
                listed.add(stored);
            }
        }
        List<Row> rows = new ArrayList<>();
        if (status == null && Task.NEXT.equals(shown) || TaskOrder.PROGRESS.equals(status)) {
            listed.sort(TaskOrder.NEXT);
            String due = host.getString(R.string.task_section_due);
            String actions = host.getString(R.string.task_section_undated);
            for (TaskStore.Stored stored : nested(listed)) {
                rows.add(new Row(stored, sectionOf(stored, listed, due, actions)));
            }
            return rows;
        }
        String section;
        if (TaskOrder.DONE.equals(status)) {
            listed.sort(TaskOrder.COMPLETED);
            section = host.getString(R.string.task_section_newest);
        } else if (TaskOrder.CANCELLED.equals(status)) {
            listed.sort(TaskOrder.RECENT);
            section = host.getString(R.string.task_section_newest);
        } else {
            listed.sort(TaskOrder.CAPTURED);
            section = host.getString(R.string.task_section_oldest);
        }
        for (TaskStore.Stored stored : nested(listed)) {
            rows.add(new Row(stored, section));
        }
        return rows;
    }

    /**
     * Next's section of a row: due or not, a subtask taking its top task's,
     * so it stays under it.
     */
    private static String sectionOf(
            TaskStore.Stored stored, List<TaskStore.Stored> listed, String due, String actions) {
        TaskStore.Stored top = stored;
        Set<String> seen = new HashSet<>();
        while (top.task.parent() != null && seen.add(top.task.uid())) {
            TaskStore.Stored above = find(listed, top.task.parent());
            if (above == null) {
                break;
            }
            top = above;
        }
        return top.task.due() != null ? due : actions;
    }

    /**
     * The rows in their order, each subtask moved under the task its {@code
     * RELATED-TO} names when that one is in the same list.
     */
    private static List<TaskStore.Stored> nested(List<TaskStore.Stored> listed) {
        List<TaskStore.Stored> ordered = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (TaskStore.Stored stored : listed) {
            if (stored.task.parent() == null || find(listed, stored.task.parent()) == null) {
                place(stored, listed, ordered, placed);
            }
        }
        // NOTE: what a cycle of parents left out still shows, at the end.
        for (TaskStore.Stored stored : listed) {
            if (!placed.contains(stored.task.uid())) {
                place(stored, listed, ordered, placed);
            }
        }
        return ordered;
    }

    private static void place(
            TaskStore.Stored stored,
            List<TaskStore.Stored> listed,
            List<TaskStore.Stored> ordered,
            Set<String> placed) {
        if (!placed.add(stored.task.uid())) {
            return;
        }
        ordered.add(stored);
        for (TaskStore.Stored child : listed) {
            if (stored.task.uid().equals(child.task.parent())) {
                place(child, listed, ordered, placed);
            }
        }
    }

    private static TaskStore.Stored find(List<TaskStore.Stored> tasks, String uid) {
        for (TaskStore.Stored stored : tasks) {
            if (stored.task.uid().equals(uid)) {
                return stored;
            }
        }
        return null;
    }

    /** The task a UID names, null when none is loaded. */
    TaskStore.Stored taskOf(String uid) {
        return uid == null ? null : find(all, uid);
    }

    /** Every open to-do but {@code stored}, for a parent picker. */
    List<TaskStore.Stored> parentsFor(TaskStore.Stored stored) {
        List<TaskStore.Stored> parents = new ArrayList<>();
        for (TaskStore.Stored other : open()) {
            if (!other.task.uid().equals(stored.task.uid())) {
                parents.add(other);
            }
        }
        return parents;
    }

    /**
     * Every category an open to-do names, one per folded spelling, in
     * first-seen order.
     */
    Set<String> categories() {
        return new LinkedHashSet<>(TaskOrder.categories(open()));
    }

    /**
     * The category row: the GTD lists, at most one on, each counting its
     * tasks under the status on; then the person's categories the tasks of
     * that status carry, any number on, plus any category on that they lack,
     * so switching status never hides one narrowing the list.
     */
    private void renderCategories() {
        categoryRow.removeAllViews();

        List<TaskStore.Stored> ofStatus = new ArrayList<>();
        for (TaskStore.Stored stored : all) {
            if (status == null
                    ? !stored.task.closed()
                    : TaskOrder.lists(status, Task.NEXT, stored.task)) {
                ofStatus.add(stored);
            }
        }

        for (int index = 0; index < LISTS.length; index++) {
            String target = LISTS[index];
            int count = 0;
            for (TaskStore.Stored stored : ofStatus) {
                if (TaskOrder.lists(status, target, stored.task) && narrowed(stored)) {
                    count++;
                }
            }
            TextView chip =
                    Chips.add(
                            host,
                            categoryRow,
                            counted(LABELS[index], count, status),
                            ICONS[index],
                            on -> {
                                shown = on ? target : Task.NEXT;
                                render();
                            });
            Chips.draw(host, chip, ICONS[index], target.equals(shown));
        }

        // NOTE: a category no task carries any more is turned off, so
        // nothing invisible narrows the lists.
        Map<String, String> every = new HashMap<>();
        for (String name : TaskOrder.categories(all)) {
            every.put(TaskOrder.fold(name), name);
        }
        categoriesOn.retainAll(every.keySet());

        Map<String, String> shownNames = new LinkedHashMap<>();
        for (String name : TaskOrder.categories(ofStatus)) {
            shownNames.put(TaskOrder.fold(name), name);
        }
        for (String folded : categoriesOn) {
            shownNames.putIfAbsent(folded, every.get(folded));
        }

        for (String name : shownNames.values()) {
            String folded = TaskOrder.fold(name);
            TextView chip =
                    Chips.add(
                            host,
                            categoryRow,
                            name,
                            0,
                            on -> {
                                if (on) {
                                    categoriesOn.add(folded);
                                } else {
                                    categoriesOn.remove(folded);
                                }
                                render();
                            });
            Chips.draw(host, chip, 0, categoriesOn.contains(folded));
        }
    }

    /**
     * What the header says of the weekly review: nothing before a first one
     * is finished.
     */
    private String reviewLine() {
        SharedPreferences preferences = host.getSharedPreferences(PREFERENCES, 0);
        long at = preferences.getLong(REVIEWED, 0);
        if (at == 0) {
            return "";
        }
        String day = Dates.day(host, at).toString();
        long days = (System.currentTimeMillis() - at) / 86_400_000L;
        return days >= REVIEW_DAYS
                ? host.getString(R.string.task_review_due, day)
                : host.getString(R.string.task_review_done, day);
    }

    /** What an empty list says: of its status when one is on, else its list. */
    private int emptyOf() {
        switch (status != null ? status : shown) {
            case Task.INBOX:
                return R.string.tasks_empty_inbox;
            case Task.WAITING:
                return R.string.tasks_empty_waiting;
            case Task.SOMEDAY:
                return R.string.tasks_empty_someday;
            case TaskOrder.PROGRESS:
                return R.string.tasks_empty_progress;
            case TaskOrder.DONE:
                return R.string.tasks_empty_done;
            case TaskOrder.CANCELLED:
                return R.string.tasks_empty_cancelled;
            default:
                return R.string.tasks_empty;
        }
    }

    /** The second line of a row: where it belongs, by the list shown. */
    private String lineOf(TaskStore.Stored stored) {
        Task task = stored.task;
        List<String> parts = new ArrayList<>();
        if (Task.WAITING.equals(shown)) {
            String who = task.contactName().isEmpty() ? task.contactAddress() : task.contactName();
            if (!who.isEmpty()) {
                parts.add(who);
            }
            long since = TaskStore.capturedAt(task);
            if (since > 0) {
                parts.add(host.getString(R.string.task_waiting_since, Dates.day(host, since)));
            }
            return String.join(" · ", parts);
        }
        if (Task.INBOX.equals(shown)) {
            long at = TaskStore.capturedAt(task);
            if (at > 0) {
                parts.add(Dates.day(host, at).toString());
            }
            if (task.source() != null) {
                parts.add(host.getString(R.string.task_from_mail));
            }
            return String.join(" · ", parts);
        }
        parts.add(stored.calendar.name);
        parts.addAll(task.categories());
        TaskStore.Stored parent = taskOf(task.parent());
        if (parent != null) {
            parts.add(parent.task.summary());
        }
        return String.join(" · ", parts);
    }

    /**
     * What the right of a row says: when it is due, how long it waited, or
     * when it was done.
     */
    private String whenOf(TaskStore.Stored stored) {
        Task task = stored.task;
        if (Task.WAITING.equals(shown)) {
            long since = TaskStore.capturedAt(task);
            if (since <= 0) {
                return "";
            }
            long days = (System.currentTimeMillis() - since) / 86_400_000L;
            return host.getString(R.string.task_waiting_days, days);
        }
        if (TaskOrder.DONE.equals(status)) {
            return task.completed() == null
                    ? ""
                    : Dates.relativeDays(host, Zones.instant(task.completed()));
        }
        if (task.due() != null) {
            return Dates.relativeDays(host, Zones.instant(task.due()));
        }
        return "";
    }

    private final class Adapter extends SectionsAdapter<Row> {
        Adapter() {
            super(sections);
        }

        @Override
        public boolean isEnabled(int position) {
            return !sections.isHeader(position);
        }

        @Override
        protected View rowView(int position, View recycled, ViewGroup parent) {
            View view = recycled;
            if (view == null) {
                view = LayoutInflater.from(host).inflate(R.layout.item_task, parent, false);
            }
            sections.shape(view, position);
            view.findViewById(R.id.task_divider)
                    .setVisibility(sections.opensCard(position) ? View.GONE : View.VISIBLE);

            Row row = sections.row(position);
            Task task = row.stored.task;
            ((TextView) view.findViewById(R.id.task_summary))
                    .setText(
                            task.summary().isEmpty()
                                    ? host.getString(R.string.event_untitled)
                                    : task.summary());
            ((TextView) view.findViewById(R.id.task_line)).setText(lineOf(row.stored));

            TextView when = view.findViewById(R.id.task_when);
            when.setText(whenOf(row.stored));
            boolean waiting = Task.WAITING.equals(shown);
            boolean late =
                    waiting
                            && TaskStore.capturedAt(task) > 0
                            && System.currentTimeMillis() - TaskStore.capturedAt(task)
                                    > 14 * 86_400_000L;
            when.setTextColor(
                    host.ui.resolveColor(
                            late ? android.R.attr.colorError : android.R.attr.textColorSecondary));

            TextView badge = view.findViewById(R.id.task_badge);
            if (waiting && replied(row.stored)) {
                badge.setText(R.string.task_reply_received);
                badge.setVisibility(View.VISIBLE);
            } else if (!TaskOrder.PROGRESS.equals(status) && TaskOrder.underWay(task)) {
                badge.setText(R.string.task_list_progress);
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }

            // NOTE: a capture is clarified rather than ticked off; a closed
            // task's tick reopens it.
            ImageButton done = view.findViewById(R.id.task_done);
            boolean tickable = !Task.INBOX.equals(shown);
            boolean closed = task.closed();
            done.setVisibility(tickable ? View.VISIBLE : View.INVISIBLE);
            done.setOnClickListener(tickable ? button -> done(row.stored, !closed) : null);
            done.setImageResource(
                    !closed
                            ? R.drawable.ic_circle
                            : TaskOrder.DONE.equals(task.status())
                                    ? R.drawable.ic_task_done
                                    : R.drawable.ic_cancel);
            done.setContentDescription(
                    host.getString(closed ? R.string.task_reopen : R.string.task_mark_done));
            return view;
        }
    }
}
