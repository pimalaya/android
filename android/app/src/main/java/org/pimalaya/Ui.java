package org.pimalaya;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;

/**
 * Theme and density helpers every hand-built view needs, shared by the
 * screens (MainActivity, ContactForm, the extracted flows) so the
 * resolution logic exists once.
 */
final class Ui {
    private final Context context;

    Ui(Context context) {
        this.context = context;
    }

    /** Resolves a theme attribute to its referenced resource id. */
    int resolveAttr(int attr) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        return value.resourceId;
    }

    /** Resolves a theme colour attribute to an ARGB int. */
    int resolveColor(int attr) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        if (value.resourceId != 0) {
            return context.getResources().getColor(value.resourceId, context.getTheme());
        }
        return value.data;
    }

    /**
     * A row's sync mark: shown while the store holds a change of the item
     * the server has not taken, in the error colour once the server refused
     * it for good.
     */
    void syncMark(ImageView mark, boolean unsynced, boolean refused) {
        mark.setVisibility(unsynced ? View.VISIBLE : View.GONE);
        if (!unsynced) {
            return;
        }
        int color =
                resolveColor(
                        refused ? android.R.attr.colorError : android.R.attr.textColorSecondary);
        mark.setImageTintList(ColorStateList.valueOf(color));
        mark.setContentDescription(
                context.getString(refused ? R.string.row_refused : R.string.row_unsynced));
    }

    /** Density-independent pixels to raw pixels. */
    int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
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
