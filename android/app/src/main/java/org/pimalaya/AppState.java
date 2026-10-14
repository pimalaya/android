package org.pimalaya;

/** What the activity, the background check and the sync adapters tell each other. */
final class AppState {
    /** Whether the app is on screen, which a background run leaves alone. */
    volatile boolean onScreen;

    /** When a run last synced, for the app to reload what it wrote. */
    volatile long backgroundRan;

    /** Whether a run is syncing, for the app's dock. */
    volatile boolean backgroundRunning;

    /** Called on the run's thread when it ends; the app's, while it lives. */
    volatile Runnable onBackgroundEnd;

    /** When a pass last brought a Contacts-app edit in, for the app to reload. */
    volatile long contactsIngested;

    /** When a pass last brought a calendar-app edit in, for the app to reload. */
    volatile long calendarsIngested;
}
