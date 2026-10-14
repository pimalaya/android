package org.pimalaya.ui;

/**
 * What an item page asks of the Linked card; {@code E} names the item.
 *
 * <p>The card in the page's own sections, or in a dialog of its own.
 */
public interface Linked<E> {
    /** Adds the Linked card to a page; {@code refresh} redraws the page. */
    void section(Sections sections, E self, Runnable refresh);

    /**
     * The Linked card in a dialog titled by the item; {@code closed} on close.
     */
    void dialog(E self, String title, Runnable closed);
}
