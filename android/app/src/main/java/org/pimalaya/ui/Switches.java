package org.pimalaya.ui;

import android.content.Context;
import android.widget.Switch;
import org.pimalaya.R;

/**
 * A switch in the palette.
 *
 * <p>DeviceDefault colours its switch from the wallpaper from Android 12 on,
 * and its switch style is private, so no theme can restyle it: each switch is
 * tinted here, its shape kept. On, the accent under a thumb of the text on
 * it; off, the divider tone under the secondary ink.
 */
public final class Switches {
    private Switches() {}

    /** A new switch, tinted. */
    public static Switch of(Context context) {
        Switch toggle = new Switch(context);
        toggle.setThumbTintList(context.getColorStateList(R.color.switch_thumb));
        toggle.setTrackTintList(context.getColorStateList(R.color.switch_track));
        return toggle;
    }
}
