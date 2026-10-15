package org.pimalaya.client;

import java.io.File;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The mail verbs of the bridge (rs/ffi/mail.rs).
 *
 * <p>Each runs on a held {@link MailSession}, but for the reads of a message
 * the store holds. Calls on a session block: run them off the main thread.
 */
public class MailClient {
    /** The account's mailboxes with their RFC 6154 roles, in one round. */
    public List<Mailbox> listMailboxes(MailSession session) {
        return Mailbox.list(
                Replies.array(
                        on(session, open -> Native.listMailboxes(open.transport(), open.handle()))));
    }

    /**
     * One page of a mailbox's listing, answering the engine's enumerate yield.
     *
     * <p>Members named by their listed summary (pimdir SYNC 4). JSON (recorded
     * exception): the engine's wire, handed straight back; a Java model of it
     * would duplicate the schema.
     */
    public JSONObject enumerateMailbox(MailSession session, String mailbox, JSONObject request) {
        String asked = request.toString();
        return Replies.object(
                on(
                        session,
                        open ->
                                Native.enumerateMailbox(
                                        open.transport(), open.handle(), mailbox, asked)));
    }

    /**
     * The messages a Graph delta named by id and markers alone, with summaries.
     *
     * <p>Items as a listing names them (JSON, recorded exception); a message
     * gone since it was listed is left out.
     */
    public JSONArray nameMessages(MailSession session, String mailbox, List<String> handles) {
        String asked = new JSONArray(handles).toString();
        JSONObject reply =
                Replies.object(
                        on(
                                session,
                                open ->
                                        Native.nameMessages(
                                                open.transport(), open.handle(), mailbox, asked)));
        JSONArray items = reply.optJSONArray("items");
        return items == null ? new JSONArray() : items;
    }

    /**
     * The floor of a mailbox's next chunk ({@link FloorReply}).
     *
     * <p>The {@code Date}s of its {@code count} newest messages dated before
     * {@code before}, null for no ceiling.
     */
    public FloorReply floor(MailSession session, String mailbox, String before, int count) {
        return FloorReply.from(
                Replies.object(
                        on(
                                session,
                                open ->
                                        Native.mailFloor(
                                                open.transport(),
                                                open.handle(),
                                                mailbox,
                                                before == null ? "" : before,
                                                count))));
    }

    /** One step of a Gmail or JMAP account's mail ({@link #stepped}). */
    public NamedStep step(
            MailSession session, StepRequest request, File dir, OfflineDriver store) {
        return NamedStep.from(Replies.object(stepped(session, request, dir, store)));
    }

    /** One step of a Microsoft Graph account's mail ({@link #stepped}). */
    public GraphStepReply graphStep(
            MailSession session, StepRequest request, File dir, OfflineDriver store) {
        return GraphStepReply.from(Replies.object(stepped(session, request, dir, store)));
    }

    /**
     * One step of an account's mail, read whole into files of {@code dir}.
     *
     * <p>The bridge asks {@code store} which listed messages it holds
     * ({@link Native#mailStep}); the reply is raw, its shape the backend's.
     */
    private static String stepped(
            MailSession session, StepRequest request, File dir, OfflineDriver store) {
        String mailbox = request.mailbox();
        String asked = request.toJson().toString();
        return on(
                session,
                open ->
                        Native.mailStep(
                                open.transport(),
                                open.handle(),
                                mailbox == null ? "" : mailbox,
                                asked,
                                dir.getAbsolutePath(),
                                store));
    }

    /**
     * Reads some messages whole into files of {@code dir}, one per message.
     *
     * <p>A message deleted since listed goes to {@code gone}, one not handed
     * over to neither. Gmail, Graph and IMAP read in batches, JMAP names
     * blobs in one {@code Email/get} then downloads each.
     */
    public MessageSources fetchSources(
            MailSession session, String mailbox, List<String> ids, File dir) {
        String asked = new JSONArray(ids).toString();
        return MessageSources.from(
                Replies.object(
                        on(
                                session,
                                open ->
                                        Native.fetchMessageSources(
                                                open.transport(),
                                                open.handle(),
                                                mailbox,
                                                asked,
                                                dir.getAbsolutePath()))));
    }

    /**
     * Writes one attachment's decoded bytes to {@code target}, answering size.
     *
     * <p>By {@link MessageBody.Attachment#part}, file to file so a large part
     * never sits on the heap. No network.
     */
    public long messagePart(File source, String part, File target) {
        return Replies.object(
                        Native.messagePart(
                                source.getAbsolutePath(), part, target.getAbsolutePath()))
                .optLong("size");
    }

    /**
     * Resolves one message's MIME tree into what a reader draws.
     *
     * <p>No network: the whole read path of a message the store holds.
     */
    public MessageBody parseMessage(byte[] source) {
        return MessageBody.from(Replies.object(Native.parseMessage(source)));
    }

    /** {@link #parseMessage(byte[])} of a file, never on the Java heap. */
    public MessageBody parseMessage(File source) {
        return MessageBody.from(Replies.object(Native.parseMessageFile(source.getAbsolutePath())));
    }

