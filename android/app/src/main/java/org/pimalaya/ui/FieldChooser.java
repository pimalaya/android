package org.pimalaya.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import org.pimalaya.R;

/**
 * The add-field chooser the editable pages share (contacts, calendar entries,
 * tasks): one row per field the item has not got yet, its icon beside its
 * label.
 *
 * <p>No field toasts so; a single one opens at once.
 */
public final class FieldChooser {
    /** One field offered: its icon, its label, and what picking it opens. */
    public record Choice(int icon, int label, Runnable open) {}

    /** Shows {@code choices}, sorted by their localized label. */
    public static void show(Activity activity, Ui ui, List<Choice> choices) {
        if (choices.isEmpty()) {
            Toast.makeText(activity, R.string.add_field_none, Toast.LENGTH_SHORT).show();
            return;
        }
        if (choices.size() == 1) {
            choices.get(0).open().run();
            return;
        }

        // NOTE: locale-aware sort by localized label, so a field is found
        // by name rather than by the catalog's layout order.
        List<Choice> sorted = new ArrayList<>(choices);
        java.text.Collator collator = java.text.Collator.getInstance();
        sorted.sort(
                (left, right) ->
                        collator.compare(
                                activity.getString(left.label()),
                                activity.getString(right.label())));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        // NOTE: bottom padding mirrors the framework title's top padding
        // so the dialog is symmetric and the last row clears the corner.
        list.setPadding(0, ui.dp(8), 0, ui.dp(18));
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(list);

        AlertDialog dialog =
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.contact_add_field)
                        .setView(scroller)
                        .create();

        for (Choice choice : sorted) {
            list.addView(row(activity, ui, choice, dialog));
        }
        dialog.show();
    }

    /** One chooser row; picking it closes the chooser and opens the dialog. */
    private static View row(Activity activity, Ui ui, Choice choice, AlertDialog dialog) {
        ImageView iconView = new ImageView(activity);
        iconView.setImageResource(choice.icon());
        iconView.setImageTintList(
                ColorStateList.valueOf(ui.resolveColor(android.R.attr.textColorSecondary)));

        TextView labelView = new TextView(activity);
        labelView.setText(choice.label());
        labelView.setTextColor(ui.resolveColor(android.R.attr.textColorPrimary));
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMarginStart(ui.dp(12));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(24), ui.dp(14), ui.dp(24), ui.dp(14));
        row.setBackgroundResource(ui.resolveAttr(android.R.attr.selectableItemBackground));
        row.addView(iconView, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
        row.addView(labelView, labelParams);
        row.setOnClickListener(
                view -> {
                    dialog.dismiss();
                    choice.open().run();
                });
        return row;
    }

    private FieldChooser() {}
}
