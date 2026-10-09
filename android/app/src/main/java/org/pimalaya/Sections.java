package org.pimalaya;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The page the app shows one item on: a scroll of sections, each one
 * rounded card holding its small label and its rows, a title over a
 * diminished subtitle.
 *
 * <p>One builder rather than one per screen, because a contact, an
 * event and a to-do are the same page with different sections: what
 * differs between them is which sections they draw and what fills the
 * rows, and neither of those is layout. The contact editor adds an
 * action to each header and a warning to a diverged row, which is what
 * the two extra parameters are for; a reader passes null for both.
 */
final class Sections {
    private final Activity activity;
    private final LinearLayout container;
    private final Ui ui;

    /** The accent the headers are drawn in, also used by the callers. */
    final int accentColor;

    private final int labelColor;
    private final int dividerColor;

    Sections(Activity activity, LinearLayout container) {
        this.activity = activity;
        this.container = container;
        this.ui = new Ui(activity);
        this.accentColor = ui.resolveColor(android.R.attr.colorAccent);
        this.labelColor = ui.resolveColor(android.R.attr.textColorSecondary);
        this.dividerColor = activity.getColor(R.color.divider_light);
    }

    /** Empties the page, before a render fills it again. */
    void clear() {
        container.removeAllViews();
    }

    /**
     * Adds a section as one card: its icon and label, an optional
     * right-aligned action on the label's line, then its items. An empty
     * section vanishes unless the caller keeps it, as a card of its
     * label alone. A title of 0 draws the card without a header.
     */
    void section(int title, int icon, List<View> items, View action, boolean keepEmpty) {
        if (items.isEmpty() && !keepEmpty) {
            return;
        }

        // NOTE: the card clips to its outline, so a row's ripple rounds
        // with the corners it sits in.
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_group);
        card.setClipToOutline(true);
        if (title != 0) {
            card.addView(header(title, icon, action, items.isEmpty()));
        }
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) {
                View line = new View(activity);
                line.setBackgroundColor(dividerColor);
                LinearLayout.LayoutParams lineParams =
                        new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, 1);
                lineParams.setMarginStart(dp(16));
                card.addView(line, lineParams);
            }
            card.addView(items.get(index));
        }

        int gutter = activity.getResources().getDimensionPixelSize(R.dimen.card_gutter);
        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMarginStart(gutter);
        cardParams.setMarginEnd(gutter);
        cardParams.topMargin = container.getChildCount() == 0 ? dp(8) : gutter;
        container.addView(card, cardParams);
    }

    /** A card's header: its icon, its label and the optional action. */
    private View header(int title, int icon, View action, boolean alone) {
        ImageView iconView = new ImageView(activity);
        iconView.setImageResource(icon);
        iconView.setImageTintList(ColorStateList.valueOf(labelColor));

        TextView label = new TextView(activity);
        label.setText(title);
        label.setTextColor(labelColor);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(null, Typeface.BOLD);
        label.setAllCaps(true);
        label.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMarginStart(dp(8));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(6), dp(6), alone ? dp(6) : 0);
        header.setMinimumHeight(dp(44));
        header.addView(iconView, new LinearLayout.LayoutParams(dp(16), dp(16)));
        header.addView(label, labelParams);
        if (action != null) {
            header.addView(action);
        }
        return header;
    }

    /**
     * A row: title over a diminished subtitle, with an optional trailing
     * view. A null action leaves the row unpressable, which is what a
     * reader's page wants; a non-null one makes it a tappable item with
     * the platform's own ripple.
     */
    View row(CharSequence title, CharSequence subtitle, View trailing, Runnable onClick) {
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(ui.resolveColor(android.R.attr.textColorPrimary));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(titleView);

        if (subtitle != null && subtitle.length() > 0) {
            TextView subtitleView = new TextView(activity);
            subtitleView.setText(subtitle);
            subtitleView.setTextColor(labelColor);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            text.addView(subtitleView);
        }

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (onClick != null) {
            row.setBackgroundResource(
                    ui.resolveAttr(android.R.attr.selectableItemBackground));
            row.setOnClickListener(view -> onClick.run());
        }
        row.addView(
                text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (trailing != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(24), dp(24));
            params.setMarginStart(dp(12));
            row.addView(trailing, params);
        }

        return row;
    }

    /**
     * A row whose subtitle labels its value rather than the other way
     * round: the value reads first and the property names itself under
     * it, which is how a read-only page is scanned.
     */
    View value(CharSequence value, int label) {
        return row(value, activity.getString(label), null, null);
    }

    int dp(int value) {
        return ui.dp(value);
    }
}
