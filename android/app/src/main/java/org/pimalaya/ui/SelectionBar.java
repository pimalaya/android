package org.pimalaya.ui;

import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import org.pimalaya.R;

/**
 * A selection's chrome, the same on every list: the cross, the count and
 * select-all in the bar; the actions in the navigation's place, each its icon
 * on the navigation's pill over its label.
 *
 * <p>The list owns its selection and its actions; this draws them. Ending a
 * selection re-applies the screen's chrome, which gives the navigation and
 * the list's own buttons back ({@link #ended}).
 */
public final class SelectionBar {
    /** A list a selection runs on, which the bar's cross and box act on. */
    public interface Owner {
        /** Whether a selection is under way. */
        boolean isSelectionMode();

        /** Ends the selection. */
        void exitSelection();

        /** Selects every row listed, or none when all already are. */
        void toggleSelectAll();
    }

    /** One action of the bar: its icon over its label, and what it does. */
    public record Action(int icon, int label, Runnable act) {}

    /** The list's own chrome, which a selection takes the place of. */
    private static final int[] LIST_CHROME = {
        R.id.bar_menu,
        R.id.bar_filter,
        R.id.calendar_modes,
        R.id.contacts_birthdays,
        R.id.contacts_duplicates,
        R.id.contacts_transfer,
        R.id.tasks_review,
        R.id.tasks_clarify,
        R.id.fab_extended,
        R.id.bottom_nav,
    };

    private SelectionBar() {}

    /**
     * Shows a running selection: {@code count} rows, the box ticked when
     * {@code all} of them are, and {@code actions} in the navigation's place.
     */
    public static void show(Host host, long count, boolean all, List<Action> actions) {
        TextView title = host.findViewById(R.id.bar_title);
        title.animate().cancel();
        title.setText(
                host.getString(R.string.selected_count, (int) Math.min(Integer.MAX_VALUE, count)));
        title.setAlpha(1f);
        for (int id : LIST_CHROME) {
            host.findViewById(id).setVisibility(View.GONE);
        }
        host.findViewById(R.id.selection_close).setVisibility(View.VISIBLE);
        host.findViewById(R.id.selection_all_slot).setVisibility(View.VISIBLE);
        ((CheckBox) host.findViewById(R.id.selection_all)).setChecked(all);

        LinearLayout bar = host.findViewById(R.id.selection_actions);
        bar.removeAllViews();
        for (Action action : actions) {
            bar.addView(
                    item(host, action),
                    new LinearLayout.LayoutParams(
                            0,
                            host.getResources().getDimensionPixelSize(R.dimen.nav_height),
                            1f));
        }
        bar.setVisibility(View.VISIBLE);
    }

    /**
     * Ends a selection's chrome on {@code panel} once its list has none left:
     * the screen's own chrome comes back. False when no selection's bar was
     * up there.
     *
     * <p>A list calls it from its own update, which the screen's chrome runs
     * again: the bar is down by then, so that second call is a no-op.
     */
    public static boolean ended(Host host, int panel) {
        if (host.screen != panel || !showing(host)) {
            return false;
        }
        host.applyChrome(panel);
        return true;
    }

    /** Whether a selection's bar is up, which the bar's title then keeps. */
    public static boolean showing(Host host) {
        return host.findViewById(R.id.selection_actions).getVisibility() == View.VISIBLE;
    }

    /** One action, on the navigation's item shape (activity_main.xml). */
    private static View item(Host host, Action action) {
        int ink = host.resolveColor(android.R.attr.textColorPrimary);

        ImageView glyph = new ImageView(host);
        glyph.setImageResource(action.icon());
        glyph.setImageTintList(ColorStateList.valueOf(ink));
        int icon = host.getResources().getDimensionPixelSize(R.dimen.item_icon);
        FrameLayout pill = new FrameLayout(host);
        pill.addView(glyph, new FrameLayout.LayoutParams(icon, icon, Gravity.CENTER));

        TextView name = new TextView(host);
        name.setText(action.label());
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        name.setTextColor(ink);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);

        LinearLayout item = new LinearLayout(host);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setBackgroundResource(
                host.ui.resolveAttr(android.R.attr.selectableItemBackgroundBorderless));
        item.setContentDescription(host.getString(action.label()));
        item.addView(
                pill,
                new LinearLayout.LayoutParams(
                        host.getResources().getDimensionPixelSize(R.dimen.nav_indicator_width),
                        host.getResources().getDimensionPixelSize(R.dimen.nav_indicator_height)));
        LinearLayout.LayoutParams nameParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = host.dp(4);
        item.addView(name, nameParams);
        item.setOnClickListener(view -> action.act().run());
        return item;
    }
}
