package org.pimalaya.task;

import android.content.SharedPreferences;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.pimalaya.R;
import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.calendar.Zones;
import org.pimalaya.client.Task;
import org.pimalaya.client.TaskEdit;
import org.pimalaya.ui.CardSections;
import org.pimalaya.ui.Dates;
import org.pimalaya.ui.FilterPanel;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.ListHeader;
import org.pimalaya.ui.Panels;
import org.pimalaya.ui.SectionsAdapter;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Tasks tab: the tasks of every calendar the filter shows, the GTD lists
 * among its filters (cairn/changes/tasks-for-everyone).
 *
 * <p>With no filter on, the open tasks, then the done ones dimmed, then the
 * cancelled ones dimmed and struck through. One inline filter panel
 * (cairn/changes/one-filter-panel): Categories keeps the GTD lists on ({@code
 * CATEGORIES}, pimdir GUIDE §17), Status the states on ({@code STATUS}),
 * Projects the tasks carrying any category on, Due compares the {@code DUE}
 * day, Calendars hides some of the calendars the calendar filter syncs, for
 * this view alone. A row's tick
 * closes it, or reopens it once closed; a tap opens its page ({@link
 * TaskPage}), or clarifies it from the Inbox.
 */
public final class TasksList {
    /** Days after which a weekly review is due again. */
    static final long REVIEW_DAYS = 7;

    /** Where the last weekly review's day is kept, per device. */
    static final String PREFERENCES = "tasks";

    static final String REVIEWED = "reviewed_at";

    /** Where the filters are kept across restarts, in {@link #PREFERENCES}. */
    private static final String FILTER_LISTS = "filter_lists";

    private static final String FILTER_STATES = "filter_states";
    private static final String FILTER_CATEGORIES = "filter_categories";
    private static final String FILTER_DUE = "filter_due";
    private static final String FILTER_DAY = "filter_day";
    private static final String FILTER_HIDDEN = "filter_hidden";

    /** The GTD lists Categories offers, reserved categories (GUIDE §17). */
    private static final String[] LISTS = {
        Task.INBOX, Task.WAITING, Task.SOMEDAY,
    };

    private static final int[] LIST_LABELS = {
        R.string.task_list_inbox, R.string.task_list_waiting, R.string.task_list_someday,
    };

    /** The states Status offers ({@link TaskOrder#stateOf}). */
    private static final String[] STATES = {
        TaskOrder.TODO, TaskOrder.PROGRESS, TaskOrder.DONE, TaskOrder.CANCELLED,
    };

    private static final int[] STATE_LABELS = {
        R.string.task_status_todo,
        R.string.task_list_progress,
        R.string.task_list_done,
        R.string.task_list_cancelled,
    };

    /** What a closed task's row is drawn at, as a mail row with no body. */
    private static final float CLOSED_ALPHA = 0.5f;

    private static final String[] DUES = {
        TaskOrder.DUE_ANY, TaskOrder.DUE_BEFORE, TaskOrder.DUE_ON, TaskOrder.DUE_AFTER,
    };

    private static final int[] DUE_LABELS = {
        R.string.filter_any_time,
        R.string.filter_due_before,
        R.string.filter_due_on,
        R.string.filter_due_after,
    };

    /** The own categories Categories shows before "Show all". */
    private static final int CATEGORIES_SHOWN = 6;

    private final Host host;
    private final TaskStore store;
    private final TaskWrites writes;
    private final CardSections<Row> sections = new CardSections<>();
    private final Adapter adapter = new Adapter();

    /** The tasks selected, by {@link #keyOf}, in the order picked. */
    private final Map<String, TaskStore.Stored> selected = new LinkedHashMap<>();

    /** The selection's actions. */
    private TaskBatch batch;

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
    private FilterPanel filters;

    /**
     * What the filters keep: the ones applied to the rows, or a draft the
     * panel edits until Apply.
     */
    private static final class Filter {
        /** The GTD lists on, of {@link #LISTS}. */
        final Set<String> lists = new LinkedHashSet<>();

        /** The states on, of {@link #STATES}. */
        final Set<String> states = new LinkedHashSet<>();

        /** The own categories on, folded. */
        final Set<String> categories = new LinkedHashSet<>();

        /** How Due compares, one of {@link #DUES}. */
        String dueMode = TaskOrder.DUE_ANY;

        /** The day Due compares against. */
        LocalDate dueDay = LocalDate.now();

        /** The calendars hidden from the view, by collection id. */
        final Set<String> hidden = new HashSet<>();

