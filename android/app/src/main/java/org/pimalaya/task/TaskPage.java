package org.pimalaya.task;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * A task's page, on the entry page's shape (EventView): its details, when it
 * is due, its progress, how it is organised, the mail it came from and its
 * calendar, edited and saved as one write.
 *
 * <p>Each row opens a dialog; a field the task has not got stays hidden until
 * its section's add button reveals it. The working values start as opened; a
 * save sends only what moved, so what another client set and this page does
 * not show survives (the bridge patches the object, never rebuilds it).
 */
public final class TaskPage {
    /** The GTD lists a task can move to, none being a next action. */
    private static final String[] LISTS = {
        Task.NEXT, Task.INBOX, Task.WAITING, Task.SOMEDAY,
    };

    private static final int[] LIST_LABELS = {
        R.string.task_none,
        R.string.task_list_inbox,
        R.string.task_list_waiting,
        R.string.task_list_someday,
    };

    /**
     * The statuses offered (RFC 5545 3.8.1.11), none stated reading as the
     * first.
     */
    private static final String[] STATUSES = {
        "NEEDS-ACTION", TaskOrder.PROGRESS, TaskOrder.DONE, TaskOrder.CANCELLED,
    };

    private static final int[] STATUS_LABELS = {
        R.string.task_status_todo,
        R.string.task_list_progress,
        R.string.task_list_done,
        R.string.task_list_cancelled,
    };

    /** The priorities offered: none, high, medium, low (RFC 5545 3.8.1.9). */
    private static final String[] PRIORITIES = {"", "1", "5", "9"};

    private static final int[] PRIORITY_LABELS = {
        R.string.task_priority_none,
        R.string.task_priority_high,
        R.string.task_priority_medium,
        R.string.task_priority_low,
    };

    /** The fields a section's add button reveals, by key. */
    private static final String NOTES = "notes";

    private static final String PRIORITY = "priority";
    private static final String DUE = "due";
    private static final String CATEGORIES = "categories";
    private static final String PARENT = "parent";
    private static final String CONTACT = "contact";

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
    private String status;
    private String listed;
    private List<String> categories;
    private String parent;
    private String due;
    private String priority;
    private String contactName;
    private String contactAddress;

