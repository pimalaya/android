package org.pimalaya.mail;

import org.pimalaya.R;
import org.pimalaya.client.Description;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.MessageBody;
import org.pimalaya.file.FileStore;
import org.pimalaya.pimdir.PimdirAccount;

import java.io.File;
import java.util.List;
import java.util.function.Supplier;

/**
 * Reads one message for the reader, from the store when it holds it and from
 * the server otherwise, filing what it fetched on the way through.
 *
 * <p>The store first, always. A message is immutable once sent, so the copy
 * filed by the last open is the message, and asking the server for it again
 * would be a round trip whose only possible answer is what is already on
 * disk. It is also what makes the reader work with no network at all.
 */
public final class MailReader {
    /** What one read came to. */
    public static final class Read {
        /** The message as the reader draws it. */
        public MessageBody body;

        /** Whether it was fetched from the server, its row no longer dimmed. */
        boolean fetched;
    }

    private final MailStore mail;
    private final FileStore files;
    private final MailClient client;

    /** A driver that only stages: what names a pending move. */
    private final MailStager stager;

    public MailReader(MailStore mail, FileStore files, MailClient client, MailStager stager) {
        this.mail = mail;
        this.files = files;
        this.client = client;
        this.stager = stager;
    }

    /**
     * The message as the reader draws it, its body null for one waiting to go
     * out whose body is gone. {@code open} opens a session on the message's
     * account, for the one read that needs the network; it throws when there
     * is none.
     */
    public Read read(MailStore.StoredMessage message, Supplier<MailSession> open) {
        Read read = new Read();
        // NOTE: a waiting message is nothing but the body its queue row
        // carries; any other is read from its stored file, never whole on
        // this side.
        byte[] waiting = message.pending() ? mail.source(message) : null;
        File stored = message.pending() ? null : mail.sourceFile(message);
        if (waiting == null && message.pending()) {
            // NOTE: never fetched. A message waiting to go out is nothing but
            // the body its queue row carries, so there is no server that has
            // it and no mailbox to ask: a missing body here is a lost message,
            // not a message yet to be read.
            return read;
        }
        // NOTE: a message whose reading killed the app before is not read
        // again (MailPoison).
        String key = MailOwed.key(message.collection, message.id);
        if (!MailPoison.begin(stager.context(), key)) {
            throw new IllegalStateException(
                    stager.context().getString(R.string.message_unreadable));
        }
        try {
            if (waiting == null && stored == null) {
                stored = fetch(message, open);
                read.fetched = true;
            }
            read.body = waiting != null ? client.parseMessage(waiting) : client.parseMessage(stored);
        } finally {
            MailPoison.end(stager.context(), key);
        }
        if (!message.pending()) {
            synchronized (stager.locks.store) {
                files.recordAttachments(
                        mail.accountIdOf(message.accountEmail), message.id, read.body.attachments());
            }
        }
        return read;
    }

    /**
     * Fetches one message the store does not hold, files it and restates its
     * summary, answering the file it is stored in.
     *
     * <p>Read as a body step reads one ({@link MailClient#fetchSources}):
     * streamed by the bridge into a file of its own, described there ({@link
     * MailClient#describeMessage}) and moved into the blob store, so a
     * message of any size opens with no copy of it on the Java heap and none
     * across the boundary.
     */
    File fetch(MailStore.StoredMessage message, Supplier<MailSession> open) {
        String[] address = addressOf(message);
        File dir = MailEngine.scratch(stager.context());
        try {
            File file;
            // NOTE: a connection of its own, opened and closed around this
            // one read. A reader opening a message is not a pass, and holding
            // one open for the time someone spends reading would be holding
            // it for no work at all.
            try (MailSession session = open.get()) {
                file =
                        MailEngine.files(
                                        client.fetchSources(
                                                session, address[0], List.of(address[1]), dir))
                                .get(address[1]);
            }
            if (file == null || !file.isFile()) {
                throw new IllegalStateException(
                        stager.context().getString(R.string.message_failed));
            }
            Description described = client.describeMessage(file);
            // NOTE: the listing read the summary without the body; the body
            // just stored restates it. One writer: a fill or body step may be
            // writing beside this open.
            synchronized (stager.locks.store) {
                mail.saveSource(message.collection, message.id, described.hash(), file);
                mail.restateFromBody(message.collection, message.id, described.summary());
            }
            File stored = mail.sourceFile(message);
            if (stored == null) {
                throw new IllegalStateException(
                        stager.context().getString(R.string.message_failed));
            }
            return stored;
        } finally {
            MailEngine.discard(dir);
        }
    }

    /**
     * Where the server holds a message, as {@code [mailbox, id]}: where it is
     * listed, or for the target of a move not carried out yet, the mailbox it
     * is moving from.
     */
    private String[] addressOf(MailStore.StoredMessage message) {
        String[] source = null;
        synchronized (stager.locks.store) {
            if (stager.offline.isPendingCreate(message.collection, message.id)) {
                source = stager.offline.moveSourceOf(message.collection, message.id);
            }
        }
        if (source == null) {
            return new String[] {message.mailbox, message.id};
        }
        return new String[] {
            PimdirAccount.nameOf(mail.accountIdOf(message.accountEmail), source[0]), source[1]
        };
    }
}
