package org.pimalaya;

import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;

/**
 * A round text field with its name as the placeholder: a pill 56dp
 * high, ringed in the accent while focused, a secret one ending in the
 * show/hide toggle. On a card it takes the page tone, on a page the
 * surface tone, so it always reads against what is around it.
 */
final class PillField {
    final LinearLayout view;
    final EditText input;

    private PillField(LinearLayout view, EditText input) {
        this.view = view;
        this.input = input;
    }

    static PillField of(MainActivity host, int hint, String value, boolean secret, boolean onCard) {
        LinearLayout pill = new LinearLayout(host);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackgroundResource(onCard ? R.drawable.field_on_card : R.drawable.field_background);
        pill.setPadding(host.ui.dp(24), 0, host.ui.dp(secret ? 4 : 24), 0);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, host.ui.dp(56));
        params.topMargin = host.ui.dp(10);
        pill.setLayoutParams(params);

        EditText input = new EditText(host);
        input.setBackground(null);
        input.setPadding(0, 0, 0, 0);
        input.setSingleLine(true);
        input.setTextSize(17);
        input.setHint(hint);
        input.setText(value == null ? "" : value);
        input.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | (secret
                                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                                : InputType.TYPE_TEXT_VARIATION_URI));
        // NOTE: the password input type sets a monospace face; every field
        // keeps the same one.
        input.setTypeface(android.graphics.Typeface.DEFAULT);
        // NOTE: the pill rings while its field has focus.
        pill.setAddStatesFromChildren(true);
        pill.addView(
                input,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        if (secret) {
            ImageButton toggle = new ImageButton(host);
            toggle.setBackgroundResource(R.drawable.ripple_circle);
            toggle.setImageResource(R.drawable.ic_visibility);
            toggle.setContentDescription(host.getString(R.string.password_show));
            toggle.setOnClickListener(
                    view -> {
                        boolean shown =
                                input.getTransformationMethod()
                                        instanceof PasswordTransformationMethod;
                        input.setTransformationMethod(
                                shown ? null : PasswordTransformationMethod.getInstance());
                        input.setSelection(input.getText().length());
                        toggle.setImageResource(
                                shown ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
                        toggle.setContentDescription(
                                host.getString(
                                        shown ? R.string.password_hide : R.string.password_show));
                    });
            pill.addView(toggle, new LinearLayout.LayoutParams(host.ui.dp(48), host.ui.dp(48)));
        }
        return new PillField(pill, input);
    }

    String text() {
        return input.getText().toString().trim();
    }
}
