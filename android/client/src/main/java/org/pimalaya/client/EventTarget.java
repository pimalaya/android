package org.pimalaya.client;

/**
 * The calendar object a write addresses: where it is filed, the revision
 * it was read at, and the account's address.
 *
 * @param calendarUrl the calendar collection's URL
 * @param id the object's resource name
 * @param etag the ETag it was read with, guarding the write; null for none
 *     (a create, or an object read without one)
 * @param address the account's address: a backend that announces a write
 *     to the attendees only when asked (Google, JMAP) asks for a meeting it
 *     names the organizer of; null for none
 */
public record EventTarget(String calendarUrl, String id, String etag, String address) {}
