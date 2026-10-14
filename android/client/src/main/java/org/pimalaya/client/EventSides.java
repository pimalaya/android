package org.pimalaya.client;

/**
 * The three bodies of a conflicted calendar object.
 *
 * @param base the body both sides last agreed on, null when none was
 * @param local the body staged here
 * @param remote the body the source holds
 */
public record EventSides(String base, String local, String remote) {}
