package org.pimalaya.ui;

import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.pimalaya.R;

/** The pill an account card and its settings show its sync state on. */
public final class SyncPill {
    private SyncPill() {}

    /**
     * The pill saying where an account stands.
     *
     * <p>Plain tone when deactivated or never synced; on an accent tint the
     * bodies its window still downloads ({@code left}, null outside the body
     * step) or when it last synced.
     */
    public static View of(Host host, boolean enabled, long stamp, Integer left) {
        boolean downloading = enabled && left != null && left > 0;
        boolean synced = downloading || (enabled && stamp > 0);
        int color =
                synced
                        ? host.resolveColor(android.R.attr.colorAccent)
                        : host.resolveColor(android.R.attr.textColorSecondary);

        android.widget.ImageView glyph = new android.widget.ImageView(host);
        glyph.setImageResource(R.drawable.ic_sync);
        glyph.setImageTintList(ColorStateList.valueOf(color));

        TextView label = new TextView(host);
        if (!enabled) {
            label.setText(R.string.drawer_deactivated);
        } else if (downloading) {
            label.setText(
                    host.getResources().getQuantityString(R.plurals.drawer_downloading, left, left));
        } else if (synced) {
            label.setText(
                    host.getString(
                            R.string.drawer_synced,
                            android.text.format.DateUtils.getRelativeTimeSpanString(
                                    stamp,
                                    System.currentTimeMillis(),
                                    android.text.format.DateUtils.MINUTE_IN_MILLIS)));
        } else {
            label.setText(R.string.drawer_never_synced);
        }
        label.setTextColor(color);
        label.setTextSize(13);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setSingleLine(true);

        android.graphics.drawable.GradientDrawable tint =
                new android.graphics.drawable.GradientDrawable();
        tint.setCornerRadius(host.dp(15));
        tint.setColor((color & 0x00ffffff) | 0x24000000);

        LinearLayout pill = new LinearLayout(host);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(host.dp(8), 0, host.dp(12), 0);
        pill.setBackground(tint);
        pill.addView(glyph, new LinearLayout.LayoutParams(host.dp(16), host.dp(16)));
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMarginStart(host.dp(8));
        pill.addView(label, labelParams);
        return pill;
    }
}
