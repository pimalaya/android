package org.pimalaya.ui;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import org.pimalaya.R;

/**
 * A row's avatar, one code for every domain (layout/avatar_slot.xml): mail,
 * contacts, calendar entries, tasks and files.
 *
 * <p>The disc in the palette's tones under its letter or a glyph, the accent
 * disc and its check in its place while the row is picked, at its size, and
 * an unread message's badge on its top-right edge.
 */
public final class AvatarSlot {
    private AvatarSlot() {}

    /**
     * Binds the slot inside {@code row}, its disc under {@code letter}.
     *
     * @param letter the disc's letter, empty for none
     * @param seed what the disc's hue is derived from (an address, a card)
     * @param declared a colour the row's owner has (a calendar's), whose hue
     *     wins, or null
     */
    public static void bind(
            Host host,
            View row,
            String letter,
            String seed,
            String declared,
            boolean picked,
            boolean unread) {
        bind(host, row, letter, 0, seed, declared, picked, unread);
    }

    /**
     * Binds the slot inside {@code row}, {@code glyph} in the letter's place
     * when not 0, drawn in the disc's ink.
     */
    public static void bind(
            Host host,
            View row,
            String letter,
            int glyph,
            String seed,
            String declared,
            boolean picked,
            boolean unread) {
        TextView disc = row.findViewById(R.id.avatar_disc);
        disc.setText(glyph == 0 ? letter : "");
        Avatar.paint(disc, seed, declared);
        disc.setVisibility(picked ? View.INVISIBLE : View.VISIBLE);

        ImageView mark = row.findViewById(R.id.avatar_glyph);
        mark.setVisibility(glyph == 0 || picked ? View.GONE : View.VISIBLE);
        if (glyph != 0) {
            mark.setImageResource(glyph);
            mark.setImageTintList(
                    ColorStateList.valueOf(Avatar.ink(host, seed, declared)));
        }

        ImageView check = row.findViewById(R.id.avatar_check);
        check.setVisibility(picked ? View.VISIBLE : View.GONE);
        if (picked) {
            check.setImageTintList(ColorStateList.valueOf(host.accentContrast()));
        }

        row.findViewById(R.id.avatar_badge).setVisibility(unread ? View.VISIBLE : View.GONE);
    }
}
