package org.pimalaya.client;

/**
 * The card a create files.
 *
 * @param id on CardDAV the whole resource name asked for, which a server
 *     may replace with its own; the other backends name the card themselves
 * @param vcard the vCard text
 */
public record NewCard(String id, String vcard) {}
