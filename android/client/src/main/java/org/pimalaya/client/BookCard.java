package org.pimalaya.client;

/**
 * A card as an update or a delete addresses it: the addressbook it is
 * filed in and the card, whose {@link Card#uri} and {@link Card#etag} are
 * sent empty when null.
 *
 * @param bookUrl the addressbook collection's URL
 * @param card the card
 */
public record BookCard(String bookUrl, Card card) {}
