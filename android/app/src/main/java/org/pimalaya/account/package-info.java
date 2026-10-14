/**
 * Accounts: who the person is connected as, and what each list and sync
 * takes of it.
 *
 * <p>An {@link AccountEntry} is one address, a connection per domain
 * ({@link AccountConnection}) and a credential per consent
 * ({@link AccountCredential}), sealed by {@link SecureStore} under a Keystore
 * key; {@link TokenRenewal} renews one credential at a time.
 *
 * <p>Beside them, the vocabulary the packages above share: the domains
 * ({@link PimDomain}), where a credential may go ({@link Endpoints}), the
 * shipped OAuth clients ({@link Oauth}), the on-device book
 * ({@link LocalBook}).
 *
 * <p>Which accounts take part ({@link AccountActivation}) and which of their
 * collections a list shows ({@link MergedFilter}) decide what a sync takes
 * ({@link SyncScope}). The package sits right above the bridge client.
 */
package org.pimalaya.account;
