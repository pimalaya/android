package org.pimalaya.ui;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.EditText;

/** Theme and density helpers every hand-built view needs, resolved once. */
public final class Ui {
    private final Context context;

    /** Helpers resolving against {@code context}'s theme. */
    public Ui(Context context) {
        this.context = context;
    }

    /** Resolves a theme attribute to its referenced resource id. */
    public int resolveAttr(int attr) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        return value.resourceId;
    }

    /** Resolves a theme colour attribute to an ARGB int. */
    public int resolveColor(int attr) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        if (value.resourceId != 0) {
            return context.getResources().getColor(value.resourceId, context.getTheme());
        }
        return value.data;
    }

    /** Density-independent pixels to raw pixels. */
    public int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * A two-line addressbook label: the name over the diminished account email.
     *
     * <p>Long names and emails never wrap into one unreadable line.
     */
    public CharSequence twoLine(String name, String email) {
        android.text.SpannableString label = new android.text.SpannableString(name + "\n" + email);
        int start = name.length() + 1;
        label.setSpan(
                new android.text.style.RelativeSizeSpan(0.8f),
                start,
                label.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(
                new android.text.style.ForegroundColorSpan(
                        resolveColor(android.R.attr.textColorSecondary)),
                start,
                label.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }

    /**
     * One popup entry carrying its own action.
     *
     * <p>Text-only: the framework popup renders forced icons flush against
     * their labels on some Android releases.
     */
    public static void item(android.widget.PopupMenu menu, int label, Runnable action) {
        menu.getMenu()
                .add(label)
                .setOnMenuItemClickListener(
                        entry -> {
                            action.run();
                            return true;
                        });
    }

    /** A plain no-suggestions text field, prefilled (dialog forms). */
    EditText field(int hint, String value) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setText(value);
        field.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return field;
    }
}
