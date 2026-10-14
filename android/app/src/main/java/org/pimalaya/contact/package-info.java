/**
 * Contacts: address books and cards, their sync with servers and the phone's
 * Contacts app, and the screens over the merged view.
 *
 * <p>{@link PimdirContacts} stores the cards on pimdir, {@link CardStore} the
 * app's own state about them (a book's switches, the merged view's link
 * exceptions). {@link ContactEngine} syncs a book with its server, and
 * {@link ContactStager} stages an edit for the next pass.
 *
 * <p>{@link ContactPhonePass} syncs a book with the phone's raw contacts
 * through {@link PhoneRemote}, its rows mapped by {@link Mapping} and pinned
 * on the JVM (docs/contacts-mapping.md).
 *
 * <p>The list merges the replicas sharing a vCard UID into one contact
 * ({@link ContactPool}, docs/merged-view.md). {@link ContactEditor} opens one
 * into the form ({@link ContactForm}), over the field model the bridge
 * projects, and a save is staged on every replica.
 */
package org.pimalaya.contact;
