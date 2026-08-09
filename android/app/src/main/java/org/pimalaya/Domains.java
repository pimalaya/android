package org.pimalaya;

/**
 * The three list domains: the order the bar offers them in, and what
 * each one is called and drawn with.
 *
 * <p>The app's top-level navigation is three bar buttons, and this is
 * the one place that says which three and in what order. It matters
 * beyond the bar: which way a switch animates depends on where the two
 * domains sit relative to each other ({@link #indexOf}), so the order
 * has to be a fact rather than a repeated literal.
 */
final class Domains {
    /** The list screens, in the order the bar offers them. */
    static final int[] PANELS = {
        MainActivity.PANEL_MAIL, MainActivity.PANEL_CONTACTS, MainActivity.PANEL_CALENDAR,
    };

    /** The bar button that switches to one domain. */
    static int buttonOf(int panel) {
        if (panel == MainActivity.PANEL_MAIL) {
            return R.id.bar_domain_mail;
        }
        return panel == MainActivity.PANEL_CALENDAR
                ? R.id.bar_domain_calendar
                : R.id.bar_domain_contacts;
    }

    /** One domain's name, which its button announces itself by. */
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

    /** A domain's position in the bar, or -1 when it is not one. */
    static int indexOf(int panel) {
        for (int index = 0; index < PANELS.length; index++) {
            if (PANELS[index] == panel) {
                return index;
            }
        }
        return -1;
    }

    private Domains() {}
}
