package org.pimalaya.account;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One OAuth credential renewed at a time, process-wide.
 *
 * <p>Workers hitting 401 at once would spend one refresh token twice, which
 * rotating providers (Fastmail, Stalwart) revoke. So it locks the credential,
 * reuses a renewal stored meanwhile ({@link SecureStore#renew}).
 */
public final class TokenRenewal {
    /**
     * The tokens a refresh returned; a null refresh token keeps the old one.
     */
    public static final class Renewed {
        final String accessToken;
        final String refreshToken;

        /** The tokens a refresh returned. */
        public Renewed(String accessToken, String refreshToken) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }
    }

    /** The token request itself, against the credential's token endpoint. */
    public interface Refresh {
        Renewed run(AccountCredential credential);
    }

    private static final Map<String, Object> LOCKS = new HashMap<>();

    private TokenRenewal() {}

    /**
     * Renews {@code domain}'s credential, or reuses one stored meanwhile.
     *
     * <p>Returns the stored account.
     */
    public static AccountEntry renew(
            SecureStore store, AccountEntry entry, PimDomain domain, Refresh refresh) {
        AccountCredential held = entry.credential(domain);
        synchronized (lock(entry.email + "\n" + held.id)) {
            AccountCredential current = stored(store, entry.email, domain);
            boolean same = current != null && current.id.equals(held.id);
            if (same
                    && !(Objects.equals(current.secret, held.secret)
                            && Objects.equals(current.refreshToken(), held.refreshToken()))) {
                return store.find(entry.email);
            }

            AccountCredential using = same ? current : held;
            Renewed renewed = refresh.run(using);
            String refreshToken =
                    renewed.refreshToken != null ? renewed.refreshToken : using.refreshToken();
            AccountEntry updated =
                    store.renew(entry.email, domain, renewed.accessToken, refreshToken);
            return updated != null
                    ? updated
                    : entry.refreshed(domain, renewed.accessToken, refreshToken);
        }
    }

    private static AccountCredential stored(SecureStore store, String email, PimDomain domain) {
        AccountEntry stored = store.find(email);
        return stored == null ? null : stored.credential(domain);
    }

    private static Object lock(String key) {
        synchronized (LOCKS) {
            return LOCKS.computeIfAbsent(key, ignored -> new Object());
        }
    }
}
