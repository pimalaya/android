package org.pimalaya.mail;

import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

/** Inline code in the rich editor: monospace, on a faint background. */
final class CodeSpan extends MetricAffectingSpan {
    private final int background;

    CodeSpan(int background) {
        this.background = background;
    }

    @Override
    public void updateDrawState(TextPaint paint) {
        paint.setTypeface(Typeface.MONOSPACE);
        paint.bgColor = background;
    }

    @Override
    public void updateMeasureState(TextPaint paint) {
        paint.setTypeface(Typeface.MONOSPACE);
    }
}
