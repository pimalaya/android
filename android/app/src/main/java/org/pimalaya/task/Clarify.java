package org.pimalaya.task;

import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.pimalaya.R;
import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.EventStore;
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
 * <p>Actionable? Under two minutes? Someone else's? Then the next action.
 * Every outcome is an edit of the same item, its {@code inbox} category
 * replaced by {@code waiting}, {@code someday} or nothing (pimdir GUIDE §17),
 * and none puts it back: a skip leaves it first in line for the next pass.
 * The answers build a {@link Clarification}, applied as one write when the
 * flow ends; filing it in another calendar is the one outcome that is two.
 */
public final class Clarify {
    private enum Step {
        ACTIONABLE,
        TWO_MINUTES,
        DELEGATED,
        NEXT_ACTION,
    }

    private final Host host;
    private final TasksList list;
    private final TaskWrites writes;
    private final Sections sections;
    private final BiConsumer<String, String> openMail;

    /** The items left to clarify, the shown one first. */
    private final List<TaskStore.Stored> queue = new ArrayList<>();

    /** The calendars an item can be filed in, read as the flow opens. */
    private List<EventStore.StoredCalendar> targets = List.of();

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
        host.io.execute(
                () -> {
                    List<EventStore.StoredCalendar> read = list.store().targets();
                    host.postAlive(
                            () -> {
                                targets = read;
                                begin();
                                host.show(Panels.CLARIFY);
                            });
                });
    }

    /** What the bar says: which item of how many. */
    public String title() {
        int at = total - queue.size() + 1;
        return host.getString(R.string.task_clarify_title, Math.min(at, total), total);
    }

    private void begin() {
        TaskStore.Stored item = queue.get(0);
        answer = new Clarification();
        answer.title = item.task.summary();
        answer.categories.addAll(item.task.categories());
        answer.parent = item.task.parent() == null ? "" : item.task.parent();
        answer.target = item.calendar;
        step = Step.ACTIONABLE;
        render();
    }

    private void render() {
        sections.clear();
        host.updateBarTitle(title());
        TaskStore.Stored item = queue.get(0);
        LinearLayout page = host.findViewById(R.id.clarify_page);

        // NOTE: the title is rephrased in place at any step, as clarifying
        // often starts by saying what the capture really is.
        List<View> shown = new ArrayList<>();
        shown.add(
                sections.row(
                        answer.title.isEmpty()
                                ? host.getString(R.string.event_untitled)
                                : answer.title,
                        item.task.description().isEmpty()
                                ? host.getString(R.string.clarify_rephrase)
                                : item.task.description(),
                        null,
                        this::rephrase));
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
                        option(R.string.clarify_drop, R.string.clarify_drop_hint, () -> {
                            answer.actionable = false;
                            answer.kept = Clarification.Kept.DELETED;
                            apply();
                        }, answer.kept == Clarification.Kept.DELETED));
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
                        option(R.string.clarify_delegate_yes, R.string.clarify_delegate_who, () ->
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
                            go(Step.NEXT_ACTION);
                        }, Boolean.FALSE.equals(answer.delegated)));
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

    private void rephrase() {
        TaskDialogs.text(host, R.string.event_field_summary, answer.title, false, value -> {
            if (!value.isEmpty()) {
                answer.title = value;
            }
            render();
        });
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

    /**
     * The last step: the next action, its categories, the task it sits
     * under, its due date and the calendar it is filed in.
     */
    private void nextAction(LinearLayout page) {
        question(page, R.string.clarify_next_action, R.string.clarify_next_action_hint);
        List<View> rows = new ArrayList<>();
        rows.add(
                sections.row(
                        answer.title.isEmpty()
                                ? host.getString(R.string.clarify_next_action_empty)
                                : answer.title,
                        host.getString(R.string.clarify_next_action_edit),
                        null,
                        this::rephrase));
        Set<String> known = new LinkedHashSet<>(list.categories());
        known.addAll(answer.categories);
        rows.add(
                sections.row(
                        host.getString(R.string.task_field_categories),
                        answer.categories.isEmpty()
                                ? host.getString(R.string.task_none)
                                : String.join(", ", answer.categories),
                        null,
                        () -> pickCategories(new ArrayList<>(known))));
        TaskStore.Stored above = list.taskOf(answer.parent);
        rows.add(
                sections.row(
                        host.getString(R.string.task_field_parent),
                        above == null ? host.getString(R.string.task_none) : above.task.summary(),
                        null,
                        this::pickParent));
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
        if (targets.size() > 1) {
            rows.add(
                    sections.row(
                            host.getString(R.string.task_section_calendar),
                            host.getString(
                                    R.string.capture_target_item,
                                    answer.target.name,
                                    answer.target.accountEmail),
                            null,
                            this::pickTarget));
        }
        sections.section(0, 0, rows, null, false);

        List<View> save = new ArrayList<>();
        save.add(
                sections.row(
                        host.getString(R.string.clarify_save_next),
                        null,
                        null,
                        () -> {
                            if (answer.title.isEmpty()) {
                                host.toast(host.getString(R.string.clarify_next_action_empty));
                                return;
                            }
                            apply();
                        }));
        sections.section(0, 0, save, null, false);
    }

    private void pickCategories(List<String> names) {
        CharSequence[] labels = names.toArray(new CharSequence[0]);
        boolean[] checked = new boolean[names.size()];
        for (int index = 0; index < names.size(); index++) {
            checked[index] = answer.categories.contains(names.get(index));
        }
        new android.app.AlertDialog.Builder(host)
                .setTitle(R.string.task_field_categories)
                .setMultiChoiceItems(labels, checked, (dialog, which, on) -> checked[which] = on)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    answer.categories.clear();
                    for (int index = 0; index < names.size(); index++) {
                        if (checked[index]) {
                            answer.categories.add(names.get(index));
                        }
                    }
                    render();
                })
                .setNeutralButton(R.string.task_category_new, (dialog, which) ->
                        TaskDialogs.text(host, R.string.task_category_new, "", false, value -> {
                            if (!value.isEmpty() && !answer.categories.contains(value)) {
                                answer.categories.add(value);
                            }
                            render();
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickParent() {
        List<TaskStore.Stored> parents = list.parentsFor(queue.get(0));
        CharSequence[] labels = new CharSequence[parents.size() + 1];
        labels[0] = host.getString(R.string.task_none);
        int checked = 0;
        for (int index = 0; index < parents.size(); index++) {
            labels[index + 1] = parents.get(index).task.summary();
            if (parents.get(index).task.uid().equals(answer.parent)) {
                checked = index + 1;
            }
        }
        TaskDialogs.pick(host, R.string.task_field_parent, labels, checked, picked -> {
            answer.parent = picked == 0 ? "" : parents.get(picked - 1).task.uid();
            render();
        });
    }

    private void pickTarget() {
        CharSequence[] labels = new CharSequence[targets.size()];
        int checked = 0;
        for (int index = 0; index < targets.size(); index++) {
            EventStore.StoredCalendar target = targets.get(index);
            labels[index] =
                    host.getString(R.string.capture_target_item, target.name, target.accountEmail);
            if (target.id.equals(answer.target.id)) {
                checked = index;
            }
        }
        TaskDialogs.pick(host, R.string.task_section_calendar, labels, checked, picked -> {
            answer.target = targets.get(picked);
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
        if (decided.kept == Clarification.Kept.DELETED) {
            writes.remove(item);
            return;
        }
        TaskEdit edit = TaskEdit.at(now);
        if (!decided.title.equals(item.task.summary())) {
            edit = edit.withText(decided.title, null);
        }
        if (decided.kept == Clarification.Kept.SOMEDAY) {
            writes.edit(item, edit.withList(Task.SOMEDAY));
            return;
        }
        if (Boolean.TRUE.equals(decided.twoMinutes)) {
            writes.edit(item, edit.withList(Task.NEXT).withDone(true));
            return;
        }
        if (Boolean.TRUE.equals(decided.delegated)) {
            String who = decided.delegateTo == null ? "" : decided.delegateTo;
            edit = edit.withList(Task.WAITING);
            if (!who.isEmpty()) {
                boolean address = who.contains("@") && !who.contains(" ");
                edit = edit.withContact(address ? "" : who, address ? who : "");
            }
            writes.edit(item, edit);
            return;
        }

        edit =
                edit.withList(Task.NEXT)
                        .withCategories(String.join(",", decided.categories))
                        .withParent(decided.parent);
        if (!decided.due.isEmpty()) {
            edit = edit.withDue(new DateEdit(decided.due, EventTimeKind.DATE, ""));
        }
        if (decided.target.id.equals(item.calendar.id)) {
            writes.edit(item, edit);
        } else {
            writes.moveTo(item, decided.target, edit);
        }
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
