package org.pimalaya.ui;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.pimalaya.R;

/**
 * A filter chip: a tonal pill naming what it keeps, accent-filled while on.
 *
 * <p>Hand-built on a TextView: the platform has no chip.
 */
public final class Chips {
    /**
     * Adds one chip to {@code row}, telling {@code onToggle} each new state.
     */
    public static TextView add(
            Host host, LinearLayout row, int label, int icon, Consumer<Boolean> onToggle) {
        return add(host, row, host.getString(label), icon, onToggle);
    }

    /**
     * Adds one chip labelled {@code label}, for a row its caller fills from
     * data; {@code icon} 0 for none but the check while on.
     */
    public static TextView add(
            Host host,
            LinearLayout row,
            CharSequence label,
            int icon,
            Consumer<Boolean> onToggle) {
        TextView chip = chip(host, row, label, icon);
        chip.setOnClickListener(
                view -> {
                    boolean on = !chip.isSelected();
                    draw(host, chip, icon, on);
                    onToggle.accept(on);
                });
        return chip;
    }

    /**
     * Adds a group of chips at most one of which is on.
     *
     * <p>Turning one on turns the others off; {@code onPick} gets the index of
     * the one on, -1 for none. Returns the chips, for a caller relabelling
     * them.
     */
    public static List<TextView> exclusive(
            Host host,
            LinearLayout row,
            int[] labels,
            int[] icons,
            IntConsumer onPick) {
        List<TextView> chips = new ArrayList<>();
        for (int index = 0; index < labels.length; index++) {
            chips.add(chip(host, row, host.getString(labels[index]), icons[index]));
        }
        for (int index = 0; index < chips.size(); index++) {
            int picked = index;
            TextView chip = chips.get(index);
            chip.setOnClickListener(
                    view -> {
                        boolean on = !chip.isSelected();
                        for (int other = 0; other < chips.size(); other++) {
                            draw(host, chips.get(other), icons[other], on && other == picked);
                        }
                        onPick.accept(on ? picked : -1);
                    });
        }
        return chips;
    }

    /**
     * Adds a pill standing for a filter on, a cross at its end: a tap runs
     * {@code remove}.
     */
    public static TextView removable(
            Host host, LinearLayout row, CharSequence label, Runnable remove) {
        TextView chip = chip(host, row, label, 0);
        int color = host.ui.resolveColor(android.R.attr.textColorPrimary);
        Drawable cross = host.getDrawable(R.drawable.ic_close).mutate();
        cross.setBounds(0, 0, host.dp(18), host.dp(18));
        chip.setCompoundDrawablesRelative(null, null, cross, null);
        chip.setCompoundDrawableTintList(
                ColorStateList.valueOf(host.ui.resolveColor(android.R.attr.textColorSecondary)));
        chip.setPaddingRelative(host.dp(14), 0, host.dp(10), 0);
        chip.setTextColor(color);
        chip.setContentDescription(host.getString(R.string.filter_remove, label));
        chip.setOnClickListener(view -> remove.run());
        return chip;
    }

    private static TextView chip(Host host, LinearLayout row, CharSequence label, int icon) {
        TextView chip = new TextView(host, null, 0, R.style.Chip);
        chip.setText(label);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, host.dp(34));
        params.setMarginEnd(host.dp(8));
        row.addView(chip, params);
        draw(host, chip, icon, false);
        return chip;
    }

    /**
     * Draws {@code chip} on or off, as a tap would, for a caller whose own
     * state turned it.
     */
    public static void draw(Host host, TextView chip, int icon, boolean on) {
        chip.setSelected(on);
        chip.setBackgroundResource(on ? R.drawable.button_pill : R.drawable.button_tonal);
        int color =
                on ? host.accentContrast() : host.ui.resolveColor(android.R.attr.textColorPrimary);
        chip.setTextColor(color);
        if (!on && icon == 0) {
            chip.setCompoundDrawablesRelative(null, null, null, null);
            return;
        }
        Drawable glyph = host.getDrawable(on ? R.drawable.ic_check : icon).mutate();
        glyph.setBounds(0, 0, host.dp(18), host.dp(18));
        chip.setCompoundDrawablesRelative(glyph, null, null, null);
        chip.setCompoundDrawableTintList(ColorStateList.valueOf(color));
    }

    private Chips() {}
}
