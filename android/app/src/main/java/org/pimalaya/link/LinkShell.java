package org.pimalaya.link;

import org.pimalaya.mail.MailStore;

/**
 * Where the Linked card opens the other item: on the page of its kind, which
 * the activity answers by forwarding to the screen that shows it.
 */
public interface LinkShell {
    /** Opens a message in the reader. */
    void openMessage(MailStore.StoredMessage message);

    /** Opens the merged contact holding a card of this key; false for none. */
    boolean openContact(String linkId);

    /** Opens one calendar resource on its page, running {@code gone} when it is not stored. */
    void openEvent(String collection, String linkId, Runnable gone);

    /** Opens a file from the Files list. */
    void openFile(String collection, String linkId);
}
