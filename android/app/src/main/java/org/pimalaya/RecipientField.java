package org.pimalaya;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A composer address field: one removable chip per recipient, wrapping
 * over as many lines as they need, then the input the next one is typed
 * in.
 *
 * <p>An address becomes a chip when a separator is typed (comma,
 * semicolon, space), on the keyboard's next action, and when the field
 * loses focus, so a pasted list splits by itself and nobody has to know
 * that a comma is what separates two. Backspace on an empty input takes
 * the last chip back into it, the way a mistyped address is corrected.
 */
public final class RecipientField extends ViewGroup {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    /** The narrowest the input gets before it wraps to a line of its own. */
    private static final int INPUT_MIN_DP = 96;

    private static final int GAP_DP = 4;

    private final EditText input;
    private final List<String> addresses = new ArrayList<>();

    public RecipientField(Context context, AttributeSet attrs) {
        super(context, attrs);

        input = new EditText(context);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        input.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        input.setPadding(dp(4), dp(8), dp(4), dp(8));
        int hint = attrs == null ? 0 : attrs.getAttributeResourceValue(ANDROID, "hint", 0);
        if (hint != 0) {
            input.setHint(hint);
        }
        addView(input);

        input.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable text) {
                        String typed = text.toString();
                        if (typed.matches(".*[,; ].*")) {
                            commit(typed);
                        }
                    }
                });
        input.setOnEditorActionListener(
                (view, action, event) -> {
                    commit(input.getText().toString());
                    return false;
                });
        input.setOnKeyListener(
                (view, code, event) -> {
                    if (code != KeyEvent.KEYCODE_DEL
                            || event.getAction() != KeyEvent.ACTION_DOWN
                            || input.length() > 0
                            || addresses.isEmpty()) {
                        return false;
                    }
                    String last = addresses.remove(addresses.size() - 1);
                    removeViewAt(getChildCount() - 2);
                    input.setText(last);
                    input.setSelection(last.length());
                    return true;
                });
        input.setOnFocusChangeListener(
                (view, focused) -> {
                    if (!focused) {
                        commit(input.getText().toString());
                    }
                });
        setOnClickListener(view -> input.requestFocus());
    }

    /** The recipients, comma separated as the bridge reads them, the pending input included. */
    String value() {
        List<String> all = new ArrayList<>(addresses);
        String pending = input.getText().toString().trim();
        if (!pending.isEmpty()) {
            all.add(pending);
        }
        return String.join(", ", all);
    }

    /** Empties the field. */
    void clear() {
        addresses.clear();
        removeViews(0, getChildCount() - 1);
        input.setText("");
    }

    /** Replaces the field's recipients with the comma separated addresses. */
    void set(String addresses) {
        clear();
        commit(addresses);
    }

    /** Turns every address in the text into a chip, leaving the input empty. */
    private void commit(String text) {
        for (String address : text.split("[,; ]+")) {
            if (!address.isEmpty()) {
                addChip(address);
            }
        }
        if (input.length() > 0) {
            input.setText("");
        }
    }

    private void addChip(String address) {
        addresses.add(address);

        TextView chip = new TextView(getContext());
        chip.setText(address);
        chip.setTextSize(14);
        chip.setSingleLine(true);
        chip.setBackgroundResource(R.drawable.recipient_chip);
        chip.setPadding(dp(12), dp(6), dp(8), dp(6));
        chip.setCompoundDrawablePadding(dp(4));
        Drawable close = getContext().getDrawable(R.drawable.ic_close);
        close.setBounds(0, 0, dp(16), dp(16));
        chip.setCompoundDrawablesRelative(null, null, close, null);
        chip.setContentDescription(
                getContext().getString(R.string.compose_remove_recipient, address));
        chip.setOnClickListener(
                view -> {
                    addresses.remove(indexOfChild(view));
                    removeView(view);
                });
        addView(chip, getChildCount() - 1);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int gap = dp(GAP_DP);
        int x = 0;
        int y = 0;
        int line = 0;

        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            boolean last = index == getChildCount() - 1;
            if (last) {
                // NOTE: the input takes what the line has left, or a line
                // of its own when that is too narrow to type in.
                int left = width - x;
                if (left < dp(INPUT_MIN_DP)) {
                    x = 0;
                    y += line + gap;
                    line = 0;
                    left = width;
                }
                child.measure(
                        MeasureSpec.makeMeasureSpec(left, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            } else {
                child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + child.getMeasuredWidth() > width) {
                    x = 0;
                    y += line + gap;
                    line = 0;
                }
            }
            x += child.getMeasuredWidth() + gap;
            line = Math.max(line, child.getMeasuredHeight());
        }

        setMeasuredDimension(
                MeasureSpec.getSize(widthSpec),
                y + line + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left - getPaddingLeft() - getPaddingRight();
        int gap = dp(GAP_DP);
        // NOTE: the line heights first, so each child sits centred in its line.
        int[] lineHeights = lineHeights(width, gap);
        int x = 0;
        int y = 0;
        int row = 0;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            int childWidth = child.getMeasuredWidth();
            if (x > 0 && x + childWidth > width) {
                x = 0;
                y += lineHeights[row] + gap;
                row++;
            }
            int offset = (lineHeights[row] - child.getMeasuredHeight()) / 2;
            int childLeft = getPaddingLeft() + x;
            int childTop = getPaddingTop() + y + offset;
            child.layout(
                    childLeft,
                    childTop,
                    childLeft + childWidth,
                    childTop + child.getMeasuredHeight());
            x += childWidth + gap;
        }
    }

    /** The height of each line, in the order they are laid out. */
    private int[] lineHeights(int width, int gap) {
        List<Integer> heights = new ArrayList<>();
        int x = 0;
        int line = 0;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (x > 0 && x + child.getMeasuredWidth() > width) {
                heights.add(line);
                x = 0;
                line = 0;
            }
            line = Math.max(line, child.getMeasuredHeight());
            x += child.getMeasuredWidth() + gap;
        }
        heights.add(line);

        int[] result = new int[heights.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = heights.get(index);
        }
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
