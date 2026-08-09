package org.pimalaya;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * The three list domains, and the dropdown the app bar's title opens
 * onto them.
 *
 * <p>This is where the app's top-level navigation is defined: the order
 * the domains come in, the name each shows and the glyph that stands for
 * it. It used to be three icons sitting in the bar, which was one
 * navigation too many beside a title saying the same thing; folding them
 * into the title gives the bar back the width its actions need.
 *
 * <p>A list popup rather than the framework's {@code PopupMenu} because
 * the glyphs are the point: a PopupMenu drops icons unless they are
 * forced, and renders them flush against their labels when they are.
 */
final class DomainMenu {
    /** The list screens, in the order the dropdown offers them. */
    static final int[] PANELS = {
        MainActivity.PANEL_MAIL, MainActivity.PANEL_CONTACTS, MainActivity.PANEL_CALENDAR,
    };

    /** How much of the screen the dropdown may take, whatever it holds. */
    private static final float MAX_SCREEN_SHARE = 0.75f;

    /** One domain's name, as its bar titles itself. */
    static int titleOf(int panel) {
        if (panel == MainActivity.PANEL_MAIL) {
            return R.string.mail_title;
        }
        return panel == MainActivity.PANEL_CALENDAR
                ? R.string.calendar_title
                : R.string.contacts_title;
    }

    /** One domain's glyph. */
    static int iconOf(int panel) {
        if (panel == MainActivity.PANEL_MAIL) {
            return R.drawable.ic_domain_mail;
        }
        return panel == MainActivity.PANEL_CALENDAR
                ? R.drawable.ic_domain_calendar
                : R.drawable.ic_domain_contacts;
    }

    /** A domain's position in the dropdown, or -1 when it is not one. */
    static int indexOf(int panel) {
        for (int index = 0; index < PANELS.length; index++) {
            if (PANELS[index] == panel) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Drops the domain list under {@code anchor} and hands the picked
     * panel to {@code onPick}; picking the one already shown does
     * nothing, which the caller need not check.
     */
    static void show(MainActivity host, View anchor, int current, IntConsumer onPick) {
        Adapter adapter = new Adapter(host, current);
        ListPopupWindow popup = new ListPopupWindow(host);
        popup.setAnchorView(anchor);
        popup.setAdapter(adapter);
        // Three short names, so the popup is as wide as its longest row
        // and no wider: a fixed width leaves a band of empty surface
        // beside "Emails", which reads as a menu missing its items.
        popup.setContentWidth(widthOf(host, adapter));
        // NOTE: modal, so a tap outside dismisses it rather than falling
        // through to whatever list row happens to be under it.
        popup.setModal(true);
        popup.setOnItemClickListener(
                (parent, view, position, id) -> {
                    popup.dismiss();
                    if (PANELS[position] != current) {
                        onPick.accept(PANELS[position]);
                    }
                });
        popup.show();
    }

    /**
     * How wide the widest row wants to be, capped so a long translation
     * cannot open a dropdown across the whole screen.
     *
     * <p>Measured rather than declared, because what the rows hold is a
     * translated name: the one number that stays right in every language
     * is the one the rows themselves ask for.
     */
    private static int widthOf(MainActivity host, Adapter adapter) {
        int free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        FrameLayout parent = new FrameLayout(host);

        int width = 0;
        View row = null;
        for (int position = 0; position < adapter.getCount(); position++) {
            row = adapter.getView(position, row, parent);
            row.measure(free, free);
            width = Math.max(width, row.getMeasuredWidth());
        }

        int ceiling =
                Math.round(host.getResources().getDisplayMetrics().widthPixels * MAX_SCREEN_SHARE);
        return Math.min(width, ceiling);
    }

    /**
     * The dropdown's rows. The current domain is drawn in the accent,
     * glyph and name together, so the list says where you are as well as
     * where you can go: the title above it is hidden behind the popup.
     */
    private static final class Adapter extends BaseAdapter {
        private final MainActivity host;
        private final int current;

        Adapter(MainActivity host, int current) {
            this.host = host;
            this.current = current;
        }

        @Override
        public int getCount() {
            return PANELS.length;
        }

        @Override
        public Object getItem(int position) {
            return PANELS[position];
        }

        @Override
        public long getItemId(int position) {
            return PANELS[position];
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            View view = recycled;
            if (view == null) {
                view = LayoutInflater.from(host).inflate(R.layout.item_domain, parent, false);
            }

            int panel = PANELS[position];
            int color =
                    host.resolveColor(
                            panel == current
                                    ? android.R.attr.colorAccent
                                    : android.R.attr.textColorPrimary);

            ImageView icon = view.findViewById(R.id.domain_icon);
            icon.setImageResource(iconOf(panel));
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(color));

            TextView name = view.findViewById(R.id.domain_name);
            name.setText(titleOf(panel));
            name.setTextColor(color);

            return view;
        }
    }

    private DomainMenu() {}
}
