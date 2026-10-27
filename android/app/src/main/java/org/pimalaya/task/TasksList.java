package org.pimalaya.task;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
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
import org.pimalaya.ui.Dates;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.ListHeader;
import org.pimalaya.ui.Panels;
import org.pimalaya.ui.SectionsAdapter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Tasks tab: the GTD lists over every calendar's to-dos.
 *
 * <p>Inbox, Next by context, Waiting For, Projects and Someday, each a filter
 * of the one load ({@link TaskStore}). A row's tick closes it; a tap opens its
 * page ({@link TaskPage}).
 */
public final class TasksList {
    /** Days after which a weekly review is due again. */
    static final long REVIEW_DAYS = 7;

    /** Where the last weekly review's day is kept, per device. */
    static final String PREFERENCES = "tasks";

    static final String REVIEWED = "reviewed_at";

    /** The lists the chips switch between, in GTD's order. */
    private static final String[] LISTS = {
        Task.INBOX, Task.NEXT, Task.WAITING, Task.PROJECT, Task.SOMEDAY,
    };

    private static final int[] LABELS = {
        R.string.task_list_inbox,
        R.string.task_list_next,
        R.string.task_list_waiting,
        R.string.task_list_projects,
        R.string.task_list_someday,
    };

    private static final int[] ICONS = {
        R.drawable.ic_task_inbox,
        R.drawable.ic_arrow_forward,
        R.drawable.ic_task_waiting,
        R.drawable.ic_folder_open,
        R.drawable.ic_task_someday,
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

    /** Opened from the Inbox's Clarify button. */
    Clarify clarify;

    /** Opened from the Review chip. */
    WeeklyReview review;

    private ListHeader header;
    private final List<TextView> listChips = new ArrayList<>();
    private LinearLayout contextRow;
    private TextView clarifyButton;

    /** The list on screen, one of {@link #LISTS}. */
    private String shown = Task.NEXT;

    /** The context Next narrows to, null for every one. */
    private String context;

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

        LinearLayout chips = header.chips();
        for (int index = 0; index < LISTS.length; index++) {
            String target = LISTS[index];
            TextView chip = chip(chips, host.getString(LABELS[index]), ICONS[index]);
            chip.setOnClickListener(
                    view -> {
                        shown = target;
                        context = null;
                        render();
                        list.setSelection(0);
                    });
            listChips.add(chip);
        }
        TextView reviewChip =
                chip(chips, host.getString(R.string.task_review), R.drawable.ic_task_review);
        reviewChip.setOnClickListener(view -> review.open());

        // NOTE: a second row under the lists, Next's contexts, which the
        // header's one chip row has no room for.
        HorizontalScrollView scroll = new HorizontalScrollView(host);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(host.dp(16), host.dp(10), host.dp(16), 0);
        scroll.setClipToPadding(false);
        contextRow = new LinearLayout(host);
        scroll.addView(contextRow);
        ((ViewGroup) header.view).addView(scroll);

        clarifyButton = new TextView(host, null, 0, R.style.Chip);
        clarifyButton.setBackgroundResource(R.drawable.button_pill);
        clarifyButton.setTextColor(host.accentContrast());
        clarifyButton.setOnClickListener(view -> clarify.open(inbox()));
        LinearLayout.LayoutParams clarifyParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, host.dp(40));
        clarifyParams.setMargins(host.dp(16), host.dp(12), host.dp(16), 0);
        ((ViewGroup) header.view).addView(clarifyButton, clarifyParams);

