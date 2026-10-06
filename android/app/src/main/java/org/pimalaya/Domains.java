package org.pimalaya;

/**
 * The three list domains: the order the bottom navigation offers them
 * in, and what each one is called and drawn with.
 *
 * <p>The app's top-level navigation is three bottom bar items, and this
 * is the one place that says which three and in what order.
 */
final class Domains {
    /** The list screens, in the order the bottom navigation offers them. */
    static final int[] PANELS = {
        MainActivity.PANEL_MAIL, MainActivity.PANEL_CONTACTS, MainActivity.PANEL_CALENDAR,
    };

    /** The bottom navigation item that switches to one domain. */
    static int buttonOf(int panel) {
        if (panel == MainActivity.PANEL_MAIL) {
            return R.id.nav_mail;
        }
        return panel == MainActivity.PANEL_CALENDAR ? R.id.nav_calendar : R.id.nav_contacts;
    }

    /** One domain's name, which its large title reads. */
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

    private Domains() {}
}
