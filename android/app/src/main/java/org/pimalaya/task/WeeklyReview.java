package org.pimalaya.task;

import android.view.View;
import android.widget.LinearLayout;

import org.pimalaya.R;
import org.pimalaya.client.Task;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.Panels;
import org.pimalaya.ui.Sections;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The weekly review: GTD's checklist, each step a count over the last load.
 *
 * <p>Nothing guessed: the Inbox left, the calendar, the open next actions,
 * Waiting For by age with the replies received, Someday. A step opens what
 * it counts and is ticked; Finish keeps the day on the device, which the
 * Tasks header then counts from.
 */
public final class WeeklyReview {
    private enum Step {
        INBOX,
        CALENDAR,
        NEXT,
        WAITING,
        SOMEDAY,
    }

    private final Host host;
    private final TasksList list;
    private final Runnable openCalendar;
    private final Sections sections;
    private final Set<Step> ticked = new HashSet<>();

    /** The review over {@code list}'s load; {@code openCalendar} shows the agenda. */
    public WeeklyReview(Host host, TasksList list, Runnable openCalendar) {
        this.host = host;
        this.list = list;
        this.openCalendar = openCalendar;
        this.sections = new Sections(host, (LinearLayout) host.findViewById(R.id.review_page));
    }

    /** Opens the checklist, the steps ticked since the last Finish kept. */
    public void open() {
        render();
        host.show(Panels.REVIEW);
    }

    /** Draws the checklist again on return from a step. */
    public void refresh() {
        render();
    }

    private void render() {
        sections.clear();
        List<TaskStore.Stored> open = list.open();
        int inbox = 0;
        int next = 0;
        int waiting = 0;
        int replied = 0;
        int late = 0;
        int someday = 0;
        long now = System.currentTimeMillis();
        for (TaskStore.Stored stored : open) {
            Task task = stored.task;
            inbox += task.in(Task.INBOX) ? 1 : 0;
            next += task.in(Task.NEXT) ? 1 : 0;
            someday += task.in(Task.SOMEDAY) ? 1 : 0;
            if (task.in(Task.WAITING)) {
                waiting++;
                if (list.replied(stored)) {
                    replied++;
                }
                long since = TaskStore.capturedAt(task);
                if (since > 0 && now - since > 14 * 86_400_000L) {
                    late++;
                }
            }
        }

        int left = inbox;
        List<View> steps = new ArrayList<>();
        steps.add(step(Step.INBOX, R.string.review_inbox,
                inbox == 0
                        ? host.getString(R.string.review_inbox_empty)
                        : host.getString(R.string.review_inbox_left, inbox),
                () -> {
                    if (left > 0) {
                        list.clarify.open(list.inbox());
                    }
                }));
        steps.add(step(Step.CALENDAR, R.string.review_calendar,
                host.getString(R.string.review_calendar_line), openCalendar));
        steps.add(step(Step.NEXT, R.string.review_next,
                host.getString(R.string.review_next_line, next),
                () -> showList(Task.NEXT)));
        steps.add(step(Step.WAITING, R.string.review_waiting,
                host.getString(R.string.review_waiting_line, waiting, replied, late),
                () -> showList(Task.WAITING)));
        steps.add(step(Step.SOMEDAY, R.string.review_someday,
                host.getString(R.string.review_someday_line, someday),
                () -> showList(Task.SOMEDAY)));
        sections.section(R.string.task_review, R.drawable.ic_task_review, steps, null, false);

        List<View> finish = new ArrayList<>();
        finish.add(sections.row(host.getString(R.string.review_finish), null, null, this::finish));
        sections.section(0, 0, finish, null, false);
    }

    private View step(Step step, int title, String line, Runnable open) {
        android.widget.ImageView tick = new android.widget.ImageView(host);
        tick.setImageResource(ticked.contains(step) ? R.drawable.ic_check_box : R.drawable.ic_check_box_blank);
        tick.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        host.ui.resolveColor(
                                ticked.contains(step)
                                        ? android.R.attr.colorAccent
                                        : android.R.attr.textColorSecondary)));
        return sections.row(host.getString(title), line, tick, () -> {
            ticked.add(step);
            render();
            open.run();
        });
    }

    private void showList(String target) {
        list.show(target);
        host.show(Panels.TASKS);
    }

    private void finish() {
        ticked.clear();
        host.getSharedPreferences(TasksList.PREFERENCES, 0)
                .edit()
                .putLong(TasksList.REVIEWED, System.currentTimeMillis())
                .apply();
        list.reload();
        host.showBack(Panels.TASKS);
    }
}
