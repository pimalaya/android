package org.pimalaya.client;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The mail verbs of the bridge (rs/ffi/mail.rs), each run on a held
 * {@link MailSession} but for the reads of a message the store already
 * holds. Every call on a session blocks, so callers must run it off the
 * main thread.
 */
public class MailClient {
    /**
     * The account's mailboxes with the roles their RFC 6154 attributes
     * mark, in one round.
     */
    public List<Mailbox> listMailboxes(MailSession session) {
        JSONArray reply =
                Replies.array(on(session, open -> Native.listMailboxes(open.transport(), open.handle())));

        List<Mailbox> mailboxes = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            JSONObject mailbox = Json.object(reply, index);
            mailboxes.add(new Mailbox(Json.string(mailbox, "name"), mailbox.optString("role")));
        }
        return mailboxes;
    }

    /**
     * One page of a mailbox's listing, the engine's {@code enumerate}
     * yield answered: the reply as the engine reads it, every member
     * named by the summary the listing read (pimdir SYNC section 4).
     *
     * <p>Passed through rather than parsed into a model of its own: the
     * shape is the engine's wire, the driver hands it straight back, and
     * a Java copy of it would be the duplicated schema the boundary
     * notes warn about.
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
     * The messages a listing named by id and markers alone (a Graph delta),
     * each read with its summary: the items as a listing names them, a
     * message gone since it was listed left out.
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
     * The floor of a mailbox's next chunk (a number of messages, never a
     * span of time): the oldest {@code Date} among its {@code count} newest
     * messages dated before {@code before}, null for no ceiling. Null when
     * fewer than {@code count} dated messages lie below the ceiling: the
     * mailbox is whole below it.
     */
    public String mailFloor(MailSession session, String mailbox, String before, int count) {
        JSONObject reply = floorReply(session, mailbox, before, count);
        return reply.isNull("floor") ? null : reply.optString("floor", null);
    }

    /**
     * The {@code Date}s of a mailbox's {@code count} newest messages by
     * {@code Date} dated before {@code before} (null for no ceiling),
     * newest first: the {@link #mailFloor} read, its floor the last of them
     * when there are {@code count}, fewer when the mailbox is whole below
     * the ceiling.
     */
    public List<String> mailNewest(MailSession session, String mailbox, String before, int count) {
        JSONArray dates = floorReply(session, mailbox, before, count).optJSONArray("dates");
        List<String> newest = new ArrayList<>();
        for (int index = 0; dates != null && index < dates.length(); index++) {
            newest.add(dates.optString(index));
        }
        return newest;
    }

    private JSONObject floorReply(MailSession session, String mailbox, String before, int count) {
        return Replies.object(
                on(
                        session,
                        open ->
                                Native.mailFloor(
                                        open.transport(),
                                        open.handle(),
                                        mailbox,
                                        before == null ? "" : before,
                                        count)));
    }

    /**
     * One step of an account's mail, read whole into files of {@code dir};
     * {@code mailbox} the one mailbox a view filtered to it steps, null for
     * the account. {@code request} is {@code {count, frontier, bound,
     * anchors, mailboxes}}, each backend reading what it needs, and asking
     * {@code store} which of each page it lists the store holds ({@link
     * Native#mailStep}); the reply as the bridge wrote it.
     */
    public JSONObject mailStep(
            MailSession session,
            String mailbox,
            JSONObject request,
            File dir,
            OfflineDriver store) {
        String asked = request.toString();
        return Replies.object(
                on(
                        session,
                        open ->
                                Native.mailStep(
                                        open.transport(),
                                        open.handle(),
                                        mailbox == null ? "" : mailbox,
                                        asked,
                                        dir.getAbsolutePath(),
                                        store)));
    }

    /**
     * Reads some messages of a mailbox whole into files of {@code dir}, one
     * per message, as the bytes the server holds: {@code {items, gone}},
     * each item {@code {id, path, size}}, a message gone (deleted since it
     * was listed) left out of the items and named by its id in
     * {@code gone}, one not handed over left out of both. Gmail, Graph and
     * IMAP read them in batches, JMAP names their blobs in one
     * {@code Email/get} and downloads them one after another.
     */
    public JSONObject fetchMessageSources(
            MailSession session, String mailbox, List<String> ids, File dir) {
        String asked = new JSONArray(ids).toString();
        return Replies.object(
                on(
                        session,
                        open ->
                                Native.fetchMessageSources(
                                        open.transport(),
                                        open.handle(),
                                        mailbox,
                                        asked,
                                        dir.getAbsolutePath())));
    }

    /**
     * Writes the decoded bytes of one attachment of the message stored at
     * {@code source}, by the section {@link MessageBody.Attachment#part}
     * names, to {@code target}, answering its size. Files on both sides, so
     * a large part never sits on the heap twice. No network.
     */
    public long messagePart(File source, String part, File target) {
        return Replies.object(
                        Native.messagePart(
                                source.getAbsolutePath(), part, target.getAbsolutePath()))
                .optLong("size");
    }

    /**
     * Resolves one message's MIME tree into what a reader draws: the
     * headers, the one body it shows, and what it carries beside it.
     *
     * <p>No network, whatever the message: this is the whole read path
     * of a message the store already holds.
     */
    public MessageBody parseMessage(byte[] source) {
        return message(Replies.object(Native.parseMessage(source)));
    }

    /**
     * {@link #parseMessage(byte[])} of the message stored in {@code source},
     * read by the bridge: the message never crosses onto the Java heap.
     */
    public MessageBody parseMessage(File source) {
        return message(Replies.object(Native.parseMessageFile(source.getAbsolutePath())));
    }

    /**
     * What the store files of one message read into {@code source}, read
     * once by the bridge: {@code {hash, size, summary, attachments}}, the
     * content hash the object store names it by ({@code sha256-128}), the
     * summary io-pimdir derives from it (null where it yields none) and its
     * attachments as {@link #attachmentsOf} reads them (null when the
     * parser cannot read it). No body crosses the boundary.
     */
    public JSONObject describeMessage(File source) {
        return Replies.object(Native.describeMessage(source.getAbsolutePath()));
    }

    /** A {@code parseMessage} reply as what a reader draws. */
    private static MessageBody message(JSONObject reply) {
        // NOTE: optString and not the null-returning helper beside it:
        // every one of these fields is always serialised, and a reader
        // must never be handed a null to render.
        return new MessageBody(
                reply.optString("subject"),
                reply.optString("from"),
                reply.optString("fromAddress"),
                reply.optString("to"),
                reply.optString("cc"),
                reply.optString("date"),
                reply.optString("kind"),
                reply.optString("body"),
                attachmentsOf(reply.optJSONArray("attachments")),
                reply.optBoolean("attachmentMark"));
    }

    /** The attachments a parse or a description lists, each {@code {name, mime, size, part}}. */
    public static List<MessageBody.Attachment> attachmentsOf(JSONArray listed) {
        List<MessageBody.Attachment> attachments =
                new ArrayList<>(listed == null ? 0 : listed.length());
        for (int index = 0; listed != null && index < listed.length(); index++) {
            JSONObject attachment = Json.object(listed, index);
            attachments.add(
                    new MessageBody.Attachment(
                            attachment.optString("name"),
                            attachment.optString("mime"),
                            attachment.optLong("size"),
                            attachment.optString("part")));
        }
        return attachments;
    }

    /**
     * Adds or removes one marker on one message, named the IMAP way
     * whichever backend answers.
     */
    public void setMessageFlag(
            MailSession session, String mailbox, String id, String flag, boolean add) {
        Replies.object(
                on(
                        session,
                        open ->
                                Native.setMessageFlag(
                                        open.transport(),
                                        open.handle(),
                                        mailbox,
                                        id,
                                        flag,
                                        add)));
    }

    /**
     * Composes one draft into the RFC 5322 message an outbox holds.
     *
     * <p>{@code draft} is a JSON object of {@code from}, {@code fromName},
     * {@code to}, {@code cc}, {@code bcc}, {@code subject}, {@code body},
     * {@code date} and {@code messageId}: the composer's fields plus the
     * two stamps, which are the caller's so the bridge stays a pure
     * function of what it is handed.
     *
     * <p>No network, which is the point: a message is written and queued
     * with the radio off, and {@link #submitMessage} is what needs one.
     * The bytes carry a {@code Bcc} header, as RFC 5322 §3.6.3 provides
     * for a message prepared for sending; the submission takes it out.
     */
    public byte[] composeMessage(String draft) {
        JSONObject reply = Replies.object(Native.composeMessage(draft));
        return Base64.getDecoder().decode(reply.optString("source"));
    }

    /**
     * Hands one stored message over, answering the {@code Message-ID} the
     * provider filed its sent copy under when it stamped one of its own
     * (Gmail), else null.
     *
     * <p>The submission alone: the copy the sender keeps is a create staged
     * in the sent mailbox when the message was queued, and the sync carries
     * it out.
     *
     * @throws SubmissionRefused when the server refused the message for
     *     good, which is what parks it rather than queueing it again.
     * @throws SubmissionUncertain when the whole message went over and no
     *     verdict came back, which parks it for the user to confirm.
     */
    public String submitMessage(MailSession session, byte[] source) {
        String submitUrl = session.account().submitUrl;
        // NOTE: run once, never retried, unlike every other verb on a
        // session. Sending is the one thing here that is not idempotent:
        // a submission that was accepted and then lost its answer looks
        // exactly like one that never went, and running it again would
        // send the message twice. A failure leaves it queued instead,
        // which is what the outbox is for.
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

    /** Moves one message from {@code mailbox} into {@code target}. */
    public void relocateMessage(MailSession session, String mailbox, String id, String target) {
        Replies.object(
                on(
                        session,
                        open ->
                                Native.relocateMessage(
                                        open.transport(), open.handle(), mailbox, id, target)));
    }

    /**
     * Copies one message from {@code mailbox} into {@code target} on the
     * server. Run once: a copy that lost its answer and ran again would
     * file a second one.
     */
    public void copyMessage(MailSession session, String mailbox, String id, String target) {
        Replies.object(
                Native.copyMessage(
                        session.transport(), session.handle(), mailbox, id, target));
    }

    /**
     * Deletes one message for good: on IMAP {@code \Deleted} then
     * {@code UID EXPUNGE} with UIDPLUS, the marker alone otherwise.
     */
    public void destroyMessage(MailSession session, String mailbox, String id) {
        Replies.object(
                on(
                        session,
                        open -> Native.destroyMessage(open.transport(), open.handle(), mailbox, id)));
    }

    /**
     * Appends one message to {@code mailbox} with {@code flags}. Run once,
     * as a copy is. IMAP only.
     */
    public void appendMessage(MailSession session, String mailbox, byte[] source, JSONArray flags) {
        Replies.object(
                Native.appendMessage(
                        session.transport(), session.handle(), mailbox, source, flags.toString()));
    }

    /**
     * Opens one account's mail connection, greeted and authenticated, for
     * a caller that will run several verbs on it and close it.
     *
     * <p>The unit is the account and not the mailbox, which is what both
     * backends are shaped for: IMAP is one session over every mailbox and
     * JMAP one session resource over the whole account.
     */
    public static MailSession openMail(Account account) {
        Transport transport = new Transport();
        try {
            JSONObject reply =
                    Replies.object(
                            Native.openMailSession(
                                    transport,
                                    account.baseUrl,
                                    account.login,
                                    account.password));
            return new MailSession(
                    account,
                    transport,
                    MailSession.handleOf(reply),
                    reply.optBoolean("expungesOne", true));
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
     * Runs one idempotent verb on a held session, reopening it once if it
     * has died.
     *
     * <p>A held connection is a hint and never a promise: a server's idle
     * timeout (Graph's is about two minutes), a rebound NAT and a walk from
     * wifi to cellular all end one under the app, and none of them announce
     * it. The bridge answers a failure as an error reply rather than
     * throwing, so a failure is read as the connection's when the transport
     * lost one since a verb last answered ({@link Transport#losses}), in this
     * call or in one run once outside it (a copy, an append): the verb is
     * then given one honest attempt on a fresh session, an IMAP one greeted
     * and authenticated again, before its failure is reported as its own. A
     * refusal from a live server is reported at once.
     *
     * <p>Idempotent is the condition and not a description: a verb that
     * lost its connection may have been carried out before it did.
     * Re-reading a message, re-storing a marker and re-moving a message that
     * has already moved all cost a round trip and change nothing. Submitting
     * a message does not, which is why it does not come through here.
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
