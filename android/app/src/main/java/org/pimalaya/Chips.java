package org.pimalaya;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A filter chip: a tonal pill naming what it keeps, filled with the
 * accent and checked while on.
 *
 * <p>Hand-built on a TextView because the platform has no chip, and the
 * only thing one needs beyond a label is the two looks.
 */
final class Chips {
    /** Adds one chip to {@code row}, calling {@code onToggle} with each new state. */
    static void add(
            MainActivity host, LinearLayout row, int label, int icon, Consumer<Boolean> onToggle) {
        TextView chip = chip(host, row, label, icon);
        chip.setOnClickListener(
                view -> {
                    boolean on = !chip.isSelected();
                    draw(host, chip, icon, on);
                    onToggle.accept(on);
                });
    }

    /**
     * Adds a group of chips at most one of which is on: turning one on
     * turns the others off, and {@code onPick} is told the index of the
     * one on, -1 when none is.
     */
    static void exclusive(
            MainActivity host,
            LinearLayout row,
            int[] labels,
            int[] icons,
            IntConsumer onPick) {
        List<TextView> chips = new ArrayList<>();
        for (int index = 0; index < labels.length; index++) {
            chips.add(chip(host, row, labels[index], icons[index]));
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
    }

    private static TextView chip(MainActivity host, LinearLayout row, int label, int icon) {
        TextView chip = new TextView(host, null, 0, R.style.Chip);
        chip.setText(label);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, host.dp(34));
        params.setMarginEnd(host.dp(8));
        row.addView(chip, params);
        draw(host, chip, icon, false);
        return chip;
    }

    private static void draw(MainActivity host, TextView chip, int icon, boolean on) {
        chip.setSelected(on);
        chip.setBackgroundResource(on ? R.drawable.button_pill : R.drawable.button_tonal);
        int color =
                on ? host.accentContrast() : host.ui.resolveColor(android.R.attr.textColorPrimary);
        chip.setTextColor(color);
        Drawable glyph = host.getDrawable(on ? R.drawable.ic_check : icon).mutate();
        glyph.setBounds(0, 0, host.dp(18), host.dp(18));
        chip.setCompoundDrawablesRelative(glyph, null, null, null);
        chip.setCompoundDrawableTintList(ColorStateList.valueOf(color));
    }

    private Chips() {}
}