    /** What the store files of the message in {@code source}, body-free. */
    public Description describeMessage(File source) {
        return Description.from(Replies.object(Native.describeMessage(source.getAbsolutePath())));
    }

    /** Adds or removes one marker, named the IMAP way, on one message. */
    public void setFlag(
            MailSession session, MessageRef message, Flags.Marker flag, boolean add) {
        Replies.object(
                on(
                        session,
                        open ->
                                Native.setMessageFlag(
                                        open.transport(),
                                        open.handle(),
                                        message.mailbox(),
                                        message.id(),
                                        flag.wire(),
                                        add)));
    }

    /**
     * Composes one draft into the RFC 5322 message an outbox holds.
     *
     * <p>No network: queued with the radio off, only {@link #submit} needs
     * one. The bytes keep a {@code Bcc} header (RFC 5322 §3.6.3), which the
     * submission takes out.
     */
    public byte[] compose(ComposeDraft draft) {
        JSONObject reply = Replies.object(Native.composeMessage(draft.toJson().toString()));
        return Base64.getDecoder().decode(reply.optString("source"));
    }

    /**
     * Hands one stored message over; answers Gmail's own sent-copy Message-ID.
     *
     * <p>Null when the provider stamped none. The sent copy is a create staged
     * when queued, which the sync carries out.
     *
     * @throws SubmissionRefused when refused for good: parked, not requeued
     * @throws SubmissionUncertain when sent whole with no verdict: parked
     */
    public String submit(MailSession session, byte[] source) {
        String submitUrl = session.account().submitUrl;
        // NOTE: run once, never retried: sending is not idempotent, and an
        // accepted submission that lost its answer would go twice. A failure
        // leaves it queued for the outbox instead.
        String json =
                Native.submitMessage(
                        session.transport(),
                        session.handle(),
                        submitUrl == null ? "" : submitUrl,
                        source);

        JSONObject reply = Replies.read(json);
        String error = reply.optString("error");
        if (!error.isEmpty()) {
            if (reply.optBoolean("permanent")) {
                throw new SubmissionRefused(error);
            }
            throw reply.optBoolean("uncertain")
                    ? new SubmissionUncertain(error)
                    : new PimalayaException(error);
        }
        return reply.isNull("messageId") ? null : reply.optString("messageId", null);
    }

    /** Moves one message into {@code target} on the server. */
    public void relocate(MailSession session, MessageRef message, String target) {
        Replies.object(
                on(
                        session,
                        open ->
                                Native.relocateMessage(
                                        open.transport(),
                                        open.handle(),
                                        message.mailbox(),
                                        message.id(),
                                        target)));
    }

    /**
     * Copies one message into {@code target} on the server, run once.
     *
     * <p>A copy that lost its answer and ran again would file a second one.
     */
    public void copy(MailSession session, MessageRef message, String target) {
        Replies.object(
                Native.copyMessage(
                        session.transport(),
                        session.handle(),
                        message.mailbox(),
                        message.id(),
                        target));
    }

    /**
     * Deletes one message for good.
     *
     * <p>On IMAP {@code \Deleted} then {@code UID EXPUNGE} with UIDPLUS, the
     * marker alone otherwise.
     */
    public void destroy(MailSession session, MessageRef message) {
        Replies.object(
                on(
                        session,
                        open ->
                                Native.destroyMessage(
                                        open.transport(),
                                        open.handle(),
                                        message.mailbox(),
                                        message.id())));
    }

    /** Appends one message to {@code mailbox}; IMAP only, run once. */
    public void append(MailSession session, String mailbox, byte[] source, Flags flags) {
        Replies.object(
                Native.appendMessage(
                        session.transport(),
                        session.handle(),
                        mailbox,
                        source,
                        flags.toJson().toString()));
    }

    /**
     * Opens one account's mail connection, greeted and authenticated.
     *
     * <p>Per account, not mailbox: one IMAP session spans every mailbox, one
     * JMAP session resource the account. The caller closes it.
     */
    public static MailSession openMail(Account account) {
        Transport transport = new Transport();
        try {
            Opened opened =
                    Opened.from(
                            Replies.object(
                                    Native.openMailSession(
                                            transport, account.toJson().toString())));
            return new MailSession(account, transport, opened.handle(), opened.expungesOne());
        } catch (RuntimeException failure) {
            transport.close();
            throw failure;
        }
    }

    /** One verb against an open session. */
    private interface OnSession {
        String run(MailSession session);
    }

    /**
     * Runs one idempotent verb on a held session, reopening it once if dead.
     *
     * <p>Idle timeouts, NAT and network changes end connections silently: a
     * failure after a loss ({@link Transport#losses}) gets one retry on a fresh
     * session; a live server's refusal is reported at once. Never submission.
     */
    private static String on(MailSession session, OnSession verb) {
        String reply;
        boolean reopened = false;
        try {
            reply = verb.run(session);
        } catch (RuntimeException failure) {
            session.reopen();
            reopened = true;
            reply = verb.run(session);
        }
        if (!reopened && Replies.failed(reply) && session.lostSinceAnswered()) {
            session.reopen();
            reply = verb.run(session);
        }
        if (!Replies.failed(reply)) {
            session.answered();
        }
        return reply;
    }
}
