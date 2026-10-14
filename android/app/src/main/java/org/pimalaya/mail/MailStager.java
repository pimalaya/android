package org.pimalaya.mail;

import org.json.JSONArray;
import org.json.JSONException;
import org.pimalaya.client.Clients;
import org.pimalaya.client.Flags;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.Stager;

/** The mail driver that only stages: markers and deletions, each write told to the list. */
public final class MailStager extends Stager {
    /** The store, for the messages a staging reads and the list a write is told to. */
    private final PimdirDb pimdir;

    public MailStager(PimdirDb pimdir, Clients clients) {
        super(pimdir, clients);
        this.pimdir = pimdir;
    }

    MailStore mail() {
        if (mail == null) {
            mail = new MailStore(pimdir.context(), pimdir);
        }
        return mail;
    }

    private MailStore mail;

    @Override
    protected void applied(JSONArray effects) {
        applied(pimdir, effects);
    }

    /** Tells a listening list that a write landed, page by page. */
    static void applied(PimdirDb pimdir, JSONArray effects) {
        Runnable listener = MailState.of(pimdir.context()).onWrite;
        if (listener != null && effects.length() > 0) {
            listener.run();
        }
    }

    /**
     * Stages one marker on one stored message, added or removed, the others
     * it carries kept; the next sync pushes the difference.
     */
    public void stageFlag(MailStore.StoredMessage message, Flags.Marker flag, boolean add)
            throws JSONException {
        mutateFlags(
                message.collection,
                offline.handleFor(message.collection, message.id),
                mail().flagsOf(message.collection, message.id).with(flag, add));
    }

    /** What deleting one message staged, which its row and toast say. */
    public enum Deletion {
        /** A submission withdrawn, with the sent copy staged beside it. */
        WITHDRAWN,
        /** Moved into the account's trash. */
        MOVED,
        /** Marked {@code \Deleted} in place, the row staying. */
        MARKED,
        /** Removed from the trash, a delete for good. */
        ERASED
    }

    /**
     * Stages one message's deletion, as its account allows.
     *
     * <p>A message waiting to go out was never sent, so withdrawing its
     * submission is the delete, the sent copy staged beside it going too.
     * Outside the trash it moves there; in the trash it is removed, which
     * the sync pushes as a delete for good. Where the account records no
     * trash, or its server erases no single message (no UIDPLUS, whose
     * plain EXPUNGE takes every marked message), it is marked
     * {@code \Deleted} and stays.
     */
    public Deletion stageDelete(MailStore.StoredMessage message) throws JSONException {
        if (message.pending) {
            mail().acknowledge(message.queued);
            String sent = mail().sentOf(message.accountEmail);
            if (sent != null && offline.isPendingCreate(sent, message.id)) {
                mutateRemove(sent, PimdirStorage.provisionalOf(message.id));
            }
            return Deletion.WITHDRAWN;
        }

        String handle = offline.handleFor(message.collection, message.id);
        String trash = mail().trashOf(message.accountEmail);
        if (!trash.isEmpty() && !trash.equals(message.mailbox)) {
            mutateMove(message.collection, handle, mail().collectionOf(message.accountEmail, trash));
            return Deletion.MOVED;
        }
        if (!trash.isEmpty() && mail().expungesOne(message.accountEmail)) {
            mutateRemove(message.collection, handle);
            return Deletion.ERASED;
        }
        mutateFlags(
                message.collection,
                handle,
                mail().flagsOf(message.collection, message.id).with(Flags.Marker.DELETED, true));
        return Deletion.MARKED;
    }
}
