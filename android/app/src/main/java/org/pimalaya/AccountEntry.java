package org.pimalaya;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.pimalaya.client.Account;

/**
 * One connected identity: an address, and what it is connected for.
 *
 * <p>The address is the account. Which domains it covers, where each of them
 * lives and how each of them signs in are properties of it
 * ({@link AccountConnection}), not three accounts wearing the same address.
 *
 * <p>That distinction is the whole model. Auth and domain are different axes:
 * one provider wants one app password for everything, another wants a separate
 * consent per scope, and both are the same person with the same account. Split
 * at the front door instead, and every cross-domain question afterwards has to
 * put the identity back together, an expired token becomes three repairs, and
 * connecting calendars next month means a second account rather than a step.
 *
 * <p>An OAuth connection carries an empty login, so the address cannot be
 * recovered from the credentials and is kept here alongside them.
 */
final class AccountEntry {
    final String email;

    private final Map<PimDomain, AccountConnection> connections;

    AccountEntry(String email, Map<PimDomain, AccountConnection> connections) {
        this.email = email;
        this.connections = new EnumMap<>(connections);
    }

    /** One connected identity covering a single domain. */
    static AccountEntry of(String email, PimDomain domain, AccountConnection connection) {
        return new AccountEntry(email, Map.of(domain, connection));
    }

    /** What this account holds for a domain, or null when it covers none. */
    AccountConnection connection(PimDomain domain) {
        return connections.get(domain);
    }

    /** Whether this account is connected for a domain. */
    boolean covers(PimDomain domain) {
        return connections.containsKey(domain);
    }

    /**
     * The server one domain talks to, or null when the account does not cover
     * it: the shorthand for the many callers that want the endpoint and not the
     * credentials around it.
     */
    Account server(PimDomain domain) {
        AccountConnection connection = connections.get(domain);
        return connection == null ? null : connection.account;
    }

    /** The domains this account covers, in the enum's order. */
    Set<PimDomain> domains() {
        return Collections.unmodifiableSet(connections.keySet());
    }

    /**
     * The same account with one domain connected or reconnected.
     *
     * <p>Returns a new entry rather than mutating: the roster is read on the
     * main thread and written from sync threads, and an account that gains a
     * domain half-way through a read would be worse than one that gains it
     * late.
     */
    AccountEntry with(PimDomain domain, AccountConnection connection) {
        Map<PimDomain, AccountConnection> merged = new EnumMap<>(connections);
        merged.put(domain, connection);
        return new AccountEntry(email, merged);
    }

    /** The same account with one domain disconnected. */
    AccountEntry without(PimDomain domain) {
        Map<PimDomain, AccountConnection> remaining = new EnumMap<>(connections);
        remaining.remove(domain);
        return new AccountEntry(email, remaining);
    }
}