        list.setAdapter(adapter);
        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    Row row = sections.row(header.rowAt(position));
                    if (row == null) {
                        return;
                    }
                    if (Task.INBOX.equals(row.stored.task.list())) {
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

    /** The list shown, as the tab opens on it. */
    public void show(String list) {
        shown = list;
        context = null;
        reload();
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

    /** The open next actions, due first, then by priority. */
    private static List<Task> nextActions(List<TaskStore.Stored> open) {
        List<TaskStore.Stored> next = new ArrayList<>();
        for (TaskStore.Stored stored : open) {
            if (Task.NEXT.equals(listOf(stored))) {
                next.add(stored);
            }
        }
        next.sort(TasksList::byDueThenPriority);
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
            if (!stored.task.closed() && Task.INBOX.equals(stored.task.list())) {
                inbox.add(stored);
            }
        }
        inbox.sort(Comparator.comparing(stored -> stored.task.created()));
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
        host.io.execute(
                () -> {
                    try {
                        writes.edit(stored, TaskEdit.at(TaskWrites.now()).withDone(done));
                    } catch (Exception error) {
                        Log.w("pimalaya", "task done failed: " + stored.event.id, error);
                        host.postAlive(() -> host.showError(error, R.string.task_save_failed));
                        return;
                    }
                    host.postAlive(this::reload);
                });
    }

    private void render() {
        List<TaskStore.Stored> open = open();
        int[] counts = new int[LISTS.length];
        for (TaskStore.Stored stored : open) {
            for (int index = 0; index < LISTS.length; index++) {
                if (LISTS[index].equals(listOf(stored))) {
                    counts[index]++;
                }
            }
        }
        for (int index = 0; index < LISTS.length; index++) {
            TextView chip = listChips.get(index);
            String label = host.getString(LABELS[index]);
            chip.setText(counts[index] > 0 && !Task.NEXT.equals(LISTS[index])
                    ? label + "  " + counts[index]
                    : label);
            draw(chip, ICONS[index], LISTS[index].equals(shown));
        }

        header.meta(reviewLine());
        renderContexts(open);

        int inboxCount = counts[0];
        if (inboxCounted != null) {
            inboxCounted.accept(inboxCount);
        }
        if (nextListed != null) {
            nextListed.accept(nextActions(open));
        }
        clarifyButton.setVisibility(
                Task.INBOX.equals(shown) && inboxCount > 0 ? View.VISIBLE : View.GONE);
        clarifyButton.setText(host.getString(R.string.task_clarify_count, inboxCount));

        List<Row> rows = rowsOf(open);
        sections.fill(rows, row -> row.section);
        adapter.notifyDataSetChanged();
        host.findViewById(R.id.tasks_empty).setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        ((TextView) host.findViewById(R.id.tasks_empty_text)).setText(emptyOf(shown));
    }

    /** The list a to-do shows in: a project's actions are in Next. */
    private static String listOf(TaskStore.Stored stored) {
        return stored.task.list();
    }

    private List<Row> rowsOf(List<TaskStore.Stored> open) {
        List<Row> rows = new ArrayList<>();
        switch (shown) {
            case Task.NEXT:
                {
                    List<TaskStore.Stored> next = new ArrayList<>();
                    for (TaskStore.Stored stored : open) {
                        if (Task.NEXT.equals(listOf(stored))
                                && (context == null
                                        || stored.task.contexts().contains(context))) {
                            next.add(stored);
                        }
                    }
                    next.sort(TasksList::byDueThenPriority);
                    String due = host.getString(R.string.task_section_due);
                    String actions = host.getString(R.string.task_section_next);
                    for (TaskStore.Stored stored : next) {
                        rows.add(new Row(stored, stored.task.due() != null ? due : actions));
                    }
                    break;
                }
            case Task.PROJECT:
                {
                    String stuck = host.getString(R.string.task_section_stuck);
                    String active = host.getString(R.string.task_section_active);
                    List<Row> stuckRows = new ArrayList<>();
                    List<Row> activeRows = new ArrayList<>();
                    for (TaskStore.Stored stored : open) {
                        if (!Task.PROJECT.equals(listOf(stored))) {
                            continue;
                        }
                        if (nextActionOf(stored) == null) {
                            stuckRows.add(new Row(stored, stuck));
                        } else {
                            activeRows.add(new Row(stored, active));
                        }
                    }
                    rows.addAll(stuckRows);
                    rows.addAll(activeRows);
                    break;
                }
            default:
                {
                    String section = host.getString(R.string.task_section_oldest);
                    List<TaskStore.Stored> listed = new ArrayList<>();
                    for (TaskStore.Stored stored : open) {
                        if (shown.equals(listOf(stored))) {
                            listed.add(stored);
                        }
                    }
                    listed.sort(Comparator.comparing(stored -> stored.task.created()));
                    for (TaskStore.Stored stored : listed) {
                        rows.add(new Row(stored, section));
                    }
                }
        }
        return rows;
    }

    /** Due ones first, soonest first; then by priority, 1 highest. */
    private static int byDueThenPriority(TaskStore.Stored left, TaskStore.Stored right) {
        boolean leftDue = left.task.due() != null;
        boolean rightDue = right.task.due() != null;
        if (leftDue != rightDue) {
            return leftDue ? -1 : 1;
        }
        if (leftDue) {
            int byDue = Long.compare(Zones.instant(left.task.due()), Zones.instant(right.task.due()));
            if (byDue != 0) {
                return byDue;
            }
        }
        return Integer.compare(rank(left.task.priority()), rank(right.task.priority()));
    }

    /** A priority's rank, undefined last (RFC 5545 3.8.1.9). */
    private static int rank(int priority) {
        return priority == 0 ? 10 : priority;
    }

    /** A project's first open next action, null when it has none. */
    TaskStore.Stored nextActionOf(TaskStore.Stored project) {
        TaskStore.Stored found = null;
        for (TaskStore.Stored stored : open()) {
            if (project.task.uid().equals(stored.task.parent())
                    && Task.NEXT.equals(stored.task.list())
                    && (found == null || byDueThenPriority(stored, found) < 0)) {
                found = stored;
            }
        }
        return found;
    }

    /** The actions of a project, done ones included. */
    int[] progressOf(TaskStore.Stored project) {
        int done = 0;
        int total = 0;
        for (TaskStore.Stored stored : all) {
            if (project.task.uid().equals(stored.task.parent())
                    && !"CANCELLED".equals(stored.task.status())) {
                total++;
                if (stored.task.closed()) {
                    done++;
                }
            }
        }
        return new int[] {done, total};
    }

    /** The project named by a UID, null when none is loaded. */
    TaskStore.Stored projectOf(String uid) {
        if (uid == null) {
            return null;
        }
        for (TaskStore.Stored stored : all) {
            if (uid.equals(stored.task.uid())) {
                return stored;
            }
        }
        return null;
    }

    /** Every open project, for a picker. */
    List<TaskStore.Stored> projects() {
        List<TaskStore.Stored> projects = new ArrayList<>();
        for (TaskStore.Stored stored : open()) {
            if (Task.PROJECT.equals(stored.task.list())) {
                projects.add(stored);
            }
        }
        return projects;
    }

    /** Every context an open to-do names, in first-seen order. */
    Set<String> contexts() {
        Set<String> contexts = new LinkedHashSet<>();
        for (TaskStore.Stored stored : open()) {
            contexts.addAll(stored.task.contexts());
        }
        return contexts;
    }

    private void renderContexts(List<TaskStore.Stored> open) {
        contextRow.removeAllViews();
        Set<String> contexts = new LinkedHashSet<>();
        for (TaskStore.Stored stored : open) {
            if (Task.NEXT.equals(listOf(stored))) {
                contexts.addAll(stored.task.contexts());
            }
        }
        ((View) contextRow.getParent())
                .setVisibility(
                        Task.NEXT.equals(shown) && !contexts.isEmpty() ? View.VISIBLE : View.GONE);
        if (context != null && !contexts.contains(context)) {
            context = null;
        }

        TextView every = chip(contextRow, host.getString(R.string.task_context_all), 0);
        draw(every, 0, context == null);
        every.setOnClickListener(
                view -> {
                    context = null;
                    render();
                });
        for (String name : contexts) {
            TextView chip = chip(contextRow, name, 0);
            draw(chip, 0, name.equals(context));
            chip.setOnClickListener(
                    view -> {
                        context = name.equals(context) ? null : name;
                        render();
                    });
        }
    }

    /** What the header says of the weekly review. */
    private String reviewLine() {
        SharedPreferences preferences = host.getSharedPreferences(PREFERENCES, 0);
        long at = preferences.getLong(REVIEWED, 0);
        if (at == 0) {
            return host.getString(R.string.task_review_never);
        }
        String day = Dates.day(host, at).toString();
        long days = (System.currentTimeMillis() - at) / 86_400_000L;
        return days >= REVIEW_DAYS
                ? host.getString(R.string.task_review_due, day)
                : host.getString(R.string.task_review_done, day);
    }

    private int emptyOf(String list) {
        switch (list) {
            case Task.INBOX:
                return R.string.tasks_empty_inbox;
            case Task.WAITING:
                return R.string.tasks_empty_waiting;
            case Task.PROJECT:
                return R.string.tasks_empty_projects;
            case Task.SOMEDAY:
                return R.string.tasks_empty_someday;
            default:
                return R.string.tasks_empty;
        }
    }

    private TextView chip(LinearLayout row, CharSequence label, int icon) {
        TextView chip = new TextView(host, null, 0, R.style.Chip);
        chip.setText(label);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, host.dp(34));
        params.setMarginEnd(host.dp(8));
        row.addView(chip, params);
        draw(chip, icon, false);
        return chip;
    }

