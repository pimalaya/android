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
import org.pimalaya.ui.Host;

import java.util.ArrayList;
import java.util.List;

/**
 * The capture sheet: a line saved to the Inbox at once, offline included, as
 * a to-do carrying {@code CATEGORIES:inbox}.
 *
 * <p>One sheet for every way in: the Tasks button, the home-screen widget and
 * launcher shortcut, the share sheet, and a mail's Add to tasks, which links
 * the message by {@code mid:} and leaves it where it is. Its target is a
 * dropdown of the task calendars, each named with its account, the one the
 * last capture went to preselected; hidden when there is one, and the sheet
 * says why when there is none.
 */
public final class CaptureSheet {
    private final Host host;
    private final TaskStore store;
    private final TaskWrites writes;

    /** Runs after a capture lands, to reload what shows tasks. */
    private final Runnable captured;

    /** The sheet over the calendar store. */
    public CaptureSheet(Host host, CalendarParts parts, Runnable captured) {
        this.host = host;
        this.store = new TaskStore(parts);
        this.writes = new TaskWrites(parts);
        this.captured = captured;
    }

    /** Opens empty. */
    public void open() {
        open("", null, null);
    }

    /**
     * Opens with {@code text} prefilled, linked to the mail {@code source}
     * names (a bare {@code Message-ID}, null for none), described by {@code
     * sourceLabel}.
     */
    public void open(String text, String source, String sourceLabel) {
        host.io.execute(
                () -> {
                    List<EventStore.StoredCalendar> targets = store.targets(host);
                    host.postAlive(() -> show(text, source, sourceLabel, targets));
                });
    }

    private void show(
            String text,
            String source,
            String sourceLabel,
            List<EventStore.StoredCalendar> targets) {
        EventStore.StoredCalendar[] target = {targets.isEmpty() ? null : targets.get(0)};

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
                    dialog.dismiss();
                    save(target[0], title, note.getText().toString().trim(), source);
                });
        dialog.show();
        input.requestFocus();
    }

    private void save(EventStore.StoredCalendar target, String title, String note, String source) {
        TaskStore.captureTo(host, target.id);
        host.io.execute(
                () -> {
                    try {
                        writes.capture(target, title, note, source);
                    } catch (Exception error) {
                        Log.w("pimalaya", "capture failed", error);
                        host.postAlive(() -> host.showError(error, R.string.task_save_failed));
                        return;
                    }
                    host.postAlive(
                            () -> {
                                host.toast(host.getString(R.string.capture_saved));
                                captured.run();
                            });
                });
    }
}
