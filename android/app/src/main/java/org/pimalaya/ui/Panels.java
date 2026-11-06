package org.pimalaya.ui;

/** The host's screens: the lists, the pages and the overlays. */
public final class Panels {
    // NOTE: ids, not places (MainActivity.slotOf).
    /** The contacts list. */
    public static final int CONTACTS = 0;
    /** A contact's page. */
    public static final int CONTACT = 1;
    /** The mail list. */
    public static final int MAIL = 2;
    /** The agenda. */
    public static final int CALENDAR = 3;
    /** The mail composer. */
    public static final int COMPOSE = 4;
    /** A message's page. */
    public static final int MESSAGE = 5;
    /** An event's page. */
    public static final int EVENT_VIEW = 6;
    /** The files list. */
    public static final int FILES = 7;
    /** The Tasks list. */
    public static final int TASKS = 8;
    /** A task's page. */
    public static final int TASK = 9;
    /** The clarify flow over the Tasks inbox. */
    public static final int CLARIFY = 10;
    /** The weekly review checklist. */
    public static final int REVIEW = 11;
    /** The account settings overlay. */
    public static final int ACCOUNT = 21;
    /** The filter page overlay. */
    public static final int FILTER = 22;
    /** The deleted items overlay. */
    public static final int DELETED = 23;
    /** The app's settings overlay. */
    public static final int SETTINGS = 24;

    private Panels() {}
}
