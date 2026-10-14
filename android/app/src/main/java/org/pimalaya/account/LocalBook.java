package org.pimalaya.account;

import org.pimalaya.client.Account;

/**
 * The built-in, undeletable "On this device" account and its one addressbook.
 *
 * <p>In memory only, seeded into the card store on launch, skipped by remote
 * sync. Its {@code local://} URL makes the bridge report a transport-less,
 * non-account-level {@code local} backend, keyed per collection like CardDAV.
 */
public final class LocalBook {
    /** Base URL and account-email key of the local account (never an email). */
    public static final String ACCOUNT = "local://on-this-device";

    /** Collection URL of the local account's single addressbook. */
    public static final String URL = "local://on-this-device/contacts";

    /** Stable addressbook id of the local book. */
    public static final String ID = "contacts";

    private LocalBook() {}

    /** True for the local account's email key or any local URL. */
    public static boolean is(String emailOrUrl) {
        return emailOrUrl != null && emailOrUrl.startsWith("local://");
    }

    /** The synthesized account; login and password unused (no transport). */
    public static AccountEntry account() {
        return AccountEntry.of(
                ACCOUNT, PimDomain.CONTACTS, ACCOUNT, AccountCredential.password("", ""));
    }
}
