package org.pimalaya.task;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.pimalaya.R;
import org.pimalaya.client.Task;
import org.pimalaya.ui.Dates;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.Panels;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The weekly review: GTD's checklist, each step a count over the last load.
 *
 * <p>Nothing guessed: the Inbox left, the calendar, the active tasks, Waiting
 * For by age with the replies received, Someday. A step opens what it counts
 * and is ticked; Finish, held at the bottom, keeps the day on the device,
 * which the Tasks bar's review button then counts from.
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
    private final LinearLayout steps;
    private final Set<Step> ticked = new HashSet<>();

    /** The review over {@code list}'s load; {@code openCalendar} shows the agenda. */
    public WeeklyReview(Host host, TasksList list, Runnable openCalendar) {
        this.host = host;
        this.list = list;
        this.openCalendar = openCalendar;
        this.steps = host.findViewById(R.id.review_steps);
        TextView finish = host.findViewById(R.id.review_finish);
        finish.setTextColor(host.accentContrast());
        finish.setOnClickListener(view -> finish());
    }

    /** Opens the checklist, the steps ticked since the last Finish kept. */
    public void open() {
        render();
        host.showInstant(Panels.REVIEW);
    }

    /** Draws the checklist again on return from a step. */
    public void refresh() {
        render();
    }

    private void render() {
        steps.removeAllViews();
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
        step(Step.INBOX, R.string.review_inbox,
                inbox == 0
                        ? host.getString(R.string.review_inbox_empty)
                        : host.getString(R.string.review_inbox_left, inbox),
                () -> {
                    if (left > 0) {
                        list.clarify.open(list.inbox());
                    }
                });
        step(Step.CALENDAR, R.string.review_calendar,
                host.getString(R.string.review_calendar_line), openCalendar);
        step(Step.NEXT, R.string.review_next,
                host.getString(R.string.review_next_line, next),
                () -> showList(Task.NEXT));
        step(Step.WAITING, R.string.review_waiting,
                host.getString(R.string.review_waiting_line, waiting, replied, late),
                () -> showList(Task.WAITING));
        step(Step.SOMEDAY, R.string.review_someday,
                host.getString(R.string.review_someday_line, someday),
                () -> showList(Task.SOMEDAY));

        long at =
                host.getSharedPreferences(TasksList.PREFERENCES, 0)
                        .getLong(TasksList.REVIEWED, 0);
        int total = Step.values().length;
        ((TextView) host.findViewById(R.id.review_meta))
                .setText(
                        at == 0
                                ? host.getString(R.string.review_meta, ticked.size(), total)
                                : host.getString(
                                        R.string.review_meta_last,
                                        Dates.dayMonth(at),
                                        ticked.size(),
                                        total));
    }

    /**
     * One step: its number in a ring, or a check on an accent disc once done,
     * its title and its count, and a chevron while it is left to do.
     */
    private void step(Step step, int title, String line, Runnable open) {
        boolean done = ticked.contains(step);
        int size = host.dp(26);

        View mark;
        if (done) {
            ImageView check = new ImageView(host);
            check.setImageResource(R.drawable.ic_check);
            check.setImageTintList(ColorStateList.valueOf(host.accentContrast()));
            check.setBackgroundResource(R.drawable.review_step_done);
            check.setPadding(host.dp(5), host.dp(5), host.dp(5), host.dp(5));
            mark = check;
        } else {
            TextView number = new TextView(host);
            number.setText(String.valueOf(step.ordinal() + 1));
            number.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            number.setTypeface(null, Typeface.BOLD);
            number.setTextColor(host.resolveColor(android.R.attr.textColorSecondary));
            number.setGravity(Gravity.CENTER);
            number.setBackgroundResource(R.drawable.review_step);
            mark = number;
        }
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(size, size);
        markParams.topMargin = host.dp(14);

        TextView name = new TextView(host);
        name.setText(title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTextColor(
                host.resolveColor(
                        done
                                ? android.R.attr.textColorSecondary
                                : android.R.attr.textColorPrimary));
        TextView count = new TextView(host);
        count.setText(line);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        count.setTextColor(host.resolveColor(android.R.attr.textColorSecondary));
        LinearLayout text = new LinearLayout(host);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(name);
        text.addView(count);

        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(0, host.dp(12), host.dp(16), host.dp(12));
        content.addView(
                text,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (!done) {
            ImageView chevron = new ImageView(host);
            chevron.setImageResource(R.drawable.ic_chevron_right);
            chevron.setImageTintList(
                    ColorStateList.valueOf(host.resolveColor(android.R.attr.textColorSecondary)));
            content.addView(chevron, new LinearLayout.LayoutParams(host.dp(20), host.dp(20)));
        }

        // NOTE: the hairline sits over the text alone, past the mark, as
        // between the rows of every grouped list.
        LinearLayout body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        if (steps.getChildCount() > 0) {
            View divider = new View(host);
            divider.setBackgroundColor(host.getColor(R.color.divider_light));
            body.addView(
                    divider,
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
        body.addView(content);

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(host.dp(14), 0, 0, 0);
        row.setBackgroundResource(host.ui.resolveAttr(android.R.attr.selectableItemBackground));
        row.addView(mark, markParams);
        LinearLayout.LayoutParams bodyParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bodyParams.setMarginStart(host.dp(12));
        row.addView(body, bodyParams);
        row.setOnClickListener(
                view -> {
                    ticked.add(step);
                    render();
                    open.run();
                });
        steps.addView(row);
    }

    private void showList(String target) {
        list.show(target);
        host.showInstant(Panels.TASKS);
    }

    private void finish() {
        ticked.clear();
        host.getSharedPreferences(TasksList.PREFERENCES, 0)
                .edit()
                .putLong(TasksList.REVIEWED, System.currentTimeMillis())
                .apply();
        list.reload();
        host.showInstant(Panels.TASKS);
    }
}
