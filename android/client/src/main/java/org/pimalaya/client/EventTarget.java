package org.pimalaya.client;

/**
 * The calendar object a write addresses, its revision and the account.
 *
 * @param calendarUrl the calendar collection's URL
 * @param id the object's resource name
 * @param etag the ETag guarding the write; null for a create or none read
 * @param address the account's address: Google and JMAP announce a write to
 *     attendees only for a meeting it organizes; null for none
 */
public record EventTarget(String calendarUrl, String id, String etag, String address) {}
