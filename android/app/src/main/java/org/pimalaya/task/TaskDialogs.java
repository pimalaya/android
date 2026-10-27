package org.pimalaya.task;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.pimalaya.R;
import org.pimalaya.ui.Host;

import java.util.Calendar;
import java.util.Locale;
import java.util.function.Consumer;

/** The small dialogs the task screens edit one value with. */
final class TaskDialogs {
    private TaskDialogs() {}

    /** Asks for one text, {@code value} prefilled; empty clears it. */
    static void text(Host host, int title, String value, boolean multiline, Consumer<String> done) {
        EditText input = new EditText(host);
        input.setText(value);
        input.setSelection(input.getText().length());
        input.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        FrameLayout wrap = new FrameLayout(host);
        wrap.setPadding(host.dp(20), host.dp(8), host.dp(20), 0);
        wrap.addView(input);
        new AlertDialog.Builder(host)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> done.accept(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        input.requestFocus();
        host.showKeyboard();
    }

    /**
     * Asks for a day, {@code value} a civil {@code YYYYMMDD} or empty.
     *
     * <p>Answers the day picked, or empty when cleared.
     */
    static void date(Host host, String value, Consumer<String> done) {
        Calendar calendar = Calendar.getInstance();
        if (value != null && value.length() >= 8) {
            calendar.set(
                    Integer.parseInt(value.substring(0, 4)),
                    Integer.parseInt(value.substring(4, 6)) - 1,
                    Integer.parseInt(value.substring(6, 8)));
        }
        DatePickerDialog dialog =
                new DatePickerDialog(
                        host,
                        (picker, year, month, day) ->
                                done.accept(
                                        String.format(
                                                Locale.US, "%04d%02d%02d", year, month + 1, day)),
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH));
        dialog.setButton(
                DatePickerDialog.BUTTON_NEUTRAL,
                host.getString(R.string.clear),
                (picker, which) -> done.accept(""));
        dialog.show();
    }

    /** Lets the user pick one of {@code labels}, {@code checked} preselected. */
    static void pick(
            Host host, int title, CharSequence[] labels, int checked, Consumer<Integer> done) {
        new AlertDialog.Builder(host)
                .setTitle(title)
                .setSingleChoiceItems(
                        labels,
                        checked,
                        (dialog, which) -> {
                            dialog.dismiss();
                            done.accept(which);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