    /** The empty fields an add button revealed since the page opened. */
    private final Set<String> revealed = new HashSet<>();

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
        status = statusOf(task);
        listed = task.list();
        categories = new ArrayList<>(task.categories());
        parent = task.parent() == null ? "" : task.parent();
        due = task.due() == null ? "" : task.due().time().substring(0, 8);
        priority = task.priority() == 0 ? "" : String.valueOf(task.priority());
        contactName = task.contactName();
        contactAddress = task.contactAddress();
        revealed.clear();
        render();
        host.show(Panels.TASK);
    }

    /** A task's status as the page offers it, none read as not started. */
    private static String statusOf(Task task) {
        for (String offered : STATUSES) {
            if (offered.equals(task.status())) {
                return offered;
            }
        }
        return STATUSES[0];
    }

    /** Whether a field shows: it holds a value, or an add button revealed it. */
    private boolean shows(String field) {
        if (revealed.contains(field)) {
            return true;
        }
        switch (field) {
            case NOTES:
                return !description.isEmpty();
            case PRIORITY:
                return !priority.isEmpty();
            case DUE:
                return !due.isEmpty();
            case CATEGORIES:
                return !categories.isEmpty();
            case PARENT:
                return !parent.isEmpty();
            case CONTACT:
                return Task.WAITING.equals(listed)
                        || !contactName.isEmpty()
                        || !contactAddress.isEmpty();
            default:
                return false;
        }
    }

    private void render() {
        sections.clear();
        details();
        when();
        progress();
        organise();

        if (opened.task.source() != null) {
            List<View> source = new ArrayList<>();
            source.add(
                    sections.row(
                            host.getString(R.string.task_open_mail),
                            opened.task.source(),
                            null,
                            this::openSource));
            sections.section(
                    R.string.task_section_from, R.drawable.ic_section_mail, source, null, false);
        }

        List<View> where = new ArrayList<>();
        where.add(sections.row(opened.calendar.name, opened.calendar.accountEmail, null, null));
        sections.section(
                R.string.task_section_calendar, R.drawable.ic_domain_calendar, where, null, false);
    }

    /** The title, then the notes and the priority once set or revealed. */
    private void details() {
        List<View> rows = new ArrayList<>();
        rows.add(
                sections.row(
                        host.getString(R.string.event_field_summary),
                        summary.isEmpty() ? host.getString(R.string.event_unset) : summary,
                        null,
                        () -> TaskDialogs.text(
                                host, R.string.event_field_summary, summary, false, value -> {
                                    summary = value;
                                    render();
                                    host.updateBarTitle(title());
                                })));
        if (shows(NOTES)) {
            rows.add(
                    sections.row(
                            host.getString(R.string.task_field_notes),
                            unsetOr(description),
                            null,
                            this::editNotes));
        }
        if (shows(PRIORITY)) {
            rows.add(
                    sections.row(
                            host.getString(R.string.event_field_priority),
                            host.getString(PRIORITY_LABELS[priorityIndex()]),
                            null,
                            this::pickPriority));
        }
        sections.section(
                R.string.event_section_details,
                R.drawable.ic_section_notes,
                rows,
                addIcon(NOTES, PRIORITY),
                true);
    }

    /** The due date once set or revealed. */
    private void when() {
        List<View> rows = new ArrayList<>();
        if (shows(DUE)) {
            rows.add(
                    sections.row(
                            host.getString(R.string.task_field_due),
                            due.isEmpty()
                                    ? host.getString(R.string.event_unset)
                                    : org.pimalaya.ui.Dates.date(host, dayStamp(due)),
                            null,
                            this::pickDue));
        }
        sections.section(
                R.string.event_section_when,
                R.drawable.ic_domain_calendar,
                rows,
                addIcon(DUE),
                true);
    }

    /** The status, then who a Waiting For item waits on. */
    private void progress() {
        List<View> rows = new ArrayList<>();
        rows.add(
                sections.row(
                        host.getString(R.string.task_field_status),
                        host.getString(STATUS_LABELS[indexOf(STATUSES, status)]),
                        null,
                        this::pickStatus));
        if (shows(CONTACT)) {
            String who = contactName.isEmpty() ? contactAddress : contactName;
            rows.add(
                    sections.row(
                            host.getString(R.string.task_field_waiting_on),
                            unsetOr(who),
                            null,
                            () -> editContact(who)));
        }
        sections.section(
                R.string.event_section_progress, R.drawable.ic_check, rows, addIcon(CONTACT), true);
    }

    /** The GTD list, then the categories and the task it sits under. */
    private void organise() {
        List<View> rows = new ArrayList<>();
        rows.add(
                sections.row(
                        host.getString(R.string.task_field_list),
                        host.getString(LIST_LABELS[indexOf(LISTS, listed)]),
                        null,
                        this::pickList));
        if (shows(CATEGORIES)) {
            rows.add(
                    sections.row(
                            host.getString(R.string.task_field_categories),
                            categories.isEmpty()
                                    ? host.getString(R.string.event_unset)
                                    : String.join(", ", categories),
                            null,
                            this::pickCategories));
        }
        if (shows(PARENT)) {
            TaskStore.Stored above = list.taskOf(parent);
            rows.add(
                    sections.row(
                            host.getString(R.string.task_field_parent),
                            above == null
                                    ? host.getString(R.string.event_unset)
                                    : above.task.summary(),
                            null,
                            this::pickParent));
        }
        sections.section(
                R.string.task_section_organise,
                R.drawable.ic_folder_open,
                rows,
                addIcon(CATEGORIES, PARENT),
                true);
    }

    /** {@code value}, or the unset placeholder when empty. */
    private String unsetOr(String value) {
        return value.isEmpty() ? host.getString(R.string.event_unset) : value;
    }

    private static int indexOf(String[] values, String value) {
        for (int index = 0; index < values.length; index++) {
            if (values[index].equals(value)) {
                return index;
            }
        }
        return 0;
    }

    /**
     * A section header's add action: the fields of {@code fields} not shown
     * yet, the one picked revealed and opened.
     */
    private View addIcon(String... fields) {
        ImageView icon = new ImageView(host);
        icon.setImageResource(R.drawable.ic_add_entry);
        icon.setImageTintList(ColorStateList.valueOf(sections.accentColor));
        icon.setContentDescription(host.getString(R.string.contact_add_field));
        icon.setBackgroundResource(
                host.ui.resolveAttr(android.R.attr.selectableItemBackgroundBorderless));
        icon.setPadding(host.dp(6), host.dp(6), host.dp(6), host.dp(6));
        icon.setOnClickListener(view -> addField(fields));
        return icon;
    }

    private void addField(String... fields) {
        List<String> offerable = new ArrayList<>();
        for (String field : fields) {
            if (!shows(field)) {
                offerable.add(field);
            }
        }
        if (offerable.isEmpty()) {
            host.toast(host.getString(R.string.event_nothing_to_add));
            return;
        }
        String[] labels = new String[offerable.size()];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = host.getString(labelOf(offerable.get(index)));
        }
        new AlertDialog.Builder(host)
                .setTitle(R.string.contact_add_field)
                .setItems(
                        labels,
                        (dialog, which) -> {
                            String field = offerable.get(which);
                            revealed.add(field);
                            render();
                            edit(field);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int labelOf(String field) {
        switch (field) {
            case NOTES:
                return R.string.task_field_notes;
            case PRIORITY:
                return R.string.event_field_priority;
            case DUE:
                return R.string.task_field_due;
            case CATEGORIES:
                return R.string.task_field_categories;
            case PARENT:
                return R.string.task_field_parent;
            default:
                return R.string.task_field_waiting_on;
        }
    }

    /** Opens a field's dialog, as its row does. */
    private void edit(String field) {
        switch (field) {
            case NOTES:
                editNotes();
                break;
            case PRIORITY:
                pickPriority();
                break;
            case DUE:
                pickDue();
                break;
            case CATEGORIES:
                pickCategories();
                break;
            case PARENT:
                pickParent();
                break;
            default:
                editContact(contactName.isEmpty() ? contactAddress : contactName);
        }
    }

    private void editNotes() {
        TaskDialogs.text(host, R.string.task_field_notes, description, true, value -> {
            description = value;
            render();
        });
    }

    private void pickDue() {
        TaskDialogs.date(host, due, picked -> {
            due = picked;
            render();
        });
    }

    private void editContact(String who) {
        TaskDialogs.text(host, R.string.task_field_waiting_on, who, false, value -> {
            if (value.contains("@") && !value.contains(" ")) {
                contactAddress = value;
                contactName = "";
            } else {
                contactName = value;
            }
            render();
        });
    }

    private void pickStatus() {
        CharSequence[] labels = new CharSequence[STATUS_LABELS.length];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = host.getString(STATUS_LABELS[index]);
        }
        TaskDialogs.pick(
                host, R.string.task_field_status, labels, indexOf(STATUSES, status), picked -> {
                    status = STATUSES[picked];
                    render();
                });
    }

    private void pickList() {
        CharSequence[] labels = new CharSequence[LIST_LABELS.length];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = host.getString(LIST_LABELS[index]);
        }
        TaskDialogs.pick(
                host, R.string.task_field_list, labels, indexOf(LISTS, listed), picked -> {
                    listed = LISTS[picked];
                    render();
                });
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

    /**
     * {@code edit} moving the task to {@code status}: done with its {@code
     * COMPLETED} time, dropped, or open again, a closed task's {@code
     * COMPLETED} and {@code PERCENT-COMPLETE} cleared (TaskEdit.withDone).
     */
    private static TaskEdit withStatus(TaskEdit edit, String status, boolean closed) {
        switch (status) {
            case TaskOrder.DONE:
                return edit.withDone(true);
            case TaskOrder.CANCELLED:
                return edit.withCancelled();
            default:
                return closed ? edit.withDone(false).withStatus(status) : edit.withStatus(status);
        }
    }

    /** Saves what moved since the page opened. */
    public void save() {
        Task task = opened.task;
        TaskEdit edit = TaskEdit.at(TaskWrites.now());
        if (!summary.equals(task.summary()) || !description.equals(task.description())) {
            edit = edit.withText(summary, description);
        }
        if (!status.equals(statusOf(task))) {
            edit = withStatus(edit, status, task.closed());
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
