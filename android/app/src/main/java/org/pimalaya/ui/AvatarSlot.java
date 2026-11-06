package org.pimalaya.ui;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.TextView;
import org.pimalaya.R;

/**
 * A row's avatar, one code for every domain (layout/avatar_slot.xml): mail,
 * contacts, calendar entries, files and, while picked, tasks.
 *
 * <p>The disc in the palette's tones under its letter, the accent disc and
 * its check in its place while the row is picked, at its size, and an unread
 * message's badge on its top-right edge.
 */
public final class AvatarSlot {
    private AvatarSlot() {}

    /**
     * Binds the slot inside {@code row}.
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
        TextView disc = row.findViewById(R.id.avatar_disc);
        disc.setText(letter);
        Avatar.paint(disc, seed, declared);
        disc.setVisibility(picked ? View.INVISIBLE : View.VISIBLE);

        android.widget.ImageView check = row.findViewById(R.id.avatar_check);
        check.setVisibility(picked ? View.VISIBLE : View.GONE);
        if (picked) {
            check.setImageTintList(ColorStateList.valueOf(host.accentContrast()));
        }

        row.findViewById(R.id.avatar_badge).setVisibility(unread ? View.VISIBLE : View.GONE);
    }
}
