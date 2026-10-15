package org.pimalaya.account;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pimalaya.client.Account;

/**
 * One address, with every domain's connection and the credentials they use.
 *
 * <p>Per address, not per domain: an expired token is one repair, a card
 * relates to a sender. A credential is stored once and named by each connection
 * using it, so renewing it is one write.
 */
public final class AccountEntry {
    /** The address, which keys the account. */
    public final String email;

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
    public static AccountEntry empty(String email) {
        return new AccountEntry(email, Map.of(), Map.of());
    }

    /** One connected identity covering a single domain. */
    static AccountEntry of(
            String email, PimDomain domain, String baseUrl, AccountCredential credential) {
        return empty(email).with(domain, baseUrl, credential);
    }

    /** The account entry matching an email, or null. */
    public static AccountEntry find(List<AccountEntry> accounts, String email) {
        for (AccountEntry entry : accounts) {
            if (entry.email.equals(email)) {
                return entry;
            }
        }
        return null;
    }

    /** An address as a release log may name it: its domain alone. */
    public static String redacted(String address) {
        if (address == null) {
            return "?";
        }
        int at = address.lastIndexOf('@');
        return at < 0 ? "*" : "*" + address.substring(at);
    }

    /** Where a domain lives, or null when the account covers none. */
    public AccountConnection connection(PimDomain domain) {
        return connections.get(domain);
    }

    /** What a domain signs in with, or null when it covers none. */
    public AccountCredential credential(PimDomain domain) {
        AccountConnection connection = connections.get(domain);
        return connection == null ? null : credentials.get(connection.credentialId);
    }

    /** Every credential this account holds, by id. */
    public Map<String, AccountCredential> credentials() {
        return Collections.unmodifiableMap(credentials);
    }

    /** Whether this account is connected for a domain. */
    public boolean covers(PimDomain domain) {
        return connections.containsKey(domain);
    }

    /**
     * The server one domain talks to, credential included.
     *
     * <p>Null when the account does not cover the domain.
     */
    public Account server(PimDomain domain) {
        AccountConnection connection = connections.get(domain);
        AccountCredential credential = credential(domain);
        if (connection == null || credential == null) {
            return null;
        }
        return new Account(
                connection.baseUrl, credential.login, credential.secret, connection.submitUrl);
    }

    /** The domains this account covers, in the enum's order. */
    public Set<PimDomain> domains() {
        return Collections.unmodifiableSet(connections.keySet());
    }

    /**
     * Every scope this account's grants from one OAuth client hold, or null.
     *
     * <p>One space-separated list: what a new grant from that client asks for
     * beside its own, so it can replace them.
     */
    public String grantedScope(String clientId) {
        java.util.List<String> scopes = new java.util.ArrayList<>();
        for (AccountConnection connection : connections.values()) {
            AccountCredential held = credentials.get(connection.credentialId);
            if (held != null && held.renewable() && clientId.equals(held.renewal.grant().clientId())) {
                scopes.add(held.renewal.grant().scope());
            }
        }
        return Oauth.union(scopes.toArray(new String[0]));
    }

    /**
     * The domains a new grant can take over.
     *
     * <p>Those signed in with another grant of the same client and token
     * endpoint, every scope of which the new one holds. A grant whose scopes
     * were never recorded is kept.
     */
    public java.util.List<PimDomain> regrantable(AccountCredential granted) {
        java.util.List<PimDomain> domains = new java.util.ArrayList<>();
        if (!granted.renewable()) {
            return domains;
        }
        for (Map.Entry<PimDomain, AccountConnection> entry : connections.entrySet()) {
            AccountCredential held = credentials.get(entry.getValue().credentialId);
            if (held != null
                    && !held.id.equals(granted.id)
                    && held.renewable()
                    && held.renewal.grant().clientId() != null
                    && held.renewal.grant().clientId().equals(granted.renewal.grant().clientId())
                    && held.renewal.grant().tokenEndpoint() != null
                    && held.renewal.grant().tokenEndpoint().equals(granted.renewal.grant().tokenEndpoint())
                    && Oauth.covers(granted.renewal.grant().scope(), held.renewal.grant().scope())) {
                domains.add(entry.getKey());
            }
        }
        return domains;
    }

    /**
     * The same account with one domain (re)connected.
     *
     * <p>A credential another domain uses makes one grant cover both. Returns a
     * new entry: the roster is read on the main thread, written from sync
     * threads.
     */
    AccountEntry with(PimDomain domain, String baseUrl, AccountCredential credential) {
        return with(domain, baseUrl, null, credential);
    }

    /**
     * The same account with one domain connected on read and write endpoints.
     *
     * <p>Only mail has a second, and only over SMTP.
     */
    public AccountEntry with(
            PimDomain domain, String baseUrl, String submitUrl, AccountCredential credential) {
        Map<PimDomain, AccountConnection> merged = new EnumMap<>(connections);
        AccountConnection replaced =
                merged.put(domain, new AccountConnection(baseUrl, credential.id, submitUrl));

        Map<String, AccountCredential> held = new HashMap<>(credentials);
        held.put(credential.id, credential);
        // NOTE: a domain moved onto another credential may leave its old one
        // named by nothing, and a secret nothing can present is dropped.
        if (replaced != null && !usedBy(merged, replaced.credentialId)) {
            held.remove(replaced.credentialId);
        }

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
