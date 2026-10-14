/**
 * Setup: connecting an account, and its settings.
 *
 * <p>{@link OnboardingFlow} is the connection wizard: discovery, the options
 * per domain ({@link SetupPlan}), sign-in and the address book selection,
 * committed by {@link AccountCommit}. {@link OauthFlow} runs the OAuth 2.0
 * grants, {@link PendingGrantStore} sealing one across the browser hop.
 *
 * <p>{@link AccountSettings} is one account's page behind the drawer, and
 * {@link AccountRemoval} deletes an account across every domain. The package
 * sits above the domains and sync, which it drives; {@link SetupShell} is
 * what it asks of the activity.
 */
package org.pimalaya.setup;