        Filter copy() {
            Filter copy = new Filter();
            copy.lists.addAll(lists);
            copy.states.addAll(states);
            copy.categories.addAll(categories);
            copy.dueMode = dueMode;
            copy.dueDay = dueDay;
            copy.hidden.addAll(hidden);
            return copy;
        }

        /** Every criterion back to its default, none narrowing. */
        void clear() {
            lists.clear();
            states.clear();
            categories.clear();
            dueMode = TaskOrder.DUE_ANY;
            hidden.clear();
        }

        /** Whether any criterion narrows. */
        boolean active() {
            return !lists.isEmpty()
                    || !states.isEmpty()
                    || !categories.isEmpty()
                    || !TaskOrder.DUE_ANY.equals(dueMode)
                    || !hidden.isEmpty();
        }
    }

    /** The filters the rows follow, kept across restarts. */
    private final Filter applied = new Filter();

    /** The filters the open panel edits, null while it is folded. */
    private Filter draft;

    /** The settled search, lower-cased. */
    private String query = "";

    /** Whether Categories lists every category, past {@link #CATEGORIES_SHOWN}. */
    private boolean allCategories;

    /** The Inbox count of the last draw, for the bar's Clarify button. */
    private int inboxCount;

    /** Every to-do of the last load. */
    private List<TaskStore.Stored> all = new ArrayList<>();

    /** The calendars Calendars offers, as of the last load. */
    private List<EventStore.StoredCalendar> calendars = List.of();

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
        header.search(
                R.string.tasks_search,
                settled -> {
                    query = settled;
                    render();
                });
        filters = new FilterPanel(host, header, this::render, new Drafted());
        restore();

