package org.pimalaya.mail;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.pimalaya.client.Clients;
import org.pimalaya.client.Flags;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.Stager;

/**
 * The mail driver that only stages: markers and deletions, told to the list.
 */
public final class MailStager extends Stager {
    /** The store, read by stagings and told of each write. */
    private final PimdirDb pimdir;

    /** A stage-only mail driver over the store. */
    public MailStager(PimdirDb pimdir, Clients clients) {
        super(pimdir, clients);
        this.pimdir = pimdir;
    }

    /** The app's context, for what the account keeps beside the store. */
    Context context() {
        return pimdir.context();
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

    /** Stages one marker added or removed; the next sync pushes the change. */
    public void stageFlag(MailStore.StoredMessage message, Flags.Marker flag, boolean add)
            throws JSONException {
        mutateFlags(
                message.collection,
                offline.handleFor(message.collection, message.id),
                mail().flagsOf(message.collection, message.id).with(flag, add));
    }

    /** Stages one message's move into the {@code target} collection. */
    public void stageMove(MailStore.StoredMessage message, String target) throws JSONException {
        mutateMove(message.collection, offline.handleFor(message.collection, message.id), target);
    }

    /** Stages one message's copy into the {@code target} collection. */
    public void stageCopy(MailStore.StoredMessage message, String target) throws JSONException {
        mutateCopy(message.collection, offline.handleFor(message.collection, message.id), target);
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
     * <p>Outgoing: its submission and sent copy are withdrawn. Outside the
     * trash it moves there, inside it goes for good; with no trash or no
     * UIDPLUS it is marked {@code \Deleted}.
     */
    public Deletion stageDelete(MailStore.StoredMessage message) throws JSONException {
        if (message.pending()) {
            mail().outbox().acknowledge(message.queued.id());
            String sent = mail().sentOf(message.accountEmail);
            if (sent != null && offline.isPendingCreate(sent, message.id)) {
                mutateRemove(sent, PimdirStorage.provisionalOf(message.id));
            }
            return Deletion.WITHDRAWN;
        }

        String handle = offline.handleFor(message.collection, message.id);
        String trash = mail().policy().trashOf(message.accountEmail);
        if (!trash.isEmpty() && !trash.equals(message.mailbox)) {
            mutateMove(message.collection, handle, mail().collectionOf(message.accountEmail, trash));
            return Deletion.MOVED;
        }
        if (!trash.isEmpty() && mail().policy().expungesOne(message.accountEmail)) {
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
