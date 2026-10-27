package org.pimalaya.task;

import android.util.Log;
import android.util.TypedValue;
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
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The Inbox, clarified one item at a time in GTD's order.
 *
 * <p>Actionable? Under two minutes? Someone else's? More than one step? Then
 * the next action. Every outcome puts the item somewhere and none puts it back:
 * a skip leaves it first in line for the next pass. The answers build a
 * {@link Clarification}, applied as one write when the flow ends.
 */
public final class Clarify {
    private enum Step {
        ACTIONABLE,
        TWO_MINUTES,
        DELEGATED,
        PROJECT,
        NEXT_ACTION,
    }

    private final Host host;
    private final TasksList list;
    private final TaskWrites writes;
    private final Sections sections;
    private final BiConsumer<String, String> openMail;

    /** The items left to clarify, the shown one first. */
    private final List<TaskStore.Stored> queue = new ArrayList<>();

    private int total;
    private Step step;
    private Clarification answer;

    /** The flow over {@code list}'s Inbox; {@code openMail} opens a source. */
    public Clarify(
            Host host, CalendarParts parts, TasksList list, BiConsumer<String, String> openMail) {
        this.host = host;
        this.list = list;
        this.writes = new TaskWrites(parts);
        this.openMail = openMail;
        this.sections = new Sections(host, (LinearLayout) host.findViewById(R.id.clarify_page));
    }

    /** Starts on {@code items}, oldest first. */
    public void open(List<TaskStore.Stored> items) {
        if (items.isEmpty()) {
            return;
        }
        queue.clear();
        queue.addAll(items);
        total = items.size();
        begin();
        host.show(Panels.CLARIFY);
    }

    /** What the bar says: which item of how many. */
    public String title() {
        int at = total - queue.size() + 1;
        return host.getString(R.string.task_clarify_title, Math.min(at, total), total);
    }

    private void begin() {
        TaskStore.Stored item = queue.get(0);
        answer = new Clarification();
        answer.nextAction = item.task.summary();
        answer.contexts.addAll(item.task.contexts());
        answer.parent = item.task.parent() == null ? "" : item.task.parent();
        step = Step.ACTIONABLE;
        render();
    }

    private void render() {
        sections.clear();
        host.updateBarTitle(title());
        TaskStore.Stored item = queue.get(0);
        LinearLayout page = host.findViewById(R.id.clarify_page);

        List<View> shown = new ArrayList<>();
        shown.add(
                sections.row(
                        item.task.summary().isEmpty()
                                ? host.getString(R.string.event_untitled)
                                : item.task.summary(),
                        item.task.description(),
                        null,
                        null));
        if (item.task.source() != null) {
            shown.add(
                    sections.row(
                            host.getString(R.string.task_open_mail),
                            null,
                            null,
                            () -> host.io.execute(() -> {
                                String[] found = list.store().mailOf(item.task.source());
                                host.postAlive(() -> {
                                    if (found == null) {
                                        host.toast(host.getString(R.string.task_mail_gone));
                                    } else {
                                        openMail.accept(found[0], found[1]);
                                    }
                                });
                            })));
        }
        sections.section(0, 0, shown, null, false);

        switch (step) {
            case ACTIONABLE:
                question(page, R.string.clarify_actionable, R.string.clarify_actionable_hint);
                options(
                        option(R.string.clarify_yes_something, 0, () -> {
                            answer.actionable = true;
                            go(Step.TWO_MINUTES);
                        }, Boolean.TRUE.equals(answer.actionable)),
                        option(R.string.clarify_someday, R.string.clarify_someday_hint, () -> {
                            answer.actionable = false;
                            answer.kept = Clarification.Kept.SOMEDAY;
                            apply();
                        }, answer.kept == Clarification.Kept.SOMEDAY),
                        option(R.string.clarify_reference, R.string.clarify_reference_hint, () -> {
                            answer.actionable = false;
                            answer.kept = Clarification.Kept.REFERENCE;
                            apply();
                        }, answer.kept == Clarification.Kept.REFERENCE),
                        option(R.string.clarify_drop, 0, () -> {
                            answer.actionable = false;
                            answer.kept = Clarification.Kept.DROPPED;
                            apply();
                        }, answer.kept == Clarification.Kept.DROPPED));
                break;
            case TWO_MINUTES:
                question(page, R.string.clarify_two_minutes, R.string.clarify_two_minutes_hint);
                options(
                        option(R.string.clarify_done_now, 0, () -> {
                            answer.twoMinutes = true;
                            apply();
                        }, Boolean.TRUE.equals(answer.twoMinutes)),
                        option(R.string.clarify_longer, 0, () -> {
                            answer.twoMinutes = false;
                            go(Step.DELEGATED);
                        }, Boolean.FALSE.equals(answer.twoMinutes)));
                break;
            case DELEGATED:
                question(page, R.string.clarify_delegate, R.string.clarify_delegate_hint);
                options(
                        option(R.string.clarify_delegate_yes, 0, () ->
                                TaskDialogs.text(
                                        host,
                                        R.string.task_field_waiting_on,
                                        answer.delegateTo == null ? "" : answer.delegateTo,
                                        false,
                                        who -> {
                                            answer.delegated = true;
                                            answer.delegateTo = who;
                                            apply();
                                        }), Boolean.TRUE.equals(answer.delegated)),
                        option(R.string.clarify_mine, 0, () -> {
                            answer.delegated = false;
                            go(Step.PROJECT);
                        }, Boolean.FALSE.equals(answer.delegated)));
                break;
            case PROJECT:
                question(page, R.string.clarify_project, R.string.clarify_project_hint);
                options(
                        option(R.string.clarify_project_yes, R.string.clarify_project_yes_hint, () -> {
                            answer.project = true;
                            answer.nextAction = "";
                            go(Step.NEXT_ACTION);
                        }, Boolean.TRUE.equals(answer.project)),
                        option(R.string.clarify_one_step, 0, () -> {
                            answer.project = false;
                            go(Step.NEXT_ACTION);
                        }, Boolean.FALSE.equals(answer.project)));
                break;
            default:
                nextAction(page);
        }

        List<View> skip = new ArrayList<>();
        skip.add(sections.row(host.getString(R.string.clarify_skip), null, null, this::skip));
        sections.section(0, 0, skip, null, false);
    }

