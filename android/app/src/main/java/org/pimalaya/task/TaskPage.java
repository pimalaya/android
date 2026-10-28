package org.pimalaya.task;

import android.app.AlertDialog;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.pimalaya.R;
import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.client.DateEdit;
import org.pimalaya.client.EventTimeKind;
import org.pimalaya.client.Task;
import org.pimalaya.client.TaskEdit;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.Panels;
import org.pimalaya.ui.Sections;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * A task's page: its list, categories, parent task, due date, who it waits on
 * and the mail it came from, edited and saved as one write.
 *
 * <p>The working values start as opened; a save sends only what moved, so
 * what another client set and this page does not show survives (the bridge
 * patches the object, never rebuilds it).
 */
public final class TaskPage {
    /** The lists a task can move to from its page, in GTD's order. */
    private static final String[] LISTS = {
        Task.INBOX, Task.NEXT, Task.WAITING, Task.SOMEDAY,
    };

    private static final int[] LIST_LABELS = {
        R.string.task_list_inbox,
        R.string.task_list_next,
        R.string.task_list_waiting,
        R.string.task_list_someday,
    };

    /** The priorities offered: none, high, medium, low (RFC 5545 3.8.1.9). */
    private static final String[] PRIORITIES = {"", "1", "5", "9"};

    private static final int[] PRIORITY_LABELS = {
        R.string.task_priority_none,
        R.string.task_priority_high,
        R.string.task_priority_medium,
        R.string.task_priority_low,
    };

    private final Host host;
    private final TasksList list;
    private final TaskWrites writes;
    private final Sections sections;

    /** Opens a mail by its collection and link id. */
    private final BiConsumer<String, String> openMail;

    private TaskStore.Stored opened;

    /** The panel the page goes back to: the Tasks tab, or the agenda. */
    private int back = Panels.TASKS;

    private String summary;
    private String description;
    private String listed;
    private List<String> categories;
    private String parent;
    private String due;
    private String priority;
    private String contactName;
    private String contactAddress;

    /** The page over {@code list}'s load; {@code openMail} opens a source. */
    public TaskPage(
            Host host, CalendarParts parts, TasksList list, BiConsumer<String, String> openMail) {
        this.host = host;
        this.list = list;
        this.writes = new TaskWrites(parts);
        this.openMail = openMail;
        this.sections = new Sections(host, (LinearLayout) host.findViewById(R.id.task_page));
    }

    /** The panel the page goes back to. */
    public int back() {
        return back;
    }

    /** What the bar calls the page: the task's title. */
    public String title() {
        return summary == null || summary.isEmpty()
                ? host.getString(R.string.event_untitled)
                : summary;
    }

    /** Opens the page on a task, from the Tasks tab. */
    public void open(TaskStore.Stored stored) {
        open(stored, Panels.TASKS);
    }

    /** Opens the page on a task, going back to {@code from}. */
    public void open(TaskStore.Stored stored, int from) {
        opened = stored;
        back = from;
        Task task = stored.task;
        summary = task.summary();
        description = task.description();
        listed = task.list();
        categories = new ArrayList<>(task.categories());
        parent = task.parent() == null ? "" : task.parent();
        due = task.due() == null ? "" : task.due().time().substring(0, 8);
        priority = task.priority() == 0 ? "" : String.valueOf(task.priority());
        contactName = task.contactName();
        contactAddress = task.contactAddress();
        render();
        host.show(Panels.TASK);
    }

    private void render() {
        sections.clear();
        LinearLayout container = host.findViewById(R.id.task_page);

        LinearLayout lists = new LinearLayout(host);
        lists.setPadding(host.dp(16), host.dp(8), host.dp(16), 0);
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(host);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(lists);
        container.addView(scroll);
        for (int index = 0; index < LISTS.length; index++) {
            String target = LISTS[index];
            TextView chip = new TextView(host, null, 0, R.style.Chip);
            chip.setText(LIST_LABELS[index]);
            boolean on = target.equals(listed);
            chip.setBackgroundResource(on ? R.drawable.button_pill : R.drawable.button_tonal);
            chip.setTextColor(
                    on
                            ? host.accentContrast()
                            : host.ui.resolveColor(android.R.attr.textColorPrimary));
            chip.setOnClickListener(
                    view -> {
                        listed = target;
                        render();
                    });
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, host.dp(34));
            params.setMarginEnd(host.dp(8));
            lists.addView(chip, params);
        }

