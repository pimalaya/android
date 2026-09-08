package org.pimalaya;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.pimalaya.client.Account;

/**
 * One address, and everything the app holds for it: where each domain
 * it covers lives ({@link AccountConnection}), and the credentials
 * those domains sign in with ({@link AccountCredential}).
 *
 * <p>One entry per address and not one per domain, because the person
 * has one account: an expired token is one repair, and a contact card
 * has to be relatable to the sender of a message without reassembling
 * an identity that onboarding took apart.
 *
 * <p>The two maps are the important part. Endpoints are per domain,
 * credentials are per consent, and one consent routinely covers several
 * domains, so a credential is stored once and named by each connection
 * that uses it. Renewing it is one write, and no two domains can end up
 * holding different halves of the same sign-in.
 */
final class AccountEntry {
    final String email;

    private final Map<PimDomain, AccountConnection> connections;
    private final Map<String, AccountCredential> credentials;

    AccountEntry(
            String email,
            Map<PimDomain, AccountConnection> connections,
            Map<String, AccountCredential> credentials) {
        this.email = email;
        // NOTE: built and filled, not copy-constructed: EnumMap cannot
        // take its key type from an empty plain map, and an account with
        // no domain yet is exactly how one is built up.
        this.connections = new EnumMap<>(PimDomain.class);
        this.connections.putAll(connections);
        this.credentials = new HashMap<>(credentials);
    }

    /** One identity covering nothing yet, to connect domains onto. */
    static AccountEntry empty(String email) {
        return new AccountEntry(email, Map.of(), Map.of());
    }

    /** One connected identity covering a single domain. */
    static AccountEntry of(
            String email, PimDomain domain, String baseUrl, AccountCredential credential) {
        return empty(email).with(domain, baseUrl, credential);
    }

    /** Where a domain lives, or null when the account covers none. */
    AccountConnection connection(PimDomain domain) {
        return connections.get(domain);
    }

    /** What a domain signs in with, or null when it covers none. */
    AccountCredential credential(PimDomain domain) {
        AccountConnection connection = connections.get(domain);
        return connection == null ? null : credentials.get(connection.credentialId);
    }

    /** Every credential this account holds, by id. */
    Map<String, AccountCredential> credentials() {
        return Collections.unmodifiableMap(credentials);
    }

    /** Whether this account is connected for a domain. */
    boolean covers(PimDomain domain) {
        return connections.containsKey(domain);
    }

    /**
     * The server one domain talks to, credential included, or null when
     * the account does not cover it: the shorthand for the many callers
     * that want somewhere to send a request.
     */
    Account server(PimDomain domain) {
        AccountConnection connection = connections.get(domain);
        AccountCredential credential = credential(domain);
        if (connection == null || credential == null) {
            return null;
        }
        return new Account(
                connection.baseUrl, credential.login, credential.secret, connection.submitUrl);
    }

    /** The domains this account covers, in the enum's order. */
    Set<PimDomain> domains() {
        return Collections.unmodifiableSet(connections.keySet());
    }

    /**
     * The same account with one domain connected or reconnected, on the
     * given endpoint and credential. Naming a credential another domain
     * already uses is how one grant comes to cover several.
     *
     * <p>Returns a new entry rather than mutating: the roster is read on
     * the main thread and written from sync threads, and an account that
     * gains a domain half-way through a read would be worse than one
     * that gains it late.
     */
    AccountEntry with(PimDomain domain, String baseUrl, AccountCredential credential) {
        return with(domain, baseUrl, null, credential);
    }

    /**
     * The same account with one domain connected on two endpoints: where
     * it is read and where it is written. Only mail has a second, and
     * only over SMTP.
     */
    AccountEntry with(
            PimDomain domain, String baseUrl, String submitUrl, AccountCredential credential) {
        Map<PimDomain, AccountConnection> merged = new EnumMap<>(connections);
        merged.put(domain, new AccountConnection(baseUrl, credential.id, submitUrl));

        Map<String, AccountCredential> held = new HashMap<>(credentials);
        held.put(credential.id, credential);

        return new AccountEntry(email, merged, held);
    }

    /**
     * The same account with one domain's credential renewed.
     *
     * <p>One write, and every domain that named that credential is
     * renewed with it. That is the property the split exists for: there
     * is no second copy to update, and therefore none to forget.
     */
    AccountEntry refreshed(PimDomain domain, String accessToken, String refreshToken) {
        AccountCredential credential = credential(domain);
        if (credential == null) {
            return this;
        }

        Map<String, AccountCredential> held = new HashMap<>(credentials);
        held.put(credential.id, credential.withTokens(accessToken, refreshToken));

        return new AccountEntry(email, connections, held);
    }

    /**
     * The same account with one domain disconnected, and its credential
     * dropped when that domain was the last to use it: a secret nothing
     * can present is one the app has no business keeping.
     */
    AccountEntry without(PimDomain domain) {
        Map<PimDomain, AccountConnection> remaining = new EnumMap<>(connections);
        AccountConnection removed = remaining.remove(domain);

        Map<String, AccountCredential> held = new HashMap<>(credentials);
        if (removed != null && !usedBy(remaining, removed.credentialId)) {
            held.remove(removed.credentialId);
        }

        return new AccountEntry(email, remaining, held);
    }

    private static boolean usedBy(
            Map<PimDomain, AccountConnection> connections, String credentialId) {
        for (AccountConnection connection : connections.values()) {
            if (credentialId.equals(connection.credentialId)) {
                return true;
            }
        }
        return false;
    }
}
