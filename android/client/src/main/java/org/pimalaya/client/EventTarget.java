package org.pimalaya.client;

/**
 * The calendar object a write addresses and its revision.
 *
 * @param calendarUrl the calendar collection's URL
 * @param id the object's resource name
 * @param etag the ETag guarding the write; null for a create or none read
 */
public record EventTarget(String calendarUrl, String id, String etag) {}