        List<View> organise = new ArrayList<>();
        organise.add(
                sections.row(
                        host.getString(R.string.task_field_categories),
                        categories.isEmpty()
                                ? host.getString(R.string.task_none)
                                : String.join(", ", categories),
                        null,
                        this::pickCategories));
        TaskStore.Stored above = list.taskOf(parent);
        organise.add(
                sections.row(
                        host.getString(R.string.task_field_parent),
                        above == null
                                ? host.getString(R.string.task_none)
                                : above.task.summary(),
                        null,
                        this::pickParent));
        organise.add(
                sections.row(
                        host.getString(R.string.task_field_due),
                        due.isEmpty()
                                ? host.getString(R.string.task_add_date)
                                : org.pimalaya.ui.Dates.date(host, dayStamp(due)),
                        null,
                        () -> TaskDialogs.date(host, due, picked -> {
                            due = picked;
                            render();
                        })));
        sections.section(R.string.task_section_organise, R.drawable.ic_folder_open, organise, null, false);

        if (Task.WAITING.equals(listed)) {
            String who = contactName.isEmpty() ? contactAddress : contactName;
            List<View> waiting = new ArrayList<>();
            waiting.add(
                    sections.row(
                            host.getString(R.string.task_field_waiting_on),
                            who.isEmpty() ? host.getString(R.string.task_none) : who,
                            null,
                            () -> TaskDialogs.text(
                                    host,
                                    R.string.task_field_waiting_on,
                                    who,
                                    false,
                                    value -> {
                                        if (value.contains("@") && !value.contains(" ")) {
                                            contactAddress = value;
                                            contactName = "";
                                        } else {
                                            contactName = value;
                                        }
                                        render();
                                    })));
            sections.section(R.string.task_section_waiting, R.drawable.ic_task_waiting, waiting, null, false);
        }

        if (opened.task.source() != null) {
            List<View> source = new ArrayList<>();
            source.add(
                    sections.row(
                            host.getString(R.string.task_open_mail),
                            opened.task.source(),
                            null,
                            this::openSource));
            sections.section(R.string.task_section_from, R.drawable.ic_section_mail, source, null, false);
        }

        List<View> details = new ArrayList<>();
        details.add(
                sections.row(
                        host.getString(R.string.event_field_summary),
                        summary,
                        null,
                        () -> TaskDialogs.text(
                                host, R.string.event_field_summary, summary, false, value -> {
                                    summary = value;
                                    render();
                                    host.updateBarTitle(title());
                                })));
        details.add(
                sections.row(
                        host.getString(R.string.task_field_notes),
                        description.isEmpty() ? host.getString(R.string.task_none) : description,
                        null,
                        () -> TaskDialogs.text(
                                host, R.string.task_field_notes, description, true, value -> {
                                    description = value;
                                    render();
                                })));
        details.add(
                sections.row(
                        host.getString(R.string.event_field_priority),
                        host.getString(PRIORITY_LABELS[priorityIndex()]),
                        null,
                        this::pickPriority));
        sections.section(R.string.event_section_details, R.drawable.ic_section_notes, details, null, false);

        List<View> where = new ArrayList<>();
        where.add(
                sections.row(
                        opened.calendar.name,
                        opened.calendar.accountEmail,
                        null,
                        null));
        sections.section(R.string.task_section_calendar, R.drawable.ic_domain_calendar, where, null, false);

