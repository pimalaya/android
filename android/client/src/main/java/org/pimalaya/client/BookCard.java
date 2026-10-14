package org.pimalaya.client;

/**
 * A card as an update or a delete addresses it: its addressbook and itself.
 *
 * <p>A null {@link Card#uri} or {@link Card#etag} is sent empty.
 *
 * @param bookUrl the addressbook collection's URL
 * @param card the card
 */
public record BookCard(String bookUrl, Card card) {}
