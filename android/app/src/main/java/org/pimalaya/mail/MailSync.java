package org.pimalaya.mail;

import java.util.List;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.client.MailSession;

/** What the mail screens ask of the app's foreground sync (the coordinator in sync answers). */
public interface MailSync {
    /** The mail list's pull, under a role chip ({@code role}, null for none). */
    void syncMail(String role);

    /** Whether the sync is held: a step asked for now would do nothing. */
    boolean syncHeld();

    /** Whether the network is not metered. */
    boolean wifi();

    /** Whether a network is there at all. */
    boolean online();

    /** Takes the mail list footer's steps. */
    void loadMore(List<MailList.Step> steps);

    /** Opens one account's mail connection. */
    MailSession openMail(AccountEntry account);

    /** The store id one account's collections are namespaced under. */
    String accountIdOf(String email);

    /** Moves the windows of some accounts later, {@code done} run on the main thread. */
    void releaseWindow(List<String> emails, String date, Runnable done);
}
