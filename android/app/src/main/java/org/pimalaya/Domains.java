package org.pimalaya;

/**
 * The four list tabs: the order the bottom navigation offers them in, and
 * what each one is called and drawn with.
 *
 * <p>The app's top-level navigation is four bottom bar items, and this is
 * the one place that says which four and in what order. Files is a tab and
 * not a {@link PimDomain}: no account connects it.
 */
final class Domains {
    /** The list screens, in the order the bottom navigation offers them. */
    static final int[] PANELS = {
        MainActivity.PANEL_MAIL,
        MainActivity.PANEL_CONTACTS,
        MainActivity.PANEL_CALENDAR,
        MainActivity.PANEL_FILES,
    };

    /** The bottom navigation item that switches to one tab. */
    static int buttonOf(int panel) {
        switch (panel) {
            case MainActivity.PANEL_MAIL:
                return R.id.nav_mail;
            case MainActivity.PANEL_CALENDAR:
                return R.id.nav_calendar;
            case MainActivity.PANEL_FILES:
                return R.id.nav_files;
            default:
                return R.id.nav_contacts;
        }
    }

    /** One tab's name, which its large title reads. */
    static int titleOf(int panel) {
        switch (panel) {
            case MainActivity.PANEL_MAIL:
                return R.string.mail_title;
            case MainActivity.PANEL_CALENDAR:
                return R.string.calendar_title;
            case MainActivity.PANEL_FILES:
                return R.string.files_title;
            default:
                return R.string.contacts_title;
        }
    }

    /** One tab's glyph. */
    static int iconOf(int panel) {
        switch (panel) {
            case MainActivity.PANEL_MAIL:
                return R.drawable.ic_domain_mail;
            case MainActivity.PANEL_CALENDAR:
                return R.drawable.ic_domain_calendar;
            case MainActivity.PANEL_FILES:
                return R.drawable.ic_folder_open;
            default:
                return R.drawable.ic_domain_contacts;
        }
    }

    private Domains() {}
}
