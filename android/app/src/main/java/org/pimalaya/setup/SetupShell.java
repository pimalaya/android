package org.pimalaya.setup;

import java.util.Set;
import java.util.function.Consumer;
import org.pimalaya.account.PimDomain;
import org.pimalaya.phone.PhoneMirror;

/**
 * What the setup screens ask of the activity beyond the shared chrome.
 *
 * <p>The auth flow's steps, the permission prompts, and the lists to reload.
 */
public interface SetupShell {
    /** Navigates the auth flow forward: the step slides in from the right. */
    void showAuth(int step);

    /** The auth flow's displayed step. */
    int authStep();

    /**
     * Asks the permissions of the wanted phone mirrors in one prompt.
     *
     * <p>Hands {@code done} the ones granted, on the main thread.
     */
    void askMirrors(Set<PhoneMirror> wanted, Consumer<Set<PhoneMirror>> done);

    /**
     * Asks the notifications permission where Android takes one.
     *
     * <p>Hands {@code done} whether the app may notify, on the main thread.
     */
    void askNotifications(Consumer<Boolean> done);

    /** Reloads one domain's list. */
    void reload(PimDomain domain);

    /** A filter changed: the lists and the bar's filter icon follow. */
    void filterChanged();
}
