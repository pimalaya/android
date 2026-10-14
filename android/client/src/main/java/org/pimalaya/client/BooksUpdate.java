package org.pimalaya.client;

import java.util.List;

/**
 * A card's book memberships to add and remove, on an account-level backend.
 *
 * @param cardId the card's id
 * @param add the book ids the card joins
 * @param remove the book ids the card leaves
 */
public record BooksUpdate(String cardId, List<String> add, List<String> remove) {}
