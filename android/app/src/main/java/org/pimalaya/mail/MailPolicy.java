package org.pimalaya.mail;

import android.content.Context;
import android.content.SharedPreferences;

import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirCollections;

/**
 * What mail remembers of each account outside the store, one shared
 * preferences file per setting: the trash, whether the server erases one
 * message alone, whether the account keeps an All Mail and is listed
 * account-wide, whether its inbox alone starts ticked, whether its
 * cross-linked bodies were released, and the sent copies a provider filed
 * under its own {@code Message-ID}. The file names and keys are the ones
 * installed versions wrote, so nothing is lost across an upgrade.
 */
public final class MailPolicy {
    /** Which accounts keep an All Mail collection (Gmail), by account id. */
    private static final String ALL_MAIL_PREFS = "mail-all-mail";

    /** Which accounts keep their inbox alone ticked to begin with, by account id. */
    private static final String INBOX_ONLY_PREFS = "mail-inbox-only";

    /** Where each account's trash mailbox is remembered, by address. */
    private static final String TRASH_PREFS = "mail-trash";

    /** Which accounts are listed account-wide, by account id. */
    private static final String ACCOUNT_WIDE_PREFS = "mail-account-wide";

    /** Which accounts had their cross-linked bodies released, by account id. */
    private static final String LINKS_REPAIRED_PREFS = "mail-links-repaired";

    /** Which accounts' servers erase no single message (no UIDPLUS), by account id. */
    private static final String MARKING_PREFS = "mail-marks-deleted";

    /** Which sent copies a provider filed under its own Message-ID, by that id. */
    private static final String SENT_ALIAS_PREFS = "mail-sent-alias";


    private final Context context;
    private final PimdirAccount accounts;

    MailPolicy(Context context, PimdirAccount accounts) {
        this.context = context;
        this.accounts = accounts;
    }

    /**
     * Remembers whether an account's mailboxes are listed account-wide: one
     * listing of the whole account, each mailbox a projection of it (Gmail's
     * labels), so they share one floor and widen together.
     */
    static void markAccountWide(Context context, String accountId, boolean accountWide) {
        SharedPreferences.Editor prefs =
                context.getSharedPreferences(ACCOUNT_WIDE_PREFS, Context.MODE_PRIVATE).edit();
        if (accountWide) {
            prefs.putBoolean(accountId, true);
        } else {
            prefs.remove(accountId);
        }
        prefs.apply();
    }

    /** Remembers whether an account keeps an All Mail collection ({@link PimdirCollections#ALL_MAIL}). */
    static void markAllMail(Context context, String accountId, boolean allMail) {
        SharedPreferences.Editor prefs =
                context.getSharedPreferences(ALL_MAIL_PREFS, Context.MODE_PRIVATE).edit();
        if (allMail) {
            prefs.putBoolean(accountId, true);
        } else {
            prefs.remove(accountId);
        }
        prefs.apply();
    }

    /** Whether an account keeps an All Mail collection ({@link #markAllMail}). */
    static boolean keepsAllMail(Context context, String accountId) {
        return context.getSharedPreferences(ALL_MAIL_PREFS, Context.MODE_PRIVATE)
                .getBoolean(accountId, false);
    }

    /** Whether an account's mailboxes are listed account-wide ({@link #markAccountWide}). */
    static boolean accountWide(Context context, String accountId) {
        return context.getSharedPreferences(ACCOUNT_WIDE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(accountId, false);
    }

    /**
     * Remembers whether an account's server erases one message alone, as
     * its last session said ({@code MailSession.expungesOne}).
     *
     * <p>Kept beside the trash for the same reason: what it decides is what
     * a delete in the trash stages, which a delete with no network has to
     * know.
     */
    static void markExpungesOne(Context context, String accountId, boolean expungesOne) {
        SharedPreferences.Editor prefs =
                context.getSharedPreferences(MARKING_PREFS, Context.MODE_PRIVATE).edit();
        if (expungesOne) {
            prefs.remove(accountId);
        } else {
            prefs.putBoolean(accountId, true);
        }
        prefs.apply();
    }

    /**
     * Whether a delete in one account's trash erases the message, else only
     * marks it {@code \Deleted} ({@link #markExpungesOne}). An account no
     * session recorded yet reads as erasing, which most servers do.
     */
    boolean expungesOne(String accountEmail) {
        return !context.getSharedPreferences(MARKING_PREFS, Context.MODE_PRIVATE)
                .getBoolean(accounts.idOf(accountEmail), false);
    }

    /**
     * The mailbox one account deletes into, empty when it marks none.
     *
     * <p>Kept beside the roster rather than asked for at the moment of a
     * delete, which is the whole difference between a delete that works with
     * no network and one that does not: what it decides is whether the message
     * moves, and leaves the list, or is only marked and stays in it.
     */
    public String trashOf(String accountEmail) {
        return context.getSharedPreferences(TRASH_PREFS, Context.MODE_PRIVATE)
                .getString(accountEmail, "");
    }

    /**
     * Remembers that the sent copy staged for {@code messageId} arrives
     * under {@code filed}, the {@code Message-ID} the provider stamped on
     * what it sent instead (Gmail), so the sync lands the staged copy on
     * the provider's rather than listing a second.
     */
    public void aliasSentCopy(String messageId, String filed) {
        if (filed.equals(messageId)) {
            return;
        }
        context.getSharedPreferences(SENT_ALIAS_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(filed, messageId)
                .apply();
    }

    /**
     * The {@code Message-ID} of the sent copy staged for a message the
     * provider filed under {@code filed} ({@link #aliasSentCopy}), else
     * null.
     */
    String stagedSentCopy(String filed) {
        return context.getSharedPreferences(SENT_ALIAS_PREFS, Context.MODE_PRIVATE)
                .getString(filed, null);
    }

    /** Forgets an alias once its staged copy landed on the provider's. */
    void forgetSentAlias(String filed) {
        context.getSharedPreferences(SENT_ALIAS_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(filed)
                .apply();
    }

    /** Remembers that an account keeps its inbox alone ticked to begin with. */
    void markInboxOnly(String accountId) {
        context.getSharedPreferences(INBOX_ONLY_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(accountId, true)
                .apply();
    }

    /** Whether an account keeps its inbox alone ticked to begin with ({@link #markInboxOnly}). */
    boolean inboxOnly(String accountId) {
        return context.getSharedPreferences(INBOX_ONLY_PREFS, Context.MODE_PRIVATE)
                .getBoolean(accountId, false);
    }

    /** Forgets whether an account keeps its inbox alone ticked, with the account. */
    void forgetInboxOnly(String accountId) {
        context.getSharedPreferences(INBOX_ONLY_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(accountId)
                .apply();
    }

    /** Remembers the mailbox one account deletes into, empty for none ({@link #trashOf}). */
    void markTrash(String accountEmail, String trash) {
        context.getSharedPreferences(TRASH_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(accountEmail, trash)
                .apply();
    }

    /** Forgets the mailbox one account deletes into, with the account. */
    void forgetTrash(String accountEmail) {
        context.getSharedPreferences(TRASH_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(accountEmail)
                .apply();
    }

    /** Whether an account had its cross-linked bodies released ({@link MailStore#repairLinks}). */
    boolean linksRepaired(String accountId) {
        return context.getSharedPreferences(LINKS_REPAIRED_PREFS, Context.MODE_PRIVATE)
                .getBoolean(accountId, false);
    }

    /** Remembers that an account had its cross-linked bodies released. */
    void markLinksRepaired(String accountId) {
        context.getSharedPreferences(LINKS_REPAIRED_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(accountId, true)
                .apply();
    }
}
