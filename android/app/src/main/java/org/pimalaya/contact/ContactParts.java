package org.pimalaya.contact;

import java.util.List;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.pimdir.PimdirDb;

/**
 * What the contact screens work over: the store, its contacts and books,
 * the merged pool, the bridge, and the activity's live account list
 * (mutated on the main thread, as the pool reads it).
 */
public record ContactParts(
        PimdirDb pimdir,
        CardStore cards,
        PimdirContacts contacts,
        ContactPool pool,
        PimalayaClient client,
        List<AccountEntry> accounts) {
    /** The account entry matching an email, or null. */
    AccountEntry accountFor(String email) {
        for (AccountEntry entry : accounts) {
            if (entry.email.equals(email)) {
                return entry;
            }
        }
        return null;
    }
}
