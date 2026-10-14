package org.pimalaya.setup;

import java.util.List;
import java.util.Set;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.Endpoints;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.pimalaya.client.Addressbook;
import org.pimalaya.contact.CardStore;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirSummary;

/**
 * The connection wizard's store work: the account its sign-ins add up to.
 *
 * <p>Saved onto whatever the address already had, with the address books it
 * subscribes. Runs on the calling thread, the main one, as the wizard does.
 */
final class AccountCommit {
    /** One domain the sign-ins connected: endpoints and credential. */
    record Connection(PimDomain domain, String baseUrl, String submitUrl, AccountCredential credential) {}

    private AccountCommit() {}

    /**
     * The one account the connections add up to, or null when there are none.
     *
     * <p>A mail connection signed in with a token names its user in its
     * endpoints ({@link Endpoints#asUser}).
     */
    static AccountEntry assemble(String email, List<Connection> connections) {
        AccountEntry connectedAccount = null;
        for (Connection connection : connections) {
            String baseUrl = connection.baseUrl();
            String submitUrl = connection.submitUrl();
            if (connection.domain() == PimDomain.MAIL && connection.credential().login.isEmpty()) {
                baseUrl = Endpoints.asUser(baseUrl, email);
                submitUrl = submitUrl == null ? null : Endpoints.asUser(submitUrl, email);
            }
            if (connectedAccount == null) {
                connectedAccount = AccountEntry.empty(email);
            }
            connectedAccount =
                    connectedAccount.with(connection.domain(), baseUrl, submitUrl, connection.credential());
        }
        return connectedAccount;
    }

    /**
     * Saves a connected account onto the stored one; answers what is stored.
     */
    static AccountEntry persist(SecureStore store, AccountEntry connected) {
        // NOTE: merged into whatever the address already had, so connecting a
        // domain onto an existing account keeps the domains it already covers
        // instead of replacing the account with a one-domain one.
        AccountEntry merged = connected;
        for (PimDomain domain : connected.domains()) {
            merged =
                    store.connect(
                            connected.email,
                            domain,
                            connected.connection(domain).baseUrl,
                            connected.connection(domain).submitUrl,
                            connected.credential(domain));
        }

        // NOTE: a grant that asked for the union (Google's incremental
        // authorization) covers the domains an earlier grant of the same
        // client signed in, so they move onto it and the old one is dropped:
        // one refresh token for the account.
        for (AccountCredential granted : connected.credentials().values()) {
            for (PimDomain domain : merged.regrantable(granted)) {
                merged =
                        store.connect(
                                merged.email,
                                domain,
                                merged.connection(domain).baseUrl,
                                merged.connection(domain).submitUrl,
                                granted);
            }
        }
        return merged;
    }

    /**
     * An account's address books as the user chose them.
     *
     * @param subscribed the URLs of the ones turned on
     * @param phone whether those also show in the phone's Contacts app
     */
    record BookChoice(
            String email, List<Addressbook> books, Set<String> subscribed, boolean phone) {}

    /**
     * Stores an account's address books and subscribes the chosen ones.
     *
     * <p>In the phone's Contacts app too when the choice says so.
     */
    static void books(CardStore cards, PimdirCollections collections, BookChoice choice) {
        String email = choice.email();
        List<Addressbook> books = choice.books();
        Set<String> subscribed = choice.subscribed();
        boolean phone = choice.phone();

        cards.replaceAddressbooks(email, books);
        collections.replace(email, PimdirSummary.CONTACT, PimdirCollections.of(email, books));

        for (Addressbook book : books) {
            boolean on = subscribed.contains(book.url());
            cards.setBookState(book.url(), on, on, on && phone);
        }
    }
}
