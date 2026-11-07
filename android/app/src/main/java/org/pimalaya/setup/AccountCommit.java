package org.pimalaya.setup;

import android.content.Context;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.Endpoints;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.pimalaya.client.Addressbook;
import org.pimalaya.contact.CardStore;
import org.pimalaya.contact.PimdirContacts;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.sync.FirstSync;
import org.pimalaya.sync.SignInRefusals;
import org.pimalaya.sync.SyncPasses;

/**
 * The connection wizard's store work: the account its sign-ins add up to.
 *
 * <p>Saved onto whatever the address already had, with the address books it
 * subscribes. Runs on the calling thread, the main one, as the wizard does.
 */
final class AccountCommit {
    /**
     * One domain the sign-ins connected: endpoints and credential, and the
     * credential submission signs in with when it is not reading's, else null.
     */
    record Connection(
            PimDomain domain,
            String baseUrl,
            String submitUrl,
            AccountCredential credential,
            AccountCredential sending) {
        Connection(
                PimDomain domain, String baseUrl, String submitUrl, AccountCredential credential) {
            this(domain, baseUrl, submitUrl, credential, null);
        }
    }

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
            }
            AccountCredential signing =
                    connection.sending() == null ? connection.credential() : connection.sending();
            if (connection.domain() == PimDomain.MAIL
                    && submitUrl != null
                    && signing.login.isEmpty()) {
                submitUrl = Endpoints.asUser(submitUrl, email);
            }
            if (connectedAccount == null) {
                connectedAccount = AccountEntry.empty(email);
            }
            connectedAccount =
                    connectedAccount.with(
                            connection.domain(),
                            baseUrl,
                            submitUrl,
                            connection.credential(),
                            connection.sending());
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
                            connected.credential(domain),
                            connected.submitCredential(domain));
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
                                granted,
                                merged.submitCredential(domain));
            }
        }
        return merged;
    }

    /** A saved sign-in again: the account, its moved domains, dropped books. */
    record Again(AccountEntry stored, Set<PimDomain> moved, List<String> books) {}

    /** Saves a sign-in again; a domain moved elsewhere is dropped, relisted. */
    static Again again(
            Context context, SyncPasses.Parts parts, AccountEntry stored, AccountEntry connected) {
        Set<PimDomain> moved = moved(stored, connected);
        AccountRemoval removal =
                new AccountRemoval(context, parts, new PimdirContacts(parts.pimdir()));
        List<String> books = new ArrayList<>();
        for (PimDomain domain : moved) {
            books.addAll(removal.forget(stored.email, domain));
        }
        AccountEntry saved = persist(parts.accounts(), connected);
        for (PimDomain domain : connected.domains()) {
            if (moved.contains(domain) || !stored.covers(domain)) {
                FirstSync.owe(context, saved.email, domain);
            }
        }
        SignInRefusals.forget(context, saved.email);
        return new Again(saved, moved, books);
    }

    /** The domains both cover that {@code connected} moves elsewhere. */
    static Set<PimDomain> moved(AccountEntry stored, AccountEntry connected) {
        Set<PimDomain> moved = EnumSet.noneOf(PimDomain.class);
        for (PimDomain domain : connected.domains()) {
            if (!stored.covers(domain)) {
                continue;
            }
            String before = stored.credential(domain).login;
            String after = connected.credential(domain).login;
            boolean relogged =
                    !before.isEmpty() && !after.isEmpty() && !before.equalsIgnoreCase(after);
            if (relogged
                    || moves(
                            stored.connection(domain).baseUrl,
                            connected.connection(domain).baseUrl)) {
                moved.add(domain);
            }
        }
        return moved;
    }

    /** Whether two base URLs are two places: another server or protocol. */
    static boolean moves(String before, String after) {
        return !placeOf(before).equals(placeOf(after));
    }

    /** A base URL as a place: no user nor trailing slash, case folded. */
    private static String placeOf(String url) {
        String place = url == null ? "" : url;
        try {
            java.net.URI uri = java.net.URI.create(place);
            if (uri.getHost() != null) {
                place =
                        new java.net.URI(
                                        uri.getScheme(),
                                        null,
                                        uri.getHost(),
                                        uri.getPort(),
                                        uri.getPath(),
                                        uri.getQuery(),
                                        null)
                                .toString();
            }
        } catch (IllegalArgumentException | java.net.URISyntaxException unread) {
            // NOTE: a marker URL no URI reads is compared as written.
        }
        if (place.endsWith("/")) {
            place = place.substring(0, place.length() - 1);
        }
        return place.toLowerCase(java.util.Locale.ROOT);
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
