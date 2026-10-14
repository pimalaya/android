package org.pimalaya.ui;

/**
 * What an item page asks of the Linked card: the card in the page's own
 * sections, or in a dialog of its own; {@code E} names the item.
 */
public interface Linked<E> {
    /** Adds the Linked card to a page; {@code refresh} redraws the page. */
    void section(Sections sections, E self, Runnable refresh);

    /** The Linked card in a dialog titled by the item, running {@code closed} once it goes. */
    void dialog(E self, String title, Runnable closed);
}