        list.setAdapter(adapter);
        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    Row row = sections.row(header.rowAt(position));
                    if (row == null) {
                        return;
                    }
                    if (isSelectionMode()) {
                        toggle(row.stored);
                    } else if (Task.INBOX.equals(only()) && !row.stored.task.closed()) {
                        clarify.open(List.of(row.stored));
                    } else {
                        page.open(row.stored);
                    }
                });
        list.setOnItemLongClickListener(
                (parent, view, position, id) -> {
                    Row row = sections.row(header.rowAt(position));
                    if (row == null) {
                        return false;
                    }
                    toggle(row.stored);
                    return true;
                });

        batch = new TaskBatch(host, this, writes);
        LinearLayout actions = host.findViewById(R.id.tasks_actions);
        action(actions, R.drawable.ic_section_label, R.string.task_batch_category, batch::category);
        action(actions, R.drawable.ic_task_done, R.string.task_field_status, batch::status);
        action(actions, R.drawable.ic_domain_calendar, R.string.task_field_due, batch::due);
        action(actions, R.drawable.ic_move_item, R.string.task_batch_move, batch::move);
        action(actions, R.drawable.ic_content_copy, R.string.task_batch_copy, batch::copy);
        action(actions, R.drawable.ic_delete_bar, R.string.task_batch_delete, batch::delete);
    }

    /** One action of the selection's bar: its icon over its label. */
    private void action(
            LinearLayout bar,
            int icon,
            int label,
            java.util.function.Consumer<List<TaskStore.Stored>> act) {
        int size = host.getResources().getDimensionPixelSize(R.dimen.item_icon);
        ImageView glyph = new ImageView(host);
        glyph.setImageResource(icon);
        glyph.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        host.resolveColor(android.R.attr.textColorPrimary)));
        TextView name = new TextView(host);
        name.setText(label);
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        name.setTextColor(host.resolveColor(android.R.attr.textColorPrimary));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout item = new LinearLayout(host);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(android.view.Gravity.CENTER);
        item.setBackgroundResource(
                host.ui.resolveAttr(android.R.attr.selectableItemBackgroundBorderless));
        item.setContentDescription(host.getString(label));
        item.addView(glyph, new LinearLayout.LayoutParams(size, size));
        LinearLayout.LayoutParams nameParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = host.dp(6);
        item.addView(name, nameParams);
        item.setOnClickListener(view -> act.accept(new ArrayList<>(selected.values())));
        bar.addView(
                item,
                new LinearLayout.LayoutParams(
                        0, host.getResources().getDimensionPixelSize(R.dimen.nav_height), 1f));
    }

    /** Whether a selection is under way. */
    public boolean isSelectionMode() {
        return !selected.isEmpty();
    }

    /** What a task is known by in the selection: its calendar and its item. */
    private static String keyOf(TaskStore.Stored stored) {
        return stored.event.collectionId + "\n" + stored.event.id;
    }

    /** Adds a task to the selection, or takes it out; empty, it ends. */
    private void toggle(TaskStore.Stored stored) {
        boolean was = isSelectionMode();
        if (selected.remove(keyOf(stored)) == null) {
            selected.put(keyOf(stored), stored);
        }
        adapter.notifyDataSetChanged();
        if (was && !isSelectionMode()) {
            host.applyChrome(Panels.TASKS);
        } else {
            updateSelectionUi();
        }
    }

    /** Selects every row listed, or none when all already are. */
    public void toggleSelectAll() {
        List<Row> rows = rowsOf();
        if (selected.size() == rows.size()) {
            endSelection();
            return;
        }
        for (Row row : rows) {
            selected.put(keyOf(row.stored), row.stored);
        }
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    /** Ends the selection and gives the bar and the navigation back. */
    public void endSelection() {
        if (selected.isEmpty()) {
            return;
        }
        selected.clear();
        adapter.notifyDataSetChanged();
        if (host.screen == Panels.TASKS) {
            host.applyChrome(Panels.TASKS);
        }
    }

    /**
     * The bar while a selection runs: the cross, the count and select-all;
     * the actions in the navigation's place.
     */
    public void updateSelectionUi() {
        if (host.screen != Panels.TASKS || !isSelectionMode()) {
            return;
        }
        TextView title = host.findViewById(R.id.bar_title);
        title.animate().cancel();
        title.setText(host.getString(R.string.selected_count, selected.size()));
        title.setAlpha(1f);
        for (int id :
                new int[] {
                    R.id.bar_menu, R.id.tasks_review, R.id.tasks_clarify, R.id.fab_extended,
                    R.id.bottom_nav,
                }) {
            host.findViewById(id).setVisibility(View.GONE);
        }
        host.findViewById(R.id.selection_close).setVisibility(View.VISIBLE);
        host.findViewById(R.id.selection_all_slot).setVisibility(View.VISIBLE);
        ((android.widget.CheckBox) host.findViewById(R.id.selection_all))
                .setChecked(selected.size() == rowsOf().size());
        host.findViewById(R.id.tasks_actions).setVisibility(View.VISIBLE);
    }

    /** Every category a task names, one per folded spelling. */
    List<String> everyCategory() {
        return new ArrayList<>(TaskOrder.categories(all));
    }

    /** The list's header, which the bar's title follows. */
    public ListHeader header() {
        return header;
    }

    /**
     * The tab opened on the open tasks of {@code list}, every other filter
     * off; {@link Task#NEXT} for every open task.
     */
    public void show(String list) {
        applied.lists.clear();
        if (!Task.NEXT.equals(list)) {
            applied.lists.add(list);
        }
        applied.states.clear();
        applied.states.add(TaskOrder.TODO);
        applied.states.add(TaskOrder.PROGRESS);
        applied.categories.clear();
        applied.dueMode = TaskOrder.DUE_ANY;
        applied.hidden.clear();
        save();
        reload();
    }

    /** The one GTD list on, null for none or several. */
    private String only() {
        return applied.lists.size() == 1 ? applied.lists.iterator().next() : null;
    }

    /** Reads the filters kept by {@link #save}. */
    private void restore() {
        SharedPreferences kept = host.getSharedPreferences(PREFERENCES, 0);
        applied.lists.addAll(kept.getStringSet(FILTER_LISTS, Set.of()));
        applied.states.addAll(kept.getStringSet(FILTER_STATES, Set.of()));
        applied.categories.addAll(kept.getStringSet(FILTER_CATEGORIES, Set.of()));
        applied.dueMode = kept.getString(FILTER_DUE, TaskOrder.DUE_ANY);
        long day = kept.getLong(FILTER_DAY, Long.MIN_VALUE);
        if (day != Long.MIN_VALUE) {
            applied.dueDay = LocalDate.ofEpochDay(day);
        }
        applied.hidden.addAll(kept.getStringSet(FILTER_HIDDEN, Set.of()));
    }

    /** Keeps the filters across restarts. */
    private void save() {
        host.getSharedPreferences(PREFERENCES, 0)
                .edit()
                .putStringSet(FILTER_LISTS, new HashSet<>(applied.lists))
                .putStringSet(FILTER_STATES, new HashSet<>(applied.states))
                .putStringSet(FILTER_CATEGORIES, new HashSet<>(applied.categories))
                .putString(FILTER_DUE, applied.dueMode)
                .putLong(FILTER_DAY, applied.dueDay.toEpochDay())
                .putStringSet(FILTER_HIDDEN, new HashSet<>(applied.hidden))
                .apply();
    }

    /** A filter changed: kept, then the list redrawn. */
    private void changed() {
        save();
        render();
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
                    List<EventStore.StoredCalendar> offered = store.calendars(host);
                    Set<String> answered = store.replied(read);
                    host.postAlive(
                            () -> {
                                if (asked != generation) {
                                    return;
                                }
                                all = read;
                                calendars = offered;
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

    private void render() {
        // NOTE: a reload keeps the selected tasks still there, as loaded now.
        if (!selected.isEmpty()) {
            Map<String, TaskStore.Stored> fresh = new HashMap<>();
            for (TaskStore.Stored stored : all) {
                fresh.put(keyOf(stored), stored);
            }
            selected.keySet().retainAll(fresh.keySet());
            selected.replaceAll((key, stored) -> fresh.get(key));
            if (selected.isEmpty() && host.screen == Panels.TASKS) {
                host.applyChrome(Panels.TASKS);
            }
        }
        List<TaskStore.Stored> open = open();
        inboxCount = 0;
        for (TaskStore.Stored stored : open) {
            inboxCount += stored.task.in(Task.INBOX) ? 1 : 0;
        }
        drawFilters();

        if (inboxCounted != null) {
            inboxCounted.accept(inboxCount);
        }
        if (nextListed != null) {
            nextListed.accept(nextActions(open));
        }
        if (host.screen == Panels.TASKS) {
            host.findViewById(R.id.tasks_clarify)
                    .setVisibility(inboxCount > 0 ? View.VISIBLE : View.GONE);
            ((ImageButton) host.findViewById(R.id.tasks_review))
                    .setImageTintList(
                            android.content.res.ColorStateList.valueOf(
                                    host.resolveColor(
                                            reviewDue()
                                                    ? android.R.attr.colorAccent
                                                    : android.R.attr.textColorPrimary)));
        }

        List<Row> rows = rowsOf();
        header.meta(
                host.getResources()
                        .getQuantityString(R.plurals.tasks_meta, rows.size(), rows.size()));
        sections.fill(rows, row -> row.section);
        adapter.notifyDataSetChanged();
        host.findViewById(R.id.tasks_empty).setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        ((TextView) host.findViewById(R.id.tasks_empty_text)).setText(emptyOf());
        updateSelectionUi();
    }

    /**
     * Whether a to-do passes Categories: in any GTD list on or carrying any
     * category on, compared folded; every to-do with none on.
     */
    private boolean inLists(Filter f, TaskStore.Stored stored) {
        if (f.lists.isEmpty() && f.categories.isEmpty()) {
            return true;
        }
        for (String list : f.lists) {
            if (stored.task.in(list)) {
                return true;
            }
        }
        return !f.categories.isEmpty() && TaskOrder.carriesAny(stored, f.categories);
    }

    /** Whether a to-do passes Status: its state on, or none on. */
    private boolean inStates(Filter f, TaskStore.Stored stored) {
        return f.states.isEmpty() || f.states.contains(TaskOrder.stateOf(stored.task));
    }

    /**
     * Whether a to-do passes every filter but Categories and Status: its due
     * day, its calendar and the search.
     */
    private boolean narrowed(Filter f, TaskStore.Stored stored) {
        return TaskOrder.dueMatches(stored.task, f.dueMode, f.dueDay)
                && (stored.calendar == null || !f.hidden.contains(stored.calendar.id))
                && searched(stored);
    }

    /** Whether the search finds a to-do in its summary or a category. */
    private boolean searched(TaskStore.Stored stored) {
        if (query.isEmpty()) {
            return true;
        }
        if (stored.task.summary().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        for (String category : stored.task.categories()) {
            if (TaskOrder.fold(category).contains(query)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The rows: the open tasks first, due ones then undated, or oldest first
     * under one of Inbox, Waiting or Someday alone; then the done ones, the
     * last done first; then the cancelled ones, the last captured first.
     */
    private List<Row> rowsOf() {
        List<TaskStore.Stored> open = new ArrayList<>();
        List<TaskStore.Stored> done = new ArrayList<>();
        List<TaskStore.Stored> cancelled = new ArrayList<>();
        for (TaskStore.Stored stored : all) {
            if (!inLists(applied, stored)
                    || !inStates(applied, stored)
                    || !narrowed(applied, stored)) {
                continue;
            }
            String state = TaskOrder.stateOf(stored.task);
            if (TaskOrder.DONE.equals(state)) {
                done.add(stored);
            } else if (TaskOrder.CANCELLED.equals(state)) {
                cancelled.add(stored);
            } else {
                open.add(stored);
            }
        }

        List<Row> rows = new ArrayList<>();
        if (only() == null) {
            open.sort(TaskOrder.NEXT);
            String due = host.getString(R.string.task_section_due);
            String actions = host.getString(R.string.task_section_undated);
            for (TaskStore.Stored stored : nested(open)) {
                rows.add(new Row(stored, sectionOf(stored, open, due, actions)));
            }
        } else {
            open.sort(TaskOrder.CAPTURED);
            String oldest = host.getString(R.string.task_section_oldest);
            for (TaskStore.Stored stored : nested(open)) {
                rows.add(new Row(stored, oldest));
            }
        }
        done.sort(TaskOrder.COMPLETED);
        String doneSection = host.getString(R.string.task_list_done);
        for (TaskStore.Stored stored : nested(done)) {
            rows.add(new Row(stored, doneSection));
        }
        cancelled.sort(TaskOrder.RECENT);
        String cancelledSection = host.getString(R.string.task_list_cancelled);
        for (TaskStore.Stored stored : nested(cancelled)) {
            rows.add(new Row(stored, cancelledSection));
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
     * Draws the filter panel, Calendars, Status, Categories and Due, and the
     * pills standing for what is on.
     */
    private void drawFilters() {
        Filter f = edited();
        filters.begin();
        drawCalendars();
        drawChoices(
                "states",
                R.string.filter_status,
                STATES,
                STATE_LABELS,
                f.states,
                // NOTE: the closed states only grow, so they count nothing.
                key -> TaskOrder.TODO.equals(key) || TaskOrder.PROGRESS.equals(key),
                key ->
                        countWhere(
                                f,
                                stored ->
                                        key.equals(TaskOrder.stateOf(stored.task))
                                                && inLists(f, stored)));
        drawCategories();
        drawDue();
        filters.end(applied.active());
    }

    /** The filters the panel shows: its draft while open, else the applied. */
    private Filter edited() {
        return draft != null ? draft : applied;
    }

    /** The panel's draft, taken on opening, kept by Apply. */
    private final class Drafted implements FilterPanel.Draft {
        @Override
        public void open() {
            draft = applied.copy();
        }

        @Override
        public void apply() {
            Filter kept = draft;
            draft = null;
            applied.clear();
            applied.lists.addAll(kept.lists);
            applied.states.addAll(kept.states);
            applied.categories.addAll(kept.categories);
            applied.dueMode = kept.dueMode;
            applied.dueDay = kept.dueDay;
            applied.hidden.addAll(kept.hidden);
            save();
        }

        @Override
        public void cancel() {
            draft = null;
        }

        @Override
        public void reset() {
            draft.clear();
        }
    }

    /**
     * Categories: Inbox, Waiting and Someday, then every category a task
     * carries, the ones on first, any number on; each but the Inbox counting
     * its tasks under the other filters (the bar's Clarify says the Inbox
     * holds items).
     */
    private void drawCategories() {
        Filter f = edited();
        Map<String, String> every = new LinkedHashMap<>();
        for (String name : TaskOrder.categories(all)) {
            every.putIfAbsent(TaskOrder.fold(name), name);
        }
        // NOTE: a category no task carries any more is turned off, so
        // nothing invisible narrows the lists; not before the first load,
        // which would drop the kept ones.
        if (loaded) {
            f.categories.retainAll(every.keySet());
        }

        List<String> onLabels = new ArrayList<>();
        for (int index = 0; index < LISTS.length; index++) {
            if (f.lists.contains(LISTS[index])) {
                onLabels.add(host.getString(LIST_LABELS[index]));
            }
        }
        List<String> own = new ArrayList<>(f.categories);
        for (String folded : f.categories) {
            onLabels.add(every.getOrDefault(folded, folded));
        }
        for (String folded : every.keySet()) {
            if (!f.categories.contains(folded)) {
                own.add(folded);
            }
        }
        String value =
                onLabels.isEmpty()
                        ? host.getString(R.string.filter_all)
                        : onLabels.size() <= 2
                                ? String.join(", ", onLabels)
                                : host.getResources()
                                        .getQuantityString(
                                                R.plurals.filter_categories_count,
                                                onLabels.size(),
                                                onLabels.size());

        filters.section(
                "categories",
                R.string.filter_categories,
                value,
                !onLabels.isEmpty(),
                list -> {
                    for (int index = 0; index < LISTS.length; index++) {
                        String key = LISTS[index];
                        boolean ticked = f.lists.contains(key);
                        filters.check(
                                list,
                                host.getString(LIST_LABELS[index]),
                                Task.INBOX.equals(key)
                                        ? null
                                        : String.valueOf(
                                                countWhere(
                                                        f,
                                                        stored ->
                                                                stored.task.in(key)
                                                                        && inStates(f, stored))),
                                ticked ? FilterPanel.Box.ON : FilterPanel.Box.OFF,
                                false,
                                0,
                                () -> {
                                    if (ticked) {
                                        f.lists.remove(key);
                                    } else {
                                        f.lists.add(key);
                                    }
                                    changed();
                                });
                    }
                    int count =
                            allCategories ? own.size() : Math.min(CATEGORIES_SHOWN, own.size());
                    for (String folded : own.subList(0, count)) {
                        boolean ticked = f.categories.contains(folded);
                        Set<String> alone = Set.of(folded);
                        filters.check(
                                list,
                                every.getOrDefault(folded, folded),
                                String.valueOf(
                                        countWhere(
                                                f,
                                                stored ->
                                                        TaskOrder.carriesAny(stored, alone)
                                                                && inStates(f, stored))),
                                ticked ? FilterPanel.Box.ON : FilterPanel.Box.OFF,
                                false,
                                0,
                                () -> {
                                    if (ticked) {
                                        f.categories.remove(folded);
                                    } else {
                                        f.categories.add(folded);
                                    }
                                    changed();
                                });
                    }
                    if (count < own.size()) {
                        filters.action(
                                list,
                                host.getString(R.string.filter_show_all, own.size()),
                                () -> {
                                    allCategories = true;
                                    render();
                                });
                    }
                });

        for (int index = 0; index < LISTS.length; index++) {
            String key = LISTS[index];
            if (f.lists.contains(key)) {
                filters.pill(
                        host.getString(LIST_LABELS[index]),
                        () -> {
                            f.lists.remove(key);
                            changed();
                        });
            }
        }
        for (String folded : new ArrayList<>(f.categories)) {
            filters.pill(
                    every.getOrDefault(folded, folded),
                    () -> {
                        f.categories.remove(folded);
                        changed();
                    });
        }
    }

    /**
     * A section of checkboxes over {@code keys}, the ones on in {@code on},
     * none on narrowing nothing; the ones {@code counted} say how many tasks
     * they would list.
     */
    private void drawChoices(
            String section,
            int name,
            String[] keys,
            int[] labels,
            Set<String> on,
            java.util.function.Predicate<String> counted,
            java.util.function.ToIntFunction<String> count) {
        List<String> onLabels = new ArrayList<>();
        for (int index = 0; index < keys.length; index++) {
            if (on.contains(keys[index])) {
                onLabels.add(host.getString(labels[index]));
            }
        }
        filters.section(
                section,
                name,
                onLabels.isEmpty()
                        ? host.getString(R.string.filter_all)
                        : String.join(", ", onLabels),
                !onLabels.isEmpty(),
                list -> {
                    for (int index = 0; index < keys.length; index++) {
                        String key = keys[index];
                        boolean ticked = on.contains(key);
                        filters.check(
                                list,
                                host.getString(labels[index]),
                                counted.test(key) ? String.valueOf(count.applyAsInt(key)) : null,
                                ticked ? FilterPanel.Box.ON : FilterPanel.Box.OFF,
                                false,
                                0,
                                () -> {
                                    if (ticked) {
                                        on.remove(key);
                                    } else {
                                        on.add(key);
                                    }
                                    changed();
                                });
                    }
                });
        for (int index = 0; index < keys.length; index++) {
            String key = keys[index];
            if (on.contains(key)) {
                filters.pill(
                        host.getString(labels[index]),
                        () -> {
                            on.remove(key);
                            changed();
                        });
            }
        }
    }

    /** The tasks {@code kept} keeps among those the other filters pass. */
    private int countWhere(Filter f, java.util.function.Predicate<TaskStore.Stored> kept) {
        int count = 0;
        for (TaskStore.Stored stored : all) {
            if (kept.test(stored) && narrowed(f, stored)) {
                count++;
            }
        }
        return count;
    }

    /** Due: any time, or before, on or after a day. */
    private void drawDue() {
        Filter f = edited();
        boolean on = !TaskOrder.DUE_ANY.equals(f.dueMode);
        String day =
                Dates.date(
                        host,
                        f.dueDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
        String value =
                on
                        ? host.getString(DUE_LABELS[indexOf(DUES, f.dueMode)]) + " " + day
                        : host.getString(R.string.filter_any_time);
        filters.section(
                "due",
                R.string.task_field_due,
                value,
                on,
                list -> {
                    for (int index = 0; index < DUES.length; index++) {
                        String mode = DUES[index];
                        filters.radio(
                                list,
                                host.getString(DUE_LABELS[index]),
                                null,
                                mode.equals(f.dueMode),
                                () -> {
                                    f.dueMode = mode;
                                    changed();
                                });
                    }
                    if (on) {
                        filters.action(list, day, this::pickDueDay);
                    }
                });
        if (on) {
            int pill =
                    TaskOrder.DUE_BEFORE.equals(f.dueMode)
                            ? R.string.filter_due_before_pill
                            : TaskOrder.DUE_ON.equals(f.dueMode)
                                    ? R.string.filter_due_on_pill
                                    : R.string.filter_due_after_pill;
            filters.pill(
                    host.getString(pill, day),
                    () -> {
                        f.dueMode = TaskOrder.DUE_ANY;
                        changed();
                    });
        }
    }

    /** Asks for Due's day; clearing it turns Due off. */
    private void pickDueDay() {
        Filter f = edited();
        TaskDialogs.date(
                host,
                String.format(
                        Locale.US,
                        "%04d%02d%02d",
                        f.dueDay.getYear(),
                        f.dueDay.getMonthValue(),
                        f.dueDay.getDayOfMonth()),
                picked -> {
                    if (picked.isEmpty()) {
                        f.dueMode = TaskOrder.DUE_ANY;
                    } else {
                        f.dueDay =
                                LocalDate.of(
                                        Integer.parseInt(picked.substring(0, 4)),
                                        Integer.parseInt(picked.substring(4, 6)),
                                        Integer.parseInt(picked.substring(6, 8)));
                    }
                    changed();
                });
    }

    /**
     * Calendars: every calendar the calendar filter syncs and that takes
     * to-dos, then any other holding one, under their accounts, any of them
     * hidden from this view alone.
     */
    private void drawCalendars() {
        Filter f = edited();
        List<EventStore.StoredCalendar> offered = new ArrayList<>(calendars);
        for (TaskStore.Stored stored : all) {
            if (stored.calendar != null) {
                offered.add(stored.calendar);
            }
        }
        Map<String, List<EventStore.StoredCalendar>> byAccount = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (EventStore.StoredCalendar calendar : offered) {
            if (names.put(calendar.id, calendar.name) == null) {
                byAccount
                        .computeIfAbsent(calendar.accountEmail, account -> new ArrayList<>())
                        .add(calendar);
            }
        }
        if (loaded) {
            f.hidden.retainAll(names.keySet());
        }
        if (names.isEmpty()) {
            return;
        }

        String value = calendarsValue(byAccount, names);
        filters.section(
                "calendars",
                R.string.filter_calendars,
                value,
                !f.hidden.isEmpty(),
                list -> {
                    for (Map.Entry<String, List<EventStore.StoredCalendar>> account :
                            byAccount.entrySet()) {
                        List<String> ids = new ArrayList<>();
                        for (EventStore.StoredCalendar calendar : account.getValue()) {
                            ids.add(calendar.id);
                        }
                        int hidden = 0;
                        for (String id : ids) {
                            hidden += f.hidden.contains(id) ? 1 : 0;
                        }
                        FilterPanel.Box box =
                                hidden == 0
                                        ? FilterPanel.Box.ON
                                        : hidden == ids.size()
                                                ? FilterPanel.Box.OFF
                                                : FilterPanel.Box.PARTIAL;
                        filters.check(
                                list,
                                account.getKey(),
                                box,
                                true,
                                0,
                                () -> {
                                    if (box == FilterPanel.Box.OFF) {
                                        f.hidden.removeAll(ids);
                                    } else {
                                        f.hidden.addAll(ids);
                                    }
                                    changed();
                                });
                        for (EventStore.StoredCalendar calendar : account.getValue()) {
                            boolean hiddenOne = f.hidden.contains(calendar.id);
                            filters.check(
                                    list,
                                    calendar.name,
                                    hiddenOne ? FilterPanel.Box.OFF : FilterPanel.Box.ON,
                                    false,
                                    host.dp(24),
                                    () -> {
                                        if (hiddenOne) {
                                            f.hidden.remove(calendar.id);
                                        } else {
                                            f.hidden.add(calendar.id);
                                        }
                                        changed();
                                    });
                        }
                    }
                });
        if (!f.hidden.isEmpty()) {
            filters.pill(
                    value,
                    () -> {
                        f.hidden.clear();
                        changed();
                    });
        }
    }

    /**
     * What Calendars says: all, the one calendar shown, the one account
     * shown whole, else how many of how many.
     */
    private String calendarsValue(
            Map<String, List<EventStore.StoredCalendar>> byAccount, Map<String, String> names) {
        Filter f = edited();
        if (f.hidden.isEmpty()) {
            return host.getString(R.string.filter_all);
        }
        List<String> shownIds = new ArrayList<>();
        for (String id : names.keySet()) {
            if (!f.hidden.contains(id)) {
                shownIds.add(id);
            }
        }
        if (shownIds.size() == 1) {
            return names.get(shownIds.get(0));
        }
        for (Map.Entry<String, List<EventStore.StoredCalendar>> account : byAccount.entrySet()) {
            List<String> ids = new ArrayList<>();
            for (EventStore.StoredCalendar calendar : account.getValue()) {
                ids.add(calendar.id);
            }
            if (!shownIds.isEmpty() && ids.containsAll(shownIds) && shownIds.containsAll(ids)) {
                return account.getKey();
            }
        }
        return host.getString(R.string.filter_count_of, shownIds.size(), names.size());
    }

    private static int indexOf(String[] keys, String key) {
        for (int index = 0; index < keys.length; index++) {
            if (keys[index].equals(key)) {
                return index;
            }
        }
        return 0;
    }

    /**
     * Whether a weekly review is due: a week after the last one, never before
     * a first one is finished.
     */
    private boolean reviewDue() {
        long at = host.getSharedPreferences(PREFERENCES, 0).getLong(REVIEWED, 0);
        return at != 0 && (System.currentTimeMillis() - at) / 86_400_000L >= REVIEW_DAYS;
    }

    /**
     * What an empty list says: of the one state on, else of the one GTD list
     * on, else of nothing to do.
     */
    private int emptyOf() {
        String state = applied.states.size() == 1 ? applied.states.iterator().next() : null;
        switch (state != null ? state : only() != null ? only() : "") {
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

    /** The second line of a row: where it belongs, by the GTD list on. */
    private String lineOf(TaskStore.Stored stored) {
        Task task = stored.task;
        List<String> parts = new ArrayList<>();
        if (Task.WAITING.equals(only())) {
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
        if (Task.INBOX.equals(only())) {
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
        if (Task.WAITING.equals(only())) {
            long since = TaskStore.capturedAt(task);
            if (since <= 0) {
                return "";
            }
            long days = (System.currentTimeMillis() - since) / 86_400_000L;
            return host.getString(R.string.task_waiting_days, days);
        }
        if (TaskOrder.DONE.equals(TaskOrder.stateOf(task))) {
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
            boolean waiting = Task.WAITING.equals(only());
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
            } else if (!applied.states.equals(Set.of(TaskOrder.PROGRESS))
                    && TaskOrder.underWay(task)) {
                badge.setText(R.string.task_list_progress);
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }

            // NOTE: done and cancelled rows step back as a mail row with no
            // body does, the card behind them left whole; a cancelled one is
            // struck through.
            boolean closed = task.closed();
            ViewGroup card = view.findViewById(R.id.row_card);
            for (int index = 0; index < card.getChildCount(); index++) {
                card.getChildAt(index).setAlpha(closed ? CLOSED_ALPHA : 1f);
            }
            TextView summary = view.findViewById(R.id.task_summary);
            boolean struck = TaskOrder.CANCELLED.equals(TaskOrder.stateOf(task));
            int strike = android.graphics.Paint.STRIKE_THRU_TEXT_FLAG;
            summary.setPaintFlags(
                    struck ? summary.getPaintFlags() | strike : summary.getPaintFlags() & ~strike);

            // NOTE: a capture is clarified rather than ticked off, its slot
            // the Inbox glyph clarifying it; a closed task's tick reopens it.
            ImageButton done = view.findViewById(R.id.task_done);
            boolean capture = Task.INBOX.equals(only()) && !closed;
            boolean picked = selected.containsKey(keyOf(row.stored));
            done.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            host.resolveColor(
                                    picked
                                            ? android.R.attr.colorAccent
                                            : android.R.attr.textColorSecondary)));
            if (isSelectionMode()) {
                // NOTE: while selecting, the box marks the selection and its
                // tap toggles it, as the row's does.
                done.setAlpha(1f);
                done.setOnClickListener(button -> toggle(row.stored));
                done.setImageResource(
                        picked ? R.drawable.ic_check_box : R.drawable.ic_check_box_blank);
                done.setContentDescription(host.getString(R.string.selected_count, 1));
            } else if (capture) {
                done.setOnClickListener(button -> clarify.open(List.of(row.stored)));
                done.setImageResource(R.drawable.ic_task_inbox);
                done.setContentDescription(host.getString(R.string.task_clarify));
            } else {
                done.setOnClickListener(button -> done(row.stored, !closed));
                done.setImageResource(
                        !closed
                                ? R.drawable.ic_check_box_blank
                                : TaskOrder.DONE.equals(task.status())
                                        ? R.drawable.ic_check_box
                                        : R.drawable.ic_cancel);
                done.setContentDescription(
                        host.getString(closed ? R.string.task_reopen : R.string.task_mark_done));
            }
            return view;
        }
    }
}
