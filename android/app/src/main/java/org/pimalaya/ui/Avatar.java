package org.pimalaya.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.widget.TextView;
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
     * The derived colours of a named thing with none of its own, the
     * avatars' eight hues as solid discs under a white letter: OKLCH 0.55
     * 0.09, 4.6:1 or more against white, in either theme.
     */
    private static final int[] SOLID = {
        0xFFA05C57, 0xFF936831, 0xFF6B7939, 0xFF3B825F,
        0xFF118186, 0xFF5272A6, 0xFF7A659E, 0xFF975B7E,
    };

    /**
     * Paints a row's disc and letter in the palette's tones, from
     * {@code seed}.
     *
     * <p>Eight hues at one lightness, the seed's slot of 45 degrees: a soft
     * fill under the letter in a deep tone of its hue by day, a deep fill
     * under a pale one by night (the avatar colours of res/values and
     * res/values-night), so every disc sits at one weight and the hue is the
     * only thing that varies.
     */
    public static void paint(TextView view, String seed) {
        Context context = view.getContext();
        int slot = slotOf(seed);
        view.setBackground(disc(context, tone(context, R.array.avatar_fills, slot)));
        view.setTextColor(tone(context, R.array.avatar_inks, slot));
    }

    /** One slot's colour from an array of the eight, in the theme's tones. */
    private static int tone(Context context, int array, int slot) {
        TypedArray tones = context.getResources().obtainTypedArray(array);
        try {
            return tones.getColor(slot, Color.GRAY);
        } finally {
            tones.recycle();
        }
    }

    /** The slot of the eight hues a seed lands in. */
    private static int slotOf(String seed) {
        return (int) (hueOf(seed) / 45f) % 8;
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
        return SOLID[slotOf(name)];
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
