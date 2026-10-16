package org.pimalaya.mail;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.LeadingMarginSpan;

/**
 * A numbered list item in the rich editor: its number in the margin.
 *
 * <p>One span per line; the number counts the numbered lines right above,
 * so it follows as items come and go.
 */
final class NumberSpan implements LeadingMarginSpan {
    /** The margin the number sits in. */
    private final int margin;

    NumberSpan(int margin) {
        this.margin = margin;
    }

    @Override
    public int getLeadingMargin(boolean first) {
        return margin;
    }

    @Override
    public void drawLeadingMargin(
            Canvas canvas,
            Paint paint,
            int x,
            int dir,
            int top,
            int baseline,
            int bottom,
            CharSequence text,
            int start,
            int end,
            boolean first,
            Layout layout) {
        if (!first || !(text instanceof Spanned spanned) || spanned.getSpanStart(this) != start) {
            return;
        }
        String label = number(spanned, start) + ".";
        float width = paint.measureText(label);
        float left = dir > 0 ? x + margin - width - margin / 4f : x - margin + margin / 4f;
        canvas.drawText(label, left, baseline, paint);
    }

    /** The item's number: one more than the numbered line above, if any. */
    private static int number(Spanned spanned, int start) {
        int number = 1;
        int line = start;
        while (line > 0) {
            int above = TextUtils.lastIndexOf(spanned, '\n', line - 2) + 1;
            boolean numbered = false;
            for (NumberSpan span : spanned.getSpans(above, above, NumberSpan.class)) {
                numbered |= spanned.getSpanStart(span) == above;
            }
            if (!numbered) {
                break;
            }
            number++;
            line = above;
        }
        return number;
    }
}
