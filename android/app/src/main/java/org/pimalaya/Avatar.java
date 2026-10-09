package org.pimalaya;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;

/**
 * The coloured disc a list row leads with, shared by the three domains.
 *
 * <p>It is one helper rather than one per screen because the discs have
 * to look like the same object: a contact, the sender of a message and a
 * calendar entry are three rows of one app, and a hue rule that differed
 * between them would read as three lists pasted together.
 *
 * <p>What each screen feeds it differs, and deliberately. A contact
 * hashes its whole vCard, a message its sender's address, so in both
 * cases the seed is the thing that identifies the row rather than the
 * label shown on it: a renamed contact and a renamed sender keep their
 * colour.
 */
final class Avatar {
    /**
     * A muted round background, its hue mapped from {@code seed}.
     *
     * <p>Saturation and value are fixed, so every disc in the app sits at
     * one weight and the hue is the only thing that varies.
     */
    static Drawable circle(Context context, String seed) {
        return disc(context, Color.HSVToColor(new float[] {hueOf(seed), 0.4f, 0.55f}));
    }

    /**
     * A round background in one given colour, for a row whose owner
     * already has one of its own: a calendar's server-set colour beats
     * anything derived, since it is the colour that calendar has
     * everywhere else the user sees it.
     *
     * <p>Inset inside the view rather than sized to it, so the disc can
     * be smaller than the column without leaving the axis the column
     * puts it on: the bar's first domain button. Shrinking the view
     * instead moved every disc two pixels off that axis and pulled the
     * text after it.
     */
    static Drawable disc(Context context, int color) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);

        int inset = context.getResources().getDimensionPixelSize(R.dimen.item_avatar_inset);
        return new InsetDrawable(circle, inset);
    }

    /**
     * The colour behind a named thing: the one it declares when that
     * parses, the hue derived from its name otherwise.
     */
    static int colorOf(String declared, String name) {
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
     * Maps a seed to a hue in [0, 360) by summing its code points. The
     * sum is locality preserving, so a one-character edit shifts the hue
     * by one degree, yet two distinct seeds (differing name, email and
     * UID) land far apart. Hashing instead would spread near-identical
     * seeds across the wheel, which is the opposite of what a list wants:
     * a card lightly edited should keep almost the same colour.
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
     * The initial a disc carries: the first letter of {@code label}
     * uppercased, or {@code #} when it starts with anything else. Also
     * the contacts list's section letter, which is why the two agree.
     */
    static String letter(String label) {
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty()) {
            return "#";
        }
        char first = Character.toUpperCase(trimmed.charAt(0));
        return Character.isLetter(first) ? String.valueOf(first) : "#";
    }

    private Avatar() {}
}
