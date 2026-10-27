package org.pimalaya.task;

import android.app.Dialog;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import org.pimalaya.R;
import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.ui.Host;

import java.util.List;

/**
 * The capture sheet: a line saved to the Inbox at once, offline included.
 *
 * <p>One sheet for every way in: the Tasks button, the home-screen widget and
 * launcher shortcut, the share sheet, and a mail's Add to tasks, which links
 * the message by {@code mid:} and leaves it where it is.
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
        if (targets.isEmpty()) {
            host.toast(host.getString(R.string.capture_no_calendar));
            return;
        }
        EventStore.StoredCalendar[] target = {targets.get(0)};

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

        TextView where = dialog.findViewById(R.id.capture_target);
        Runnable drawTarget =
                () -> where.setText(
                        host.ui.twoLine(
                                host.getString(R.string.capture_into, target[0].name),
                                target[0].accountEmail));
        drawTarget.run();
        where.setOnClickListener(
                view -> {
                    CharSequence[] labels = new CharSequence[targets.size()];
                    for (int index = 0; index < labels.length; index++) {
                        labels[index] =
                                host.ui.twoLine(
                                        targets.get(index).name, targets.get(index).accountEmail);
                    }
                    TaskDialogs.pick(
                            host,
                            R.string.capture_where,
                            labels,
                            targets.indexOf(target[0]),
                            picked -> {
                                target[0] = targets.get(picked);
                                drawTarget.run();
                            });
                });

        dialog.findViewById(R.id.capture_cancel).setOnClickListener(view -> dialog.dismiss());
        dialog.findViewById(R.id.capture_save)
                .setOnClickListener(
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
