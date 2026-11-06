package org.pimalaya.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * A bar's toolbox: its buttons in one tonal pill, the reader's shape.
 *
 * <p>The screens show and hide each button on their own, so the pill follows
 * them: measured to nothing while none of its buttons shows, it then leaves
 * no gap in the bar, its background inset carrying the margin.
 */
public final class ToolGroup extends LinearLayout {
    public ToolGroup(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        for (int index = 0; index < getChildCount(); index++) {
            if (getChildAt(index).getVisibility() != View.GONE) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }
        }
        setMeasuredDimension(0, 0);
    }
}