    private void draw(TextView chip, int icon, boolean on) {
        chip.setSelected(on);
        chip.setBackgroundResource(on ? R.drawable.button_pill : R.drawable.button_tonal);
        int color =
                on ? host.accentContrast() : host.ui.resolveColor(android.R.attr.textColorPrimary);
        chip.setTextColor(color);
        if (icon == 0) {
            chip.setCompoundDrawablesRelative(null, null, null, null);
            return;
        }
        Drawable glyph = host.getDrawable(icon).mutate();
        glyph.setBounds(0, 0, host.dp(18), host.dp(18));
        chip.setCompoundDrawablesRelative(glyph, null, null, null);
        chip.setCompoundDrawableTintList(ColorStateList.valueOf(color));
    }

    /** The second line of a row: where it belongs. */
    private String lineOf(TaskStore.Stored stored) {
        Task task = stored.task;
        List<String> parts = new ArrayList<>();
        if (Task.PROJECT.equals(task.list())) {
            TaskStore.Stored next = nextActionOf(stored);
            int[] progress = progressOf(stored);
            if (next != null) {
                parts.add(host.getString(R.string.task_project_next, next.task.summary()));
            }
            parts.add(host.getString(R.string.task_project_progress, progress[0], progress[1]));
            return String.join(" · ", parts);
        }
        if (Task.WAITING.equals(task.list())) {
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
        if (Task.INBOX.equals(task.list())) {
            long at = TaskStore.capturedAt(task);
            if (at > 0) {
                parts.add(Dates.day(host, at).toString());
            }
            if (task.source() != null) {
                parts.add(host.getString(R.string.task_from_mail));
            }
            return String.join(" · ", parts);
        }
        parts.addAll(task.contexts());
        TaskStore.Stored project = projectOf(task.parent());
        if (project != null) {
            parts.add(project.task.summary());
        }
        if (parts.isEmpty()) {
            parts.add(stored.calendar.name);
        }
        return String.join(" · ", parts);
    }

    /** What the right of a row says: when it is due, or how long it waited. */
    private String whenOf(TaskStore.Stored stored) {
        Task task = stored.task;
        if (Task.WAITING.equals(task.list())) {
            long since = TaskStore.capturedAt(task);
            if (since <= 0) {
                return "";
            }
            long days = (System.currentTimeMillis() - since) / 86_400_000L;
            return host.getString(R.string.task_waiting_days, days);
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
            boolean late =
                    Task.WAITING.equals(task.list())
                            && TaskStore.capturedAt(task) > 0
                            && System.currentTimeMillis() - TaskStore.capturedAt(task)
                                    > 14 * 86_400_000L;
            when.setTextColor(
                    host.ui.resolveColor(
                            late ? android.R.attr.colorError : android.R.attr.textColorSecondary));

            TextView badge = view.findViewById(R.id.task_badge);
            if (Task.WAITING.equals(task.list()) && replied(row.stored)) {
                badge.setText(R.string.task_reply_received);
                badge.setVisibility(View.VISIBLE);
            } else if (Task.PROJECT.equals(task.list()) && nextActionOf(row.stored) == null) {
                badge.setText(R.string.task_no_next_action);
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }

            // NOTE: a capture is clarified rather than ticked off, and a
            // project closes when its outcome does, from its page.
            ImageButton done = view.findViewById(R.id.task_done);
            boolean tickable =
                    !Task.INBOX.equals(task.list()) && !Task.PROJECT.equals(task.list());
            done.setVisibility(tickable ? View.VISIBLE : View.INVISIBLE);
            done.setOnClickListener(tickable ? button -> done(row.stored, true) : null);
            return view;
        }
    }
}
