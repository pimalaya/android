package org.pimalaya.task;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.pimalaya.R;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.DateEdit;
import org.pimalaya.client.EventTimeKind;
import org.pimalaya.client.Task;
import org.pimalaya.client.TaskEdit;
import org.pimalaya.ui.Host;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Tasks list's actions on a selection (cairn/changes/task-batch-actions):
 * Category, Status, Due, Move, Copy and Delete.
 *
 * <p>Each stages one write per task offline, as the task page does for one,
 * off the main thread; a refused write is reported once and the others kept.
 * Every action ends the selection.
 */
final class TaskBatch {
    /** The GTD lists the Category sheet heads with, one state at most. */
    private static final String[] LISTS = {Task.INBOX, Task.WAITING, Task.SOMEDAY};

    private static final int[] LIST_LABELS = {
        R.string.task_list_inbox, R.string.task_list_waiting, R.string.task_list_someday,
    };

    /** What Status offers, in TaskPage's order. */
    private static final String[] STATES = {
        TaskOrder.TODO, TaskOrder.PROGRESS, TaskOrder.DONE, TaskOrder.CANCELLED,
    };

    private static final int[] STATE_LABELS = {
        R.string.task_status_todo,
        R.string.task_list_progress,
        R.string.task_list_done,
        R.string.task_list_cancelled,
    };

    /** A box of the Category sheet. */
    private enum Box {
        ON,
        PARTIAL,
        OFF,
    }

    /** One task's write. */
    private interface Write {
        void run(TaskStore.Stored stored) throws Exception;
    }

    private final Host host;
    private final TasksList list;
    private final TaskWrites writes;

    TaskBatch(Host host, TasksList list, TaskWrites writes) {
        this.host = host;
        this.list = list;
        this.writes = writes;
    }

    /**
     * Category: Inbox, Waiting, Someday, then every category a task carries,
     * each ticked when every selected task carries it, partly when some do;
     * Apply adds the ticked ones and takes the blank ones off, a partly
     * ticked one changing nothing.
     */
    void category(List<TaskStore.Stored> tasks) {
        Map<String, String> names = new LinkedHashMap<>();
        for (String name : list.everyCategory()) {
            names.putIfAbsent(TaskOrder.fold(name), name);
        }
        Map<String, Box> before = new HashMap<>();
        Map<String, Box> now = new HashMap<>();
        for (String key : LISTS) {
            int carried = 0;
            for (TaskStore.Stored stored : tasks) {
                carried += stored.task.in(key) ? 1 : 0;
            }
            before.put(key, boxOf(carried, tasks.size()));
        }
        for (String folded : names.keySet()) {
            int carried = 0;
            for (TaskStore.Stored stored : tasks) {
                carried += TaskOrder.carriesAny(stored, Set.of(folded)) ? 1 : 0;
            }
            before.put(folded, boxOf(carried, tasks.size()));
        }
        now.putAll(before);

        LinearLayout rows = new LinearLayout(host);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, host.dp(8), 0, 0);
        ScrollView scroll = new ScrollView(host);
        scroll.addView(rows);
        Runnable[] draw = new Runnable[1];
        draw[0] =
                () -> {
                    rows.removeAllViews();
                    for (int index = 0; index < LISTS.length; index++) {
                        String key = LISTS[index];
                        rows.addView(
                                row(
                                        host.getString(LIST_LABELS[index]),
                                        now.get(key),
                                        () -> {
                                            Box next = cycle(before.get(key), now.get(key));
                                            // NOTE: one state at most: ticking a
                                            // list leaves the others blank.
                                            if (next == Box.ON) {
                                                for (String other : LISTS) {
                                                    now.put(other, Box.OFF);
                                                }
                                            }
                                            now.put(key, next);
                                            draw[0].run();
                                        }));
                    }
                    for (Map.Entry<String, String> name : names.entrySet()) {
                        String folded = name.getKey();
                        rows.addView(
                                row(
                                        name.getValue(),
                                        now.get(folded),
                                        () -> {
                                            now.put(
                                                    folded,
                                                    cycle(before.get(folded), now.get(folded)));
                                            draw[0].run();
                                        }));
                    }
                    TextView add = new TextView(host);
                    add.setText(R.string.task_category_new);
                    add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    add.setTextColor(host.resolveColor(android.R.attr.colorAccent));
                    add.setPadding(host.dp(24), host.dp(12), host.dp(24), host.dp(12));
                    add.setBackgroundResource(
                            host.ui.resolveAttr(android.R.attr.selectableItemBackground));
                    add.setOnClickListener(
                            view ->
                                    TaskDialogs.text(
                                            host,
                                            R.string.task_category_new,
                                            "",
                                            false,
                                            value -> {
                                                String trimmed = value.trim();
                                                if (trimmed.isEmpty()
                                                        || TaskOrder.reserved(trimmed)) {
                                                    return;
                                                }
                                                String folded = TaskOrder.fold(trimmed);
                                                names.putIfAbsent(folded, trimmed);
                                                before.putIfAbsent(folded, Box.OFF);
                                                now.put(folded, Box.ON);
                                                draw[0].run();
                                            }));
                    rows.addView(add);
                };
        draw[0].run();

