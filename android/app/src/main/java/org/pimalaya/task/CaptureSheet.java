package org.pimalaya.task;

import android.app.Dialog;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import org.pimalaya.R;
import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.Task;
import org.pimalaya.ui.ChipField;
import org.pimalaya.ui.Chips;
import org.pimalaya.ui.Host;

import java.util.ArrayList;
import java.util.List;

/**
 * The task sheet: a task saved at once, offline included, as a dateless to-do
 * (cairn/changes/tasks-for-everyone).
 *
 * <p>One sheet for every way in: the Tasks add button, the share sheet and a
 * mail's Add to tasks, which links the message by {@code mid:} and leaves it
 * where it is, open it with no state; the home-screen widget and launcher
 * shortcut, both a capture, with Inbox on. Its state chips, Inbox, Waiting and
 * Someday, keep at most one on, none being a next action; its categories are
 * the person's own tags. Its target is a dropdown of the task calendars, each
 * named with its account, the one the last task went to preselected; hidden
 * when there is one, and the sheet says why when there is none.
 */
public final class CaptureSheet {
    /** The states the chips set, in GTD's order. */
    private static final String[] STATES = {Task.INBOX, Task.WAITING, Task.SOMEDAY};

    private static final int[] STATE_LABELS = {
        R.string.task_list_inbox, R.string.task_list_waiting, R.string.task_list_someday,
    };

    private static final int[] STATE_ICONS = {
        R.drawable.ic_task_inbox, R.drawable.ic_task_waiting, R.drawable.ic_task_someday,
    };

    private final Host host;
    private final TaskStore store;
    private final TaskWrites writes;

    /** Runs after a task lands, to reload what shows tasks. */
    private final Runnable captured;

    /** The sheet over the calendar store. */
    public CaptureSheet(Host host, CalendarParts parts, Runnable captured) {
        this.host = host;
        this.store = new TaskStore(parts);
        this.writes = new TaskWrites(parts);
        this.captured = captured;
    }

    /** Opens empty, no state on: the add button's new task. */
    public void open() {
        open("", null, null);
    }

    /** Opens empty with Inbox on: the widget's and the shortcut's capture. */
    public void capture() {
        open("", null, null, Task.INBOX);
    }

    /**
     * Opens with {@code text} prefilled, no state on, linked to the mail
     * {@code source} names (a bare {@code Message-ID}, null for none),
     * described by {@code sourceLabel}.
     */
    public void open(String text, String source, String sourceLabel) {
        open(text, source, sourceLabel, Task.NEXT);
    }

    private void open(String text, String source, String sourceLabel, String state) {
        host.io.execute(
                () -> {
                    List<EventStore.StoredCalendar> targets = store.targets(host);
                    host.postAlive(() -> show(text, source, sourceLabel, state, targets));
                });
    }

    private void show(
            String text,
            String source,
            String sourceLabel,
            String state,
            List<EventStore.StoredCalendar> targets) {
        EventStore.StoredCalendar[] target = {targets.isEmpty() ? null : targets.get(0)};
        String[] picked = {state};

        Dialog dialog = new Dialog(host, R.style.SyncSheet);
        dialog.setContentView(R.layout.capture_sheet);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.BOTTOM);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

        if (source != null) {
            ((TextView) dialog.findViewById(R.id.capture_title)).setText(R.string.capture_from_mail);
            TextView linked = dialog.findViewById(R.id.capture_source);
            linked.setText(host.getString(R.string.capture_linked, sourceLabel));
            linked.setVisibility(View.VISIBLE);
        }

        EditText input = dialog.findViewById(R.id.capture_text);
        input.setText(text);
        input.setSelection(input.getText().length());
        EditText note = dialog.findViewById(R.id.capture_note);
        TextView addNote = dialog.findViewById(R.id.capture_add_note);
        addNote.setOnClickListener(
                view -> {
                    note.setVisibility(View.VISIBLE);
                    addNote.setVisibility(View.GONE);
                    note.requestFocus();
                });

        List<TextView> states =
                Chips.exclusive(
                        host,
                        dialog.findViewById(R.id.capture_states),
                        STATE_LABELS,
                        STATE_ICONS,
                        index -> picked[0] = index < 0 ? Task.NEXT : STATES[index]);
        for (int index = 0; index < STATES.length; index++) {
            Chips.draw(host, states.get(index), STATE_ICONS[index], STATES[index].equals(state));
        }
        ChipField categories = dialog.findViewById(R.id.capture_categories);
        categories.tags(R.string.task_remove_category);

        Spinner where = dialog.findViewById(R.id.capture_target);
        if (targets.size() > 1) {
            List<CharSequence> labels = new ArrayList<>();
            for (EventStore.StoredCalendar calendar : targets) {
                labels.add(
                        host.getString(
                                R.string.capture_target_item,
                                calendar.name,
                                calendar.accountEmail));
            }
            ArrayAdapter<CharSequence> adapter =
                    new ArrayAdapter<>(host, android.R.layout.simple_spinner_item, labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            where.setAdapter(adapter);
            where.setOnItemSelectedListener(
                    new AdapterView.OnItemSelectedListener() {
                        @Override
                        public void onItemSelected(
                                AdapterView<?> parent, View view, int position, long id) {
                            target[0] = targets.get(position);
                        }

                        @Override
                        public void onNothingSelected(AdapterView<?> parent) {}
                    });
            where.setVisibility(View.VISIBLE);
            dialog.findViewById(R.id.capture_where).setVisibility(View.VISIBLE);
        }
        View save = dialog.findViewById(R.id.capture_save);
        if (targets.isEmpty()) {
            dialog.findViewById(R.id.capture_none).setVisibility(View.VISIBLE);
            save.setEnabled(false);
        }

        dialog.findViewById(R.id.capture_cancel).setOnClickListener(view -> dialog.dismiss());
        save.setOnClickListener(
                view -> {
                    String title = input.getText().toString().trim();
                    if (title.isEmpty()) {
                        input.setError(host.getString(R.string.capture_empty));
                        return;
                    }
                    List<String> tags = categories.entries();
                    for (String tag : tags) {
                        if (TaskOrder.reserved(tag)) {
                            host.toast(host.getString(R.string.task_category_reserved, tag));
                            return;
                        }
                    }
                    dialog.dismiss();
                    String body = note.getText().toString().trim();
                    save(target[0], title, body, source, picked[0], tags);
                });
        dialog.show();
        input.requestFocus();
    }

    private void save(
            EventStore.StoredCalendar target,
            String title,
            String note,
            String source,
            String state,
            List<String> categories) {
        TaskStore.captureTo(host, target.id);
        host.io.execute(
                () -> {
                    try {
                        writes.add(target, title, note, source, state, categories);
                    } catch (Exception error) {
                        Log.w("pimalaya", "task add failed", error);
                        host.postAlive(() -> host.showError(error, R.string.task_save_failed));
                        return;
                    }
                    host.postAlive(
                            () -> {
                                host.toast(
                                        host.getString(
                                                Task.INBOX.equals(state)
                                                        ? R.string.capture_saved
                                                        : R.string.task_added));
                                captured.run();
                            });
                });
    }
}
