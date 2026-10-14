package org.pimalaya.ui;

/** The host's screens: the content flipper's panels and the overlays. */
public final class Panels {
    // NOTE: everything from contacts to event is the content flipper's
    // child indexes, in the order activity_main.xml includes them; auth
    // and account are whole-frame overlays sliding over it, bar included
    // (only the FAB stays above them), so their ids only have to miss
    // the flipper's range.
    public static final int CONTACTS = 0;
    public static final int CONTACT = 1;
    public static final int MAIL = 2;
    public static final int CALENDAR = 3;
    public static final int COMPOSE = 4;
    public static final int MESSAGE = 5;
    public static final int EVENT_VIEW = 6;
    public static final int FILES = 7;
    public static final int ACCOUNT = 21;
    public static final int FILTER = 22;
    public static final int DELETED = 23;

    private Panels() {}
}
