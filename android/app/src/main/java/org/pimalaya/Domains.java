package org.pimalaya;

/**
 * The three list domains: the order the drawer offers them in, and what
 * each one is called and drawn with.
 *
 * <p>The app's top-level navigation is three drawer rows, and this is
 * the one place that says which three and in what order.
 */
final class Domains {
    /** The list screens, in the order the drawer offers them. */
    static final int[] PANELS = {
        MainActivity.PANEL_MAIL, MainActivity.PANEL_CONTACTS, MainActivity.PANEL_CALENDAR,
    };

    /** The drawer row that switches to one domain. */
    static int buttonOf(int panel) {
        if (panel == MainActivity.PANEL_MAIL) {
            return R.id.drawer_domain_mail;
        }
        return panel == MainActivity.PANEL_CALENDAR
                ? R.id.drawer_domain_calendar
                : R.id.drawer_domain_contacts;
    }

    /** One domain's name, which its drawer row reads. */
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
