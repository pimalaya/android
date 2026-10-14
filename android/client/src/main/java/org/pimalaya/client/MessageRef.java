package org.pimalaya.client;

/**
 * One message a write addresses on the server.
 *
 * @param mailbox the mailbox holding it, by name
 * @param id its id on its backend (an IMAP UID, a provider id)
 */
public record MessageRef(String mailbox, String id) {}