        List<View> actions = new ArrayList<>();
        boolean closed = opened.task.closed();
        actions.add(
                sections.row(
                        host.getString(closed ? R.string.task_reopen : R.string.task_mark_done),
                        null,
                        null,
                        () -> {
                            list.done(opened, !closed);
                            host.showBack(back);
                        }));
        actions.add(sections.row(host.getString(R.string.task_drop), null, null, this::drop));
        sections.section(0, 0, actions, null, false);
    }

    private int priorityIndex() {
        for (int index = 0; index < PRIORITIES.length; index++) {
            if (PRIORITIES[index].equals(priority)) {
                return index;
            }
        }
        int value = priority.isEmpty() ? 0 : Integer.parseInt(priority);
        return value == 0 ? 0 : value < 5 ? 1 : value == 5 ? 2 : 3;
    }

    private void pickPriority() {
        CharSequence[] labels = new CharSequence[PRIORITY_LABELS.length];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = host.getString(PRIORITY_LABELS[index]);
        }
        TaskDialogs.pick(host, R.string.event_field_priority, labels, priorityIndex(), picked -> {
            priority = PRIORITIES[picked];
            render();
        });
    }

    private void pickCategories() {
        Set<String> known = new LinkedHashSet<>(list.categories());
        known.addAll(categories);
        List<String> names = new ArrayList<>(known);
        CharSequence[] labels = names.toArray(new CharSequence[0]);
        boolean[] checked = new boolean[names.size()];
        for (int index = 0; index < names.size(); index++) {
            checked[index] = categories.contains(names.get(index));
        }
        new AlertDialog.Builder(host)
                .setTitle(R.string.task_field_categories)
                .setMultiChoiceItems(labels, checked, (dialog, which, on) -> checked[which] = on)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> {
                            categories.clear();
                            for (int index = 0; index < names.size(); index++) {
                                if (checked[index]) {
                                    categories.add(names.get(index));
                                }
                            }
                            render();
                        })
                .setNeutralButton(
                        R.string.task_category_new,
                        (dialog, which) ->
                                TaskDialogs.text(host, R.string.task_category_new, "", false, value -> {
                                    if (!value.isEmpty() && !categories.contains(value)) {
                                        categories.add(value);
                                    }
                                    render();
                                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickParent() {
        List<TaskStore.Stored> parents = list.parentsFor(opened);
        CharSequence[] labels = new CharSequence[parents.size() + 1];
        labels[0] = host.getString(R.string.task_none);
        int checked = 0;
        for (int index = 0; index < parents.size(); index++) {
            labels[index + 1] = parents.get(index).task.summary();
            if (parents.get(index).task.uid().equals(parent)) {
                checked = index + 1;
            }
        }
        TaskDialogs.pick(host, R.string.task_field_parent, labels, checked, picked -> {
            parent = picked == 0 ? "" : parents.get(picked - 1).task.uid();
            render();
        });
    }

    private void openSource() {
        String source = opened.task.source();
        host.io.execute(
                () -> {
                    String[] found = list.store().mailOf(source);
                    host.postAlive(
                            () -> {
                                if (found == null) {
                                    host.toast(host.getString(R.string.task_mail_gone));
                                    return;
                                }
                                openMail.accept(found[0], found[1]);
                            });
                });
    }

    private void drop() {
        new AlertDialog.Builder(host)
                .setMessage(R.string.task_drop_confirm)
                .setPositiveButton(
                        R.string.task_drop,
                        (dialog, which) -> write(TaskEdit.at(TaskWrites.now()).withCancelled()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Saves what moved since the page opened. */
    public void save() {
        Task task = opened.task;
        TaskEdit edit = TaskEdit.at(TaskWrites.now());
        if (!summary.equals(task.summary()) || !description.equals(task.description())) {
            edit = edit.withText(summary, description);
        }
        if (!listed.equals(task.list())) {
            edit = edit.withList(listed);
        }
        if (!categories.equals(task.categories())) {
            edit = edit.withCategories(String.join(",", categories));
        }
        if (!parent.equals(task.parent() == null ? "" : task.parent())) {
            edit = edit.withParent(parent);
        }
        String openedDue = task.due() == null ? "" : task.due().time().substring(0, 8);
        if (!due.equals(openedDue)) {
            edit = edit.withDue(new DateEdit(due, due.isEmpty() ? null : EventTimeKind.DATE, ""));
        }
        String openedPriority = task.priority() == 0 ? "" : String.valueOf(task.priority());
        if (!priority.equals(openedPriority)) {
            edit = edit.withPriority(priority);
        }
        if (!Objects.equals(contactName, task.contactName())
                || !Objects.equals(contactAddress, task.contactAddress())) {
            edit = edit.withContact(contactName, contactAddress);
        }
        write(edit);
    }

    private void write(TaskEdit edit) {
        TaskStore.Stored target = opened;
        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
        host.io.execute(
                () -> {
                    Exception failure = null;
                    try {
                        writes.edit(target, edit);
                    } catch (Exception error) {
                        Log.w("pimalaya", "task save failed: " + target.event.id, error);
                        failure = error;
                    }
                    Exception outcome = failure;
                    host.postAlive(
                            () -> {
                                host.setAuthLoading(R.id.fab, R.id.fab_progress, false);
                                if (outcome != null) {
                                    host.showError(outcome, R.string.task_save_failed);
                                    return;
                                }
                                list.reload();
                                host.showBack(back);
                            });
                });
    }

    /** Midnight opening a civil {@code YYYYMMDD} day, in the device's zone. */
    static long dayStamp(String day) {
        return java.time.LocalDate.of(
                        Integer.parseInt(day.substring(0, 4)),
                        Integer.parseInt(day.substring(4, 6)),
                        Integer.parseInt(day.substring(6, 8)))
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }
}
