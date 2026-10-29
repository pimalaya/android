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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Tasks tab: the GTD lists over every calendar's to-dos.
 *
 * <p>Inbox, Next, Waiting For and Someday, each a filter of the one load
 * ({@link TaskStore}) across accounts and calendars, the state a reserved
 * category (pimdir GUIDE §17); a category filter narrows all four. A row's
 * tick closes it; a tap opens its page ({@link TaskPage}), or clarifies it
 * from the Inbox.
 */
public final class TasksList {
    /** Days after which a weekly review is due again. */
    static final long REVIEW_DAYS = 7;

    /** Where the last weekly review's day is kept, per device. */
    static final String PREFERENCES = "tasks";

    static final String REVIEWED = "reviewed_at";

    /** The lists the chips switch between, in GTD's order. */
    private static final String[] LISTS = {
        Task.INBOX, Task.NEXT, Task.WAITING, Task.SOMEDAY,
    };

    private static final int[] LABELS = {
        R.string.task_list_inbox,
        R.string.task_list_next,
        R.string.task_list_waiting,
        R.string.task_list_someday,
    };

    private static final int[] ICONS = {
        R.drawable.ic_task_inbox,
        R.drawable.ic_arrow_forward,
        R.drawable.ic_task_waiting,
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
    private LinearLayout categoryRow;
    private TextView clarifyButton;

    /** The list on screen, one of {@link #LISTS}. */
    private String shown = Task.NEXT;

    /** The category every list narrows to, null for every one. */
    private String category;

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
                        render();
                        list.setSelection(0);
                    });
            listChips.add(chip);
        }
        TextView reviewChip =
                chip(chips, host.getString(R.string.task_review), R.drawable.ic_task_review);
        reviewChip.setOnClickListener(view -> review.open());

        // NOTE: a second row under the lists, the categories, which the
        // header's one chip row has no room for. It narrows every list, so
        // switching lists keeps it.
        HorizontalScrollView scroll = new HorizontalScrollView(host);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(host.dp(16), host.dp(10), host.dp(16), 0);
        scroll.setClipToPadding(false);
        categoryRow = new LinearLayout(host);
        scroll.addView(categoryRow);
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
                    if (Task.INBOX.equals(shown)) {
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

    /** The list shown, as the tab opens on it, every category shown. */
    public void show(String list) {
        shown = list;
        category = null;
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
        renderCategories(open);
        int[] counts = new int[LISTS.length];
        int inboxCount = 0;
        for (TaskStore.Stored stored : open) {
            inboxCount += stored.task.in(Task.INBOX) ? 1 : 0;
            for (int index = 0; index < LISTS.length; index++) {
                if (stored.task.in(LISTS[index]) && narrowed(stored)) {
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

    /** Whether a to-do passes the category filter, compared folded. */
    private boolean narrowed(TaskStore.Stored stored) {
        return category == null || TaskOrder.carries(stored, category);
    }

    private List<Row> rowsOf(List<TaskStore.Stored> open) {
        List<TaskStore.Stored> listed = new ArrayList<>();
        for (TaskStore.Stored stored : open) {
            if (stored.task.in(shown) && narrowed(stored)) {
                listed.add(stored);
            }
        }
        List<Row> rows = new ArrayList<>();
        if (Task.NEXT.equals(shown)) {
            listed.sort(TaskOrder.NEXT);
            String due = host.getString(R.string.task_section_due);
            String actions = host.getString(R.string.task_section_next);
            for (TaskStore.Stored stored : nested(listed)) {
                rows.add(new Row(stored, sectionOf(stored, listed, due, actions)));
            }
            return rows;
        }
        listed.sort(TaskOrder.CAPTURED);
        String section = host.getString(R.string.task_section_oldest);
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

    private void renderCategories(List<TaskStore.Stored> open) {
        categoryRow.removeAllViews();
        java.util.Collection<String> categories = TaskOrder.categories(open);
        ((View) categoryRow.getParent())
                .setVisibility(categories.isEmpty() ? View.GONE : View.VISIBLE);
        if (category != null && categories.stream().noneMatch(this::picked)) {
            category = null;
        }

        TextView every = chip(categoryRow, host.getString(R.string.task_context_all), 0);
        draw(every, 0, category == null);
        every.setOnClickListener(
                view -> {
                    category = null;
                    render();
                });
        for (String name : categories) {
            TextView chip = chip(categoryRow, name, 0);
            draw(chip, 0, picked(name));
            chip.setOnClickListener(
                    view -> {
                        category = picked(name) ? null : name;
                        render();
                    });
        }
    }

    /** Whether {@code name} is the category picked, compared folded. */
    private boolean picked(String name) {
        return category != null && TaskOrder.fold(category).equals(TaskOrder.fold(name));
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
        parts.addAll(task.categories());
        TaskStore.Stored parent = taskOf(task.parent());
        if (parent != null) {
            parts.add(parent.task.summary());
        }
        if (parts.isEmpty()) {
            parts.add(stored.calendar.name);
        }
        return String.join(" · ", parts);
    }

    /** What the right of a row says: when it is due, or how long it waited. */
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
            } else {
                badge.setVisibility(View.GONE);
            }

            // NOTE: a capture is clarified rather than ticked off.
            ImageButton done = view.findViewById(R.id.task_done);
            boolean tickable = !Task.INBOX.equals(shown);
            done.setVisibility(tickable ? View.VISIBLE : View.INVISIBLE);
            done.setOnClickListener(tickable ? button -> done(row.stored, true) : null);
            return view;
        }
    }
}
