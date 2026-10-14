package org.pimalaya.mail;

import android.content.Context;
import android.content.SharedPreferences;

import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirCollections;

/**
 * What mail remembers of each account outside the store, a prefs file each.
 *
 * <p>The trash, single-message erase, All Mail and account-wide listing,
 * inbox-only ticks, released links, provider-aliased sent copies. File names
 * and keys are those installed versions wrote: nothing lost on upgrade.
 */
public final class MailPolicy {
    /** Which accounts keep an All Mail collection (Gmail), by account id. */
    private static final String ALL_MAIL_PREFS = "mail-all-mail";

    /** Accounts whose inbox alone starts ticked, by account id. */
    private static final String INBOX_ONLY_PREFS = "mail-inbox-only";

    /** Where each account's trash mailbox is remembered, by address. */
    private static final String TRASH_PREFS = "mail-trash";

    /** Which accounts are listed account-wide, by account id. */
    private static final String ACCOUNT_WIDE_PREFS = "mail-account-wide";

    /** Which accounts had their cross-linked bodies released, by account id. */
    private static final String LINKS_REPAIRED_PREFS = "mail-links-repaired";

    /** Accounts whose servers erase no single message (no UIDPLUS), by id. */
    private static final String MARKING_PREFS = "mail-marks-deleted";

    /** Sent copies a provider filed under its own Message-ID, by that id. */
    private static final String SENT_ALIAS_PREFS = "mail-sent-alias";


    private final Context context;
    private final PimdirAccount accounts;

    MailPolicy(Context context, PimdirAccount accounts) {
        this.context = context;
        this.accounts = accounts;
    }

    /**
     * Remembers whether an account's mailboxes are listed account-wide.
     *
     * <p>One listing, each mailbox a projection (Gmail's labels), so they share
     * one floor and widen together.
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

    /**
     * Remembers whether an account keeps an All Mail collection.
     *
     * <p>{@link PimdirCollections#ALL_MAIL}.
     */
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

    /** Whether an account keeps an All Mail collection. */
    static boolean keepsAllMail(Context context, String accountId) {
        return context.getSharedPreferences(ALL_MAIL_PREFS, Context.MODE_PRIVATE)
                .getBoolean(accountId, false);
    }

    /** Whether an account's mailboxes are listed account-wide. */
    static boolean accountWide(Context context, String accountId) {
        return context.getSharedPreferences(ACCOUNT_WIDE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(accountId, false);
    }

    /**
     * Remembers whether an account's server erases one message alone.
     *
     * <p>As its last session said ({@code MailSession.expungesOne}); kept
     * beside the trash since an offline delete in the trash needs it.
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
     * Whether a trash delete erases the message, else marks it deleted.
     *
     * <p>An account no session recorded yet reads as erasing, as most do.
     */
    boolean expungesOne(String accountEmail) {
        return !context.getSharedPreferences(MARKING_PREFS, Context.MODE_PRIVATE)
                .getBoolean(accounts.idOf(accountEmail), false);
    }

    /**
     * The mailbox one account deletes into, empty when it marks none.
     *
     * <p>Kept rather than asked at delete time, so an offline delete still
     * knows whether the message moves out of the list or is only marked.
     */
    public String trashOf(String accountEmail) {
        return context.getSharedPreferences(TRASH_PREFS, Context.MODE_PRIVATE)
                .getString(accountEmail, "");
    }

    /**
     * Remembers the copy staged for {@code messageId} arrives as {@code filed}.
     *
     * <p>The id the provider stamped instead (Gmail), so the sync lands the
     * staged copy on the provider's rather than listing a second.
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

    /** The staged sent copy's id for one filed as {@code filed}, else null. */
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

    /** Whether an account's inbox alone starts ticked. */
    boolean inboxOnly(String accountId) {
        return context.getSharedPreferences(INBOX_ONLY_PREFS, Context.MODE_PRIVATE)
                .getBoolean(accountId, false);
    }

    /** Forgets whether an account's inbox alone starts ticked. */
    void forgetInboxOnly(String accountId) {
        context.getSharedPreferences(INBOX_ONLY_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(accountId)
                .apply();
    }

    /** Remembers the mailbox an account deletes into, empty for none. */
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

    /** Whether an account's cross-linked bodies were released. */
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
