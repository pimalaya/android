package org.pimalaya.contact;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.Card;

/**
 * The editor's working state, one object per open contact.
 *
 * <p>Built when a card, group or conflict opens, replaced by a blank when the
 * editor leaves; the form, merge and conflict flows read this one session.
 */
public final class EditSession {
    /** The book of the open card, or of the one being composed. */
    Addressbook book;

    String accountEmail;
    /** The card open in the editor; null when composing a new one. */
    public Card card;

    /** The vCard the editor patches: the card's, or a fresh template. */
    String vcard;

    /** The merged group's replicas behind the editor; saving fans out. */
    List<Entry> replicas = new ArrayList<>();

    /** The replica the Merge action keeps; every other card is removed. */
    Entry mergeSurvivor;

    /** The addressbooks dialog's desired state, applied on save. */
    Map<String, Boolean> pendingBookState;

    /** The editor's bar title (the shared bar is retitled per screen). */
    public CharSequence title = "";

    /**
     * Whether the editor resolves a both-sides-edited conflict.
     *
     * <p>The picks apply onto the captured three-way merge ({@code vcard}) and
     * stage onto the one conflicted replica.
     */
    public boolean resolvingConflict;
}
