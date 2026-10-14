package org.pimalaya.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import org.pimalaya.R;

/**
 * The coloured disc a list row leads with, shared by the three domains.
 *
 * <p>One helper so contacts, senders and events read as one app. The seed
 * identifies the row, not its label (a contact's whole vCard, a sender's
 * address), so renaming keeps the colour.
 */
public final class Avatar {
    /**
     * A muted round background, its hue mapped from {@code seed}.
     *
     * <p>Saturation and value are fixed, so every disc in the app sits at
     * one weight and the hue is the only thing that varies.
     */
    public static Drawable circle(Context context, String seed) {
        return disc(context, Color.HSVToColor(new float[] {hueOf(seed), 0.4f, 0.55f}));
    }

    /**
     * A round background in a colour the row's owner already has.
     *
     * <p>A calendar's server colour beats anything derived. Inset rather than
     * sized, so a smaller disc stays on the axis of the bar's first domain
     * button.
     */
    public static Drawable disc(Context context, int color) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);

        int inset = context.getResources().getDimensionPixelSize(R.dimen.item_avatar_inset);
        return new InsetDrawable(circle, inset);
    }

    /** A named thing's colour: its declared one if it parses, else derived. */
    public static int colorOf(String declared, String name) {
        if (declared != null && !declared.isEmpty()) {
            try {
                // NOTE: CalDAV writes the alpha last (#RRGGBBAA, Apple's
                // calendar-color), the platform first.
                String color = declared.trim();
                if (color.length() == 9 && color.charAt(0) == '#') {
                    color = "#" + color.substring(7) + color.substring(1, 7);
                }
                return Color.parseColor(color);
            } catch (IllegalArgumentException error) {
                // NOTE: servers send colours the platform cannot read
                // (named CSS colours, `#rgb`, an empty element); the
                // derived hue is a better answer than no disc at all.
            }
        }
        return Color.HSVToColor(new float[] {hueOf(name), 0.4f, 0.55f});
    }

    /**
     * Maps a seed to a hue in [0, 360) by summing its code points.
     *
     * <p>The sum preserves locality: a one-character edit shifts the hue a
     * degree, so a lightly edited card keeps its colour, while distinct seeds
     * land apart.
     */
    private static float hueOf(String seed) {
        if (seed == null) {
            return 0f;
        }
        long sum = 0;
        for (int index = 0; index < seed.length(); index++) {
            sum += seed.charAt(index);
        }
        return sum % 360;
    }

    /**
     * A disc's initial: the uppercased first letter, or {@code #} otherwise.
     *
     * <p>Also the contacts list's section letter, which is why the two agree.
     */
    public static String letter(String label) {
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty()) {
            return "#";
        }
        char first = Character.toUpperCase(trimmed.charAt(0));
        return Character.isLetter(first) ? String.valueOf(first) : "#";
    }

    private Avatar() {}
}
