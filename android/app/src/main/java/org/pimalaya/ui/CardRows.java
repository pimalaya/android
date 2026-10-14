package org.pimalaya.ui;

import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.pimalaya.R;

/** The rows the setup's cards are made of, on the card they sit in. */
public final class CardRows {
    private CardRows() {}

    /** An empty card, its rows clipped to its rounded outline. */
    public static LinearLayout card(Host host) {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_group);
        card.setClipToOutline(true);
        return card;
    }

    /**
     * One domain's row in a card: glyph tile, name over status, trailing view.
     *
     * <p>The same row under a name of its own, for a card covering several.
     */
    public static LinearLayout domainRow(
            Host host,
            int icon,
            String label,
            String status,
            boolean error,
            View trailing,
            boolean bold) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(host.ui.dp(60));
        row.setPadding(host.ui.dp(14), host.ui.dp(8), host.ui.dp(8), host.ui.dp(8));
        TypedValue ripple = new TypedValue();
        host.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setForeground(host.getDrawable(ripple.resourceId));

        android.widget.ImageView glyph = new android.widget.ImageView(host);
        glyph.setImageResource(icon);
        glyph.setBackgroundResource(R.drawable.tile_page);
        glyph.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        glyph.setPadding(host.ui.dp(6), host.ui.dp(6), host.ui.dp(6), host.ui.dp(6));
        glyph.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        host.ui.resolveColor(android.R.attr.textColorPrimary)));
        LinearLayout.LayoutParams glyphParams =
                new LinearLayout.LayoutParams(host.ui.dp(32), host.ui.dp(32));
        glyphParams.setMarginEnd(host.ui.dp(14));
        row.addView(glyph, glyphParams);

        LinearLayout text = new LinearLayout(host);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(host);
        name.setText(label);
        name.setTextSize(16);
        if (bold) {
            name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        }
        name.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
        text.addView(name);
        if (status != null) {
            TextView line = new TextView(host);
            line.setText(status);
            line.setTextSize(13);
            line.setTextColor(
                    host.ui.resolveColor(
                            error ? android.R.attr.colorError : android.R.attr.textColorSecondary));
            text.addView(line);
        }
        row.addView(
                text,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (trailing != null) {
            row.addView(trailing);
        }
        return row;
    }

    /** An option's row under a hairline: title over its line, then its tick. */
    public static View tickRow(Host host, int titleText, int lineText, CheckBox toggle) {
        LinearLayout item = new LinearLayout(host);
        item.setOrientation(LinearLayout.VERTICAL);
        item.addView(rowDivider(host));

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(host.ui.dp(56));
        row.setPadding(host.ui.dp(60), host.ui.dp(8), host.ui.dp(8), host.ui.dp(8));
        TypedValue ripple = new TypedValue();
        host.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setForeground(host.getDrawable(ripple.resourceId));

        LinearLayout text = new LinearLayout(host);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(host);
        title.setText(titleText);
        title.setTextSize(16);
        title.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
        text.addView(title);
        TextView line = new TextView(host);
        line.setText(lineText);
        line.setTextSize(13);
        line.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
        text.addView(line);
        LinearLayout.LayoutParams textParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginEnd(host.ui.dp(12));
        row.addView(text, textParams);
        row.addView(toggle);
        row.setOnClickListener(view -> toggle.toggle());

        item.addView(row);
        return item;
    }

    /** The line between two rows of a card, inset past the glyph. */
    public static View rowDivider(Host host) {
        View divider = new View(host);
        divider.setBackgroundResource(R.color.divider_light);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMarginStart(host.ui.dp(60));
        divider.setLayoutParams(params);
        return divider;
    }
}