    private void go(Step next) {
        step = next;
        render();
    }

    private void question(LinearLayout page, int text, int hint) {
        TextView title = new TextView(host);
        title.setText(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
        title.setPadding(host.dp(20), host.dp(24), host.dp(20), 0);
        page.addView(title);
        TextView line = new TextView(host);
        line.setText(hint);
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        line.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
        line.setPadding(host.dp(20), host.dp(6), host.dp(20), 0);
        page.addView(line);
    }

    /** One answer: its label, an optional line, and whether it is chosen. */
    private View option(int label, int hint, Runnable pick, boolean chosen) {
        View row =
                sections.row(
                        host.getString(label),
                        hint == 0 ? null : host.getString(hint),
                        null,
                        pick);
        if (chosen) {
            row.setBackgroundResource(R.drawable.button_tonal);
        }
        return row;
    }

    private void options(View... rows) {
        List<View> items = new ArrayList<>(List.of(rows));
        sections.section(0, 0, items, null, false);
    }

    /** The last step: the next action, where it is done, its project. */
    private void nextAction(LinearLayout page) {
        question(page, R.string.clarify_next_action, R.string.clarify_next_action_hint);
        List<View> rows = new ArrayList<>();
        rows.add(
                sections.row(
                        answer.nextAction.isEmpty()
                                ? host.getString(R.string.clarify_next_action_empty)
                                : answer.nextAction,
                        host.getString(R.string.clarify_next_action_edit),
                        null,
                        () -> TaskDialogs.text(
                                host, R.string.clarify_next_action, answer.nextAction, false,
                                value -> {
                                    answer.nextAction = value;
                                    render();
                                })));
        Set<String> known = new LinkedHashSet<>(list.contexts());
        known.addAll(answer.contexts);
        rows.add(
                sections.row(
                        host.getString(R.string.task_field_context),
                        answer.contexts.isEmpty()
                                ? host.getString(R.string.task_none)
                                : String.join(", ", answer.contexts),
                        null,
                        () -> pickContexts(new ArrayList<>(known))));
        if (!Boolean.TRUE.equals(answer.project)) {
            TaskStore.Stored project = list.projectOf(answer.parent);
            rows.add(
                    sections.row(
                            host.getString(R.string.task_field_project),
                            project == null
                                    ? host.getString(R.string.task_none)
                                    : project.task.summary(),
                            null,
                            this::pickProject));
        }
        rows.add(
                sections.row(
                        host.getString(R.string.task_field_due),
                        answer.due.isEmpty()
                                ? host.getString(R.string.task_add_date)
                                : org.pimalaya.ui.Dates.date(host, TaskPage.dayStamp(answer.due)),
                        null,
                        () -> TaskDialogs.date(host, answer.due, picked -> {
                            answer.due = picked;
                            render();
                        })));
        sections.section(0, 0, rows, null, false);

        List<View> save = new ArrayList<>();
        save.add(
                sections.row(
                        host.getString(R.string.clarify_save_next),
                        null,
                        null,
                        () -> {
                            if (answer.nextAction.isEmpty()) {
                                host.toast(host.getString(R.string.clarify_next_action_empty));
                                return;
                            }
                            apply();
                        }));
        sections.section(0, 0, save, null, false);
    }

    private void pickContexts(List<String> names) {
        CharSequence[] labels = names.toArray(new CharSequence[0]);
        boolean[] checked = new boolean[names.size()];
        for (int index = 0; index < names.size(); index++) {
            checked[index] = answer.contexts.contains(names.get(index));
        }
        new android.app.AlertDialog.Builder(host)
                .setTitle(R.string.task_field_context)
                .setMultiChoiceItems(labels, checked, (dialog, which, on) -> checked[which] = on)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    answer.contexts.clear();
                    for (int index = 0; index < names.size(); index++) {
                        if (checked[index]) {
                            answer.contexts.add(names.get(index));
                        }
                    }
                    render();
                })
                .setNeutralButton(R.string.task_context_new, (dialog, which) ->
                        TaskDialogs.text(host, R.string.task_context_new, "@", false, value -> {
                            if (!value.isEmpty() && !answer.contexts.contains(value)) {
                                answer.contexts.add(value);
                            }
                            render();
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickProject() {
        List<TaskStore.Stored> projects = list.projects();
        CharSequence[] labels = new CharSequence[projects.size() + 1];
        labels[0] = host.getString(R.string.task_none);
        int checked = 0;
        for (int index = 0; index < projects.size(); index++) {
            labels[index + 1] = projects.get(index).task.summary();
            if (projects.get(index).task.uid().equals(answer.parent)) {
                checked = index + 1;
            }
        }
        TaskDialogs.pick(host, R.string.task_field_project, labels, checked, picked -> {
            answer.parent = picked == 0 ? "" : projects.get(picked - 1).task.uid();
            render();
        });
    }

    /** Leaves the item first in line for the next pass, and moves on. */
    private void skip() {
        queue.remove(0);
        total = Math.max(total - 1, queue.size());
        next();
    }

    /** Writes what the answers decide, then moves to the next item. */
    private void apply() {
        TaskStore.Stored item = queue.get(0);
        Clarification decided = answer;
        String now = TaskWrites.now();
        host.io.execute(
                () -> {
                    try {
                        write(item, decided, now);
                    } catch (Exception error) {
                        Log.w("pimalaya", "clarify failed: " + item.event.id, error);
                        host.postAlive(() -> host.showError(error, R.string.task_save_failed));
                        return;
                    }
                    host.postAlive(
                            () -> {
                                queue.remove(0);
                                next();
                            });
                });
    }

    /** The write a clarification makes; off the main thread. */
    private void write(TaskStore.Stored item, Clarification decided, String now) throws Exception {
        TaskEdit edit = TaskEdit.at(now);
        if (Boolean.FALSE.equals(decided.actionable)) {
            switch (decided.kept) {
                case SOMEDAY:
                    edit = edit.withList(Task.SOMEDAY);
                    break;
                case REFERENCE:
                    edit = edit.withList(Task.REFERENCE);
                    break;
                default:
                    edit = edit.withCancelled();
            }
            writes.edit(item, edit);
            return;
        }
        if (Boolean.TRUE.equals(decided.twoMinutes)) {
            writes.edit(item, edit.withList(Task.NEXT).withDone(true));
            return;
        }
        if (Boolean.TRUE.equals(decided.delegated)) {
            String who = decided.delegateTo == null ? "" : decided.delegateTo;
            boolean address = who.contains("@") && !who.contains(" ");
            writes.edit(
                    item,
                    edit.withList(Task.WAITING)
                            .withContact(address ? "" : who, address ? who : ""));
            return;
        }

        DateEdit due =
                decided.due.isEmpty()
                        ? null
                        : new DateEdit(decided.due, EventTimeKind.DATE, "");
        if (Boolean.TRUE.equals(decided.project)) {
            // NOTE: the captured item becomes the project, its outcome; the
            // next action is a new to-do under it, in the same calendar.
            writes.edit(item, edit.withList(Task.PROJECT));
            TaskEdit action =
                    TaskEdit.at(now)
                            .withText(decided.nextAction, null)
                            .withContexts(String.join(",", decided.contexts))
                            .withParent(item.task.uid());
            if (due != null) {
                action = action.withDue(due);
            }
            writes.create(item.calendar, action);
            return;
        }

        edit =
                edit.withList(Task.NEXT)
                        .withText(decided.nextAction, null)
                        .withContexts(String.join(",", decided.contexts))
                        .withParent(decided.parent);
        if (due != null) {
            edit = edit.withDue(due);
        }
        writes.edit(item, edit);
    }

    private void next() {
        if (queue.isEmpty()) {
            list.show(Task.INBOX);
            host.showBack(Panels.TASKS);
            return;
        }
        begin();
    }
}
