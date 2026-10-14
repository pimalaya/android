/**
 * The phone mirror's plumbing: the Android accounts the phone's contacts and
 * calendars hold the store's items under, and the queue keeping them current.
 *
 * <p>{@link Accounts} keeps one Android account per mirrored address book and
 * one per address whose calendars the phone shows. {@link PhoneMirror} names
 * the two mirrors and the permissions each asks for.
 *
 * <p>{@link PhoneQueue} runs a collection's phone pass a second after a store
 * write touched it, on a thread of its own. The passes are the domains'
 * ({@code ContactPhonePass}, {@code CalendarPhonePass}), handed to it by the
 * composition root, so this package sits below them.
 */
package org.pimalaya.phone;