        new AlertDialog.Builder(host)
                .setTitle(
                        host.getResources()
                                .getQuantityString(
                                        R.plurals.task_batch_category_title,
                                        tasks.size(),
                                        tasks.size()))
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.filter_apply,
                        (dialog, which) -> {
                            String on = null;
                            Set<String> cleared = new HashSet<>();
                            for (String key : LISTS) {
                                if (now.get(key) == Box.ON) {
                                    on = key;
                                } else if (now.get(key) == Box.OFF
                                        && before.get(key) != Box.OFF) {
                                    cleared.add(key);
                                }
                            }
                            Map<String, String> ticked = new LinkedHashMap<>();
                            Set<String> blank = new HashSet<>();
                            for (Map.Entry<String, String> name : names.entrySet()) {
                                Box box = now.get(name.getKey());
                                if (box == Box.ON) {
                                    ticked.put(name.getKey(), name.getValue());
                                } else if (box == Box.OFF) {
                                    blank.add(name.getKey());
                                }
                            }
                            String state = on;
                            each(
                                    tasks,
                                    stored -> {
                                        TaskEdit edit = TaskEdit.at(TaskWrites.now());
                                        String moved = stateAfter(stored.task, state, cleared);
                                        if (moved != null) {
                                            edit = edit.withList(moved);
                                        }
                                        List<String> own = stored.task.categories();
                                        List<String> merged = merged(own, ticked, blank);
                                        if (!merged.equals(own)) {
                                            edit = edit.withCategories(String.join(",", merged));
                                        }
                                        writes.edit(stored, edit);
                                    });
                        })
                .show();
    }

    /** Status: one state set on every task, Done stamping its time. */
    void status(List<TaskStore.Stored> tasks) {
        CharSequence[] labels = new CharSequence[STATES.length];
        for (int index = 0; index < STATES.length; index++) {
            labels[index] = host.getString(STATE_LABELS[index]);
        }
        TaskDialogs.pick(
                host,
                R.string.task_field_status,
                labels,
                -1,
                picked ->
                        each(
                                tasks,
                                stored ->
                                        writes.edit(
                                                stored,
                                                TaskPage.withStatus(
                                                        TaskEdit.at(TaskWrites.now()),
                                                        STATES[picked],
                                                        stored.task.closed()))));
    }

    /** Due: a day set on every task, or cleared from all. */
    void due(List<TaskStore.Stored> tasks) {
        TaskDialogs.date(
                host,
                "",
                day ->
                        each(
                                tasks,
                                stored ->
                                        writes.edit(
                                                stored,
                                                TaskEdit.at(TaskWrites.now())
                                                        .withDue(
                                                                new DateEdit(
                                                                        day,
                                                                        day.isEmpty()
                                                                                ? null
                                                                                : EventTimeKind
                                                                                        .DATE,
                                                                        "")))));
    }

    /** Move: every task filed in the calendar picked, those there left alone. */
    void move(List<TaskStore.Stored> tasks) {
        pickCalendar(
                R.string.task_batch_move,
                target ->
                        each(
                                tasks,
                                stored -> {
                                    if (!stored.calendar.id.equals(target.id)) {
                                        writes.moveTo(
                                                stored, target, TaskEdit.at(TaskWrites.now()));
                                    }
                                }));
    }

    /**
     * Copy: a new task per selected one in the calendar picked, under a new
     * {@code UID}, a subtask kept under its parent when the parent is copied
     * too.
     */
    void copy(List<TaskStore.Stored> tasks) {
        pickCalendar(
                R.string.task_batch_copy,
                target -> {
                    Map<String, String> copies = new HashMap<>();
                    each(
                            parentsFirst(tasks),
                            stored ->
                                    copies.put(
                                            stored.task.uid(),
                                            writes.create(
                                                    target,
                                                    copyOf(
                                                            stored.task,
                                                            copies.get(stored.task.parent())))));
                });
    }

    /** Delete: asked with the count, every task restorable from Deleted items. */
    void delete(List<TaskStore.Stored> tasks) {
        new AlertDialog.Builder(host)
                .setMessage(
                        host.getResources()
                                .getQuantityString(
                                        R.plurals.task_batch_delete_confirm,
                                        tasks.size(),
                                        tasks.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.task_batch_delete,
                        (dialog, which) -> each(tasks, writes::remove))
                .show();
    }

    /** Asks for a calendar taking tasks, then hands it to {@code picked}. */
    private void pickCalendar(
            int title, java.util.function.Consumer<EventStore.StoredCalendar> picked) {
        host.io.execute(
                () -> {
                    List<EventStore.StoredCalendar> targets = list.store().targets(host);
                    host.postAlive(
                            () -> {
                                if (targets.isEmpty()) {
                                    host.toast(host.getString(R.string.capture_no_calendar));
                                    return;
                                }
                                CharSequence[] labels = new CharSequence[targets.size()];
                                for (int index = 0; index < targets.size(); index++) {
                                    EventStore.StoredCalendar calendar = targets.get(index);
                                    labels[index] =
                                            host.ui.twoLine(calendar.name, calendar.accountEmail);
                                }
                                TaskDialogs.pick(
                                        host,
                                        title,
                                        labels,
                                        -1,
                                        index -> picked.accept(targets.get(index)));
                            });
                });
    }

    /**
     * Runs {@code write} on each task in turn off the main thread, then
     * reports the first refusal, ends the selection and reloads.
     */
    private void each(List<TaskStore.Stored> tasks, Write write) {
        host.io.execute(
                () -> {
                    Exception first = null;
                    for (TaskStore.Stored stored : tasks) {
                        try {
                            write.run(stored);
                        } catch (Exception error) {
                            Log.w("pimalaya", "task batch write failed: " + stored.event.id, error);
                            if (first == null) {
                                first = error;
                            }
                        }
                    }
                    Exception outcome = first;
                    host.postAlive(
                            () -> {
                                if (outcome != null) {
                                    host.showError(outcome, R.string.task_save_failed);
                                }
                                list.endSelection();
                                list.reload();
                            });
                });
    }

    /** One row of the Category sheet: its box, its name. */
    private View row(String label, Box box, Runnable tap) {
        ImageView mark = new ImageView(host);
        mark.setImageResource(
                box == Box.ON
                        ? R.drawable.ic_check_box
                        : box == Box.PARTIAL
                                ? R.drawable.ic_check_box_partial
                                : R.drawable.ic_check_box_blank);
        mark.setImageTintList(
                ColorStateList.valueOf(
                        host.resolveColor(
                                box == Box.OFF
                                        ? android.R.attr.textColorSecondary
                                        : android.R.attr.colorAccent)));
        TextView name = new TextView(host);
        name.setText(label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTextColor(host.resolveColor(android.R.attr.textColorPrimary));

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(host.getResources().getDimensionPixelSize(R.dimen.item_height));
        row.setPadding(host.dp(24), 0, host.dp(24), 0);
        row.setBackgroundResource(host.ui.resolveAttr(android.R.attr.selectableItemBackground));
        int icon = host.getResources().getDimensionPixelSize(R.dimen.item_icon);
        row.addView(mark, new LinearLayout.LayoutParams(icon, icon));
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginStart(host.dp(16));
        row.addView(name, params);
        row.setOnClickListener(view -> tap.run());
        return row;
    }

    private static Box boxOf(int carried, int of) {
        return carried == 0 ? Box.OFF : carried == of ? Box.ON : Box.PARTIAL;
    }

    /**
     * A box's next state on a tap: ticked, blank, then back to partly ticked
     * when it started so, which leaves the tasks as they are.
     */
    private static Box cycle(Box before, Box now) {
        if (now == Box.ON) {
            return Box.OFF;
        }
        if (now == Box.OFF && before == Box.PARTIAL) {
            return Box.PARTIAL;
        }
        return Box.ON;
    }

    /**
     * A task's own categories once the sheet applies: each of {@code ticked}
     * (folded to its spelling) added when missing, each of {@code blank}
     * (folded) taken off, compared folded; the rest kept in order.
     */
    static List<String> merged(
            List<String> own, Map<String, String> ticked, Set<String> blank) {
        List<String> merged = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        for (String category : own) {
            String folded = TaskOrder.fold(category);
            if (!blank.contains(folded) && kept.add(folded)) {
                merged.add(category);
            }
        }
        for (Map.Entry<String, String> category : ticked.entrySet()) {
            if (kept.add(category.getKey())) {
                merged.add(category.getValue());
            }
        }
        return merged;
    }

    /**
     * The state a task moves to once the sheet applies, null to leave it:
     * {@code on} when a list was ticked, else no state when a list it is in
     * was made blank.
     */
    static String stateAfter(Task task, String on, Set<String> cleared) {
        if (on != null) {
            return task.in(on) ? null : on;
        }
        for (String list : cleared) {
            if (task.in(list)) {
                return Task.NEXT;
            }
        }
        return null;
    }

    /** The tasks with every parent among them before its children. */
    static List<TaskStore.Stored> parentsFirst(List<TaskStore.Stored> tasks) {
        Set<String> among = new HashSet<>();
        for (TaskStore.Stored stored : tasks) {
            among.add(stored.task.uid());
        }
        List<TaskStore.Stored> ordered = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        // NOTE: each round places the tasks whose parent is placed or not
        // among them; a cycle of parents is placed as it comes.
        while (ordered.size() < tasks.size()) {
            int before = ordered.size();
            for (TaskStore.Stored stored : tasks) {
                String parent = stored.task.parent();
                if (!placed.contains(stored.task.uid())
                        && (parent == null || !among.contains(parent) || placed.contains(parent))) {
                    ordered.add(stored);
                    placed.add(stored.task.uid());
                }
            }
            if (ordered.size() == before) {
                for (TaskStore.Stored stored : tasks) {
                    if (placed.add(stored.task.uid())) {
                        ordered.add(stored);
                    }
                }
            }
        }
        return ordered;
    }

    /**
     * The edit writing a copy of {@code task}: everything it states but its
     * {@code UID}, under {@code parent} when that one was copied, else on its
     * own.
     */
    static TaskEdit copyOf(Task task, String parent) {
        TaskEdit edit =
                TaskEdit.at(TaskWrites.now())
                        .withList(task.list())
                        .withText(
                                task.summary(),
                                task.description().isEmpty() ? null : task.description());
        if (!task.categories().isEmpty()) {
            edit = edit.withCategories(String.join(",", task.categories()));
        }
        if (task.due() != null) {
            edit =
                    edit.withDue(
                            new DateEdit(task.due().time(), task.due().kind(), task.due().tzid()));
        }
        if (task.priority() != 0) {
            edit = edit.withPriority(String.valueOf(task.priority()));
        }
        if (!task.contactName().isEmpty() || !task.contactAddress().isEmpty()) {
            edit = edit.withContact(task.contactName(), task.contactAddress());
        }
        if (task.source() != null) {
            edit = edit.withSource(task.source());
        }
        if (parent != null) {
            edit = edit.withParent(parent);
        }
        String state = TaskOrder.stateOf(task);
        return TaskOrder.TODO.equals(state) ? edit : TaskPage.withStatus(edit, state, false);
    }
}
