package org.pimalaya.mail;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.pimalaya.R;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Flags;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.MessageBody;
import org.pimalaya.file.FileActions;
import org.pimalaya.file.FileStore;
import org.pimalaya.file.FilesList;
import org.pimalaya.file.OpenedCopies;
import org.pimalaya.pimdir.ItemLinks;
import org.pimalaya.pimdir.MailDate;
import org.pimalaya.ui.Avatar;
import org.pimalaya.ui.Dates;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.Linked;
import org.pimalaya.ui.Panels;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The reader for one message: the header card, then the message itself.
 *
 * <p>The card is drawn from the list row the reader tapped, so the
 * screen is never blank: the subject, the sender and the date are
 * already in the store, and only the body and the recipients have to be
 * read. They are read from the store too wherever it holds the message,
 * which it does from the first time the message was opened; a message it
 * does not hold is fetched once and filed as it renders.
 *
 * <p>An HTML body renders in a web view with scripting off, network
 * loads blocked and images not loaded at all. That is the whole point
 * of showing HTML rather than the text alternative: a remote image is a
 * read receipt the sender did not ask permission for, and a script in a
 * message has no business running.
 */
public final class MessageView implements FilesList.Origins {
    /** How wide a badge row may run: the reader's own inner width. */
    private static final int CARD_INSET = 32;

    private final Host host;

    private final MailParts parts;

    /** The foreground sync, which opens a session for a fetch. */
    private final MailSync sync;

    /** What an attachment offers: open, share, save to a folder. */
    private final FileActions fileActions;

    /** The Linked card the reader's badge opens. */
    private final Linked<ItemLinks.Endpoint> linked;

    /** The list read again after a write; set by the list once built. */
    MailList mailList;

    /** The composer a reply and a forward open; set by the composer once built. */
    MessageCompose compose;

    /** The row the reader opened, and what the header is drawn from. */
    private MailStore.StoredMessage current;

    /**
     * What the open message's two toggles stand at.
     *
     * <p>Held beside the row rather than read back off it: the row is the
     * store's answer as of the listing, and the reader moves both of them
     * while it is open (opening marks read, the toggles are toggles). The
     * store is written in the same pass, so the two agree; this is what
     * the buttons draw from without a re-read per tap.
     */
    private boolean seen;

    private boolean flagged;

    /** The open message as read, what a reply and a forward quote; null until it loads. */
    private MessageBody loaded;

    public MessageView(
            Host host,
            MailParts parts,
            MailSync sync,
            FileActions fileActions,
            Linked<ItemLinks.Endpoint> linked) {
        this.host = host;
        this.parts = parts;
        this.sync = sync;
        this.fileActions = fileActions;
        this.linked = linked;
    }

    /** Opens the reader on one row, then fetches what it does not hold. */
    public void open(MailStore.StoredMessage message) {
        current = message;
        loaded = null;
        seen = message.seen;
        flagged = message.flagged;

        header(message);
        badges(new ArrayList<>());
        host.findViewById(R.id.message_view_linked).setVisibility(View.GONE);
        actions();
        loading();
        host.show(Panels.MESSAGE);

        load(message);
    }

    /** The header the store already knows, drawn before anything loads. */
    private void header(MailStore.StoredMessage message) {
        TextView avatar = host.findViewById(R.id.message_view_avatar);
        avatar.setText(Avatar.letter(message.fromAddress));
        avatar.setBackground(Avatar.circle(host, message.fromAddress));

        ((TextView) host.findViewById(R.id.message_view_subject))
                .setText(
                        message.subject.isEmpty()
                                ? host.getString(R.string.message_no_subject)
                                : message.subject);
        ((TextView) host.findViewById(R.id.message_view_from)).setText(sender(message));
        ((TextView) host.findViewById(R.id.message_view_mailbox))
                .setText(message.mailboxLabel + " · " + message.accountEmail);
        // NOTE: the recipients come with the body, and a line of the
        // previous message's must not linger until then.
        host.findViewById(R.id.message_view_recipients).setVisibility(View.GONE);
        date(message.stamp);
    }

    /** The sender as the header names them: the name over the address. */
    private String sender(MailStore.StoredMessage message) {
        if (message.fromName.isEmpty()) {
            return message.fromAddress;
        }
        return message.fromName + " <" + message.fromAddress + ">";
    }

    /** The exact date, with how long ago it was beside it. */
    private void date(long stamp) {
        TextView date = host.findViewById(R.id.message_view_date);
        if (stamp <= 0) {
            date.setText("");
            return;
        }
        date.setText(Dates.full(host, stamp) + " (" + Dates.relative(host, stamp) + ")");
    }

    /**
     * Renders the message, from the store when it holds it and from the
     * server otherwise, filing what it fetched on the way through.
     *
     * <p>The store first, always. A message is immutable once sent, so the
     * copy filed by the last open is the message, and asking the server for
     * it again would be a round trip whose only possible answer is what is
     * already on disk. It is also what makes the reader work with no network
     * at all.
     */
    private void load(MailStore.StoredMessage message) {
        AccountEntry account = accountOf(message.accountEmail);
        if (account == null) {
            state(host.getString(R.string.message_no_account));
            return;
        }

        // NOTE: off the io executor, which a step of the background fill or
        // of the body download can hold for seconds: someone is waiting.
        host.interactive.execute(
                () -> {
                    MessageBody loaded = null;
                    boolean fetched = false;
                    Exception failure = null;
                    try {
                        MailReader.Read read =
                                reader(message).read(message, () -> openOnline(message, account));
                        if (read.body == null) {
                            throw new IllegalStateException(
                                    host.getString(R.string.message_failed));
                        }
                        loaded = read.body;
                        fetched = read.fetched;
                    } catch (Exception error) {
                        Log.w("pimalaya", "message fetch failed: " + message.id, error);
                        failure = error;
                    }
                    // NOTE: read whether or not the body came: a message
                    // whose body was released keeps its attachments listed.
                    List<FileStore.StoredFile> files =
                            message.pending
                                    ? new ArrayList<>()
                                    : parts.files().attachments(
                                            parts.mail().accountIdOf(message.accountEmail),
                                            message.id);
                    ItemLinks.Endpoint self = MailStore.endpointOf(message);
                    int links = self == null ? -1 : parts.links().links(self).size();

                    MessageBody outcome = loaded;
                    boolean stored = fetched;
                    Exception error = failure;
                    host.postAlive(
                            () -> {
                                // NOTE: its row is on the phone now, no
                                // longer dimmed.
                                if (stored) {
                                    mailList.reload();
                                }
                                // NOTE: the reader may have left while the
                                // fetch ran, and a second message may
                                // already be on screen.
                                if (current != message) {
                                    return;
                                }
                                badges(files);
                                linked(message, links);
                                if (outcome == null) {
                                    state(host.message(error, R.string.message_failed));
                                    return;
                                }
                                render(outcome);
                                // NOTE: after the body, not before it. The
                                // fetch asks for BODY.PEEK[], so a read that
                                // failed leaves the message unread, which is
                                // the honest answer: nothing was read.
                                if (!seen) {
                                    write(Flags.Marker.SEEN, true);
                                }
                            });
                });
    }

    /**
     * The reader of one message's account ({@link MailReader}), on a mail
     * driver that only stages: no account, so it services the storage yields
     * a mutation makes and can reach no server.
     *
     * <p>Which is what every action in the reader and the composer needs.
     * Staging is a disk write, and the push it derives is the next sync's.
     */
    private MailReader reader(MailStore.StoredMessage message) {
        return new MailReader(
                parts.mail(),
                parts.files(),
                parts.clients().mail(),
                new MailEngine(
                        parts.pimdir(),
                        parts.clients(),
                        null,
                        sync.accountIdOf(message.accountEmail)));
    }

    /**
     * A session on the message's account for the reader to fetch it on, when
     * there is a network: else it is not on the phone, and within its window
     * the body step brings it once back online, else an open does.
     */
    private MailSession openOnline(MailStore.StoredMessage message, AccountEntry account) {
        if (!sync.online()) {
            boolean taken =
                    MailBodies.takes(
                            message.sortKey,
                            parts.mail().mailboxWindow(message.collection),
                            parts.mail().roles().get(message.collection),
                            MailOffline.whole(host, message.collection));
            throw new IllegalStateException(
                    host.getString(
                            taken
                                    ? R.string.message_not_on_phone
                                    : R.string.message_not_on_phone_later));
        }
        return sync.openMail(account);
    }

    /**
     * The badge opening the message's Linked card, saying how many links it
     * has; hidden for a message that cannot be linked ({@code count} -1).
     */
    private void linked(MailStore.StoredMessage message, int count) {
        android.widget.TextView badge = host.findViewById(R.id.message_view_linked);
        badge.setVisibility(count < 0 ? View.GONE : View.VISIBLE);
        badge.setText(
                count == 0
                        ? host.getString(R.string.linked_link_to)
                        : host.getResources()
                                .getQuantityString(R.plurals.linked_count, count, count));
        badge.setOnClickListener(
                view ->
                        linked.dialog(
                                MailStore.endpointOf(message),
                                message.subject,
                                () ->
                                        host.io.execute(
                                                () -> {
                                                    int links =
                                                            parts.links()
                                                                    .links(
                                                                            MailStore.endpointOf(
                                                                                    message))
                                                                    .size();
                                                    host.postAlive(
                                                            () -> {
                                                                if (current == message) {
                                                                    linked(message, links);
                                                                }
                                                            });
                                                })));
    }

    /** What a tap on an attachment offers: open it, share it, or save it to a folder. */
    private void choose(MailStore.StoredMessage message, FileStore.StoredFile attachment) {
        FileActions.Source bytes = () -> fileOf(message, attachment);
        CharSequence[] actions = {
            host.getString(R.string.attachment_open),
            host.getString(R.string.files_share),
            host.getString(R.string.attachment_save)
        };
        new AlertDialog.Builder(host)
                .setTitle(fileActions.label(attachment))
                .setItems(
                        actions,
                        (dialog, which) -> {
                            if (which == 0) {
                                fileActions.open(attachment, bytes);
                            } else if (which == 1) {
                                fileActions.share(attachment, bytes);
                            } else {
                                fileActions.saveToFolder(attachment, bytes);
                            }
                        })
                .show();
    }

    /**
     * The file one attachment is read from: a saved copy's blob when one was
     * saved, else the part the bridge writes out of the message's blob, the
     * message fetched first when the store does not hold it.
     *
     * <p>NOTE: fetching the part alone (IMAP {@code BODY.PEEK[<section>]},
     * Gmail {@code attachments.get}, Graph {@code /attachments/{id}}, a JMAP
     * blob) would spare the rest of the message; the whole message is what
     * every backend reads today.
     */
    public java.io.File fileOf(MailStore.StoredMessage message, FileStore.StoredFile attachment) {
        java.io.File saved = parts.files().saved(attachment);
        if (saved != null) {
            return saved;
        }
        java.io.File source = parts.mail().sourceFile(message);
        if (source == null) {
            AccountEntry account = accountOf(message.accountEmail);
            if (account == null) {
                throw new IllegalStateException(host.getString(R.string.message_no_account));
            }
            if (!sync.online()) {
                throw new IllegalStateException(
                        host.getString(R.string.message_not_on_phone_later));
            }
            reader(message).fetch(message, () -> sync.openMail(account));
            host.postAlive(mailList::reload);
            source = parts.mail().sourceFile(message);
            if (source == null) {
                throw new IllegalStateException(host.getString(R.string.attachment_failed));
            }
        }
        java.io.File part = OpenedCopies.partFile(host, attachment.seq);
        parts.clients().mail().messagePart(source, attachment.part, part);
        return part;
    }

    /**
     * The file a stand-in is read from: the part its message holds written
     * out ({@link #fileOf(MailStore.StoredMessage, FileStore.StoredFile)}),
     * refused when its message is gone.
     */
    @Override
    public java.io.File fileOf(FileStore.Origin origin, FileStore.StoredFile file) {
        MailStore.StoredMessage message =
                origin == null
                        ? null
                        : parts.mail().message(origin.collection, origin.linkId);
        if (message == null || file.part == null) {
            throw new IllegalStateException(host.getString(R.string.files_no_message));
        }
        return fileOf(message, file);
    }

    /** Opens the message a file came from in the reader. */
    @Override
    public void openOrigin(FileStore.Origin origin) {
        host.io.execute(
                () -> {
                    MailStore.StoredMessage message =
                            parts.mail().message(origin.collection, origin.linkId);
                    host.postAlive(
                            () -> {
                                if (message == null) {
                                    host.toast(host.getString(R.string.files_no_message));
                                } else {
                                    open(message);
                                }
                            });
                });
    }

    /**
     * The action row: the two toggles drawn at what the message stands
     * at, and the delete button.
     */
    private void actions() {
        // NOTE: a message still in the outbox has no markers to move. It
        // has never been anywhere that keeps any, and offering the toggles
        // would promise a state nothing would remember.
        boolean pending = current != null && current.pending;

        ImageButton unread = host.findViewById(R.id.message_view_unread);
        unread.setVisibility(pending ? View.GONE : View.VISIBLE);
        unread.setContentDescription(
                host.getString(seen ? R.string.message_mark_unread : R.string.message_mark_read));
        unread.setImageResource(seen ? R.drawable.ic_visibility_off : R.drawable.ic_check);
        unread.setOnClickListener(view -> write(Flags.Marker.SEEN, !seen));

        ImageButton flag = host.findViewById(R.id.message_view_flag);
        flag.setVisibility(pending ? View.GONE : View.VISIBLE);
        flag.setContentDescription(
                host.getString(flagged ? R.string.message_unflag : R.string.message_flag));
        starOf(host, flag, flagged);
        flag.setOnClickListener(view -> write(Flags.Marker.FLAGGED, !flagged));

        host.findViewById(R.id.message_view_delete).setOnClickListener(view -> confirmDelete());

        // A message still waiting to go out has nothing to answer yet; one
        // that may have gone is sent again only when the user says so.
        boolean uncertain = current != null && current.uncertain;
        host.findViewById(R.id.message_view_replies)
                .setVisibility(pending && !uncertain ? View.GONE : View.VISIBLE);
        TextView reply = host.findViewById(R.id.message_view_reply);
        reply.setTextColor(host.accentContrast());
        reply.setCompoundDrawableTintList(
                android.content.res.ColorStateList.valueOf(host.accentContrast()));
        reply.setText(uncertain ? R.string.message_send_again : R.string.message_reply);
        reply.setOnClickListener(
                view -> {
                    if (uncertain) {
                        sendAgain(current);
                    } else {
                        compose.open(reply(current, loaded));
                    }
                });
        View forward = host.findViewById(R.id.message_view_forward);
        forward.setVisibility(uncertain ? View.GONE : View.VISIBLE);
        forward.setOnClickListener(view -> compose.open(forward(current, loaded)));
    }

    /**
     * Queues a message that may have gone to be sent again, the user having
     * found it did not, then leaves the reader.
     */
    private void sendAgain(MailStore.StoredMessage message) {
        host.io.execute(
                () -> {
                    Exception failure = null;
                    try {
                        parts.mail().resendOutgoing(message.queued);
                    } catch (Exception error) {
                        Log.w("pimalaya", "send again failed", error);
                        failure = error;
                    }
                    Exception error = failure;
                    host.postAlive(
                            () -> {
                                if (error != null) {
                                    host.showError(error, R.string.message_write_failed);
                                    return;
                                }
                                host.toast(host.getString(R.string.message_sent_again));
                                mailList.reload();
                                if (current == message) {
                                    host.showBack(Panels.MAIL);
                                }
                            });
                });
    }

    /**
     * A reply to everyone on the message: the sender, and every other
     * address it went to, the account's own left out. A message the
     * account sent itself answers its recipients instead, which is who a
     * follow-up goes to. The quote and the people beyond the sender wait
     * for the body; before it loads, the reply goes to the sender alone.
     */
    private MessageCompose.Prefill reply(MailStore.StoredMessage message, MessageBody body) {
        MailReply.People people =
                MailReply.replyAll(
                        message.accountEmail,
                        message.fromAddress,
                        body != null ? body.to() : null,
                        body != null ? body.cc() : null);

        MessageCompose.Prefill prefill = new MessageCompose.Prefill();
        prefill.from = message.accountEmail;
        prefill.to = people.to();
        prefill.cc = people.cc();
        prefill.subject = MailReply.prefixed("Re:", message.subject);
        prefill.parent = message;
        List<String> thread = parts.mail().threadOf(message.collection, message.id);
        if (!thread.isEmpty()) {
            prefill.inReplyTo = MailReply.inReplyTo(thread);
            prefill.references = MailReply.references(thread);
        }
        if (body != null) {
            prefill.body =
                    "\n\n"
                            + host.getString(
                                    R.string.compose_quote_intro, when(message), sender(message))
                            + "\n"
                            + MailReply.quoted(plain(body));
        }
        return prefill;
    }

    /**
     * A forward: nobody to send to yet, the subject marked, and the
     * message's headers over its text. The attachments stay behind, the
     * composer writing plain text only.
     */
    private MessageCompose.Prefill forward(MailStore.StoredMessage message, MessageBody body) {
        MessageCompose.Prefill prefill = new MessageCompose.Prefill();
        prefill.from = message.accountEmail;
        prefill.subject = MailReply.prefixed("Fwd:", message.subject);
        StringBuilder text = new StringBuilder("\n\n");
        text.append(host.getString(R.string.compose_forward_intro)).append('\n');
        text.append("From: ").append(sender(message)).append('\n');
        text.append("Date: ").append(when(message)).append('\n');
        text.append("Subject: ").append(message.subject).append('\n');
        if (body != null) {
            text.append("To: ").append(body.to()).append('\n');
            if (!body.cc().isEmpty()) {
                text.append("Cc: ").append(body.cc()).append('\n');
            }
            text.append('\n').append(plain(body));
        }
        prefill.body = text.toString();
        return prefill;
    }

    /** The message's text, an HTML body read down to what it says. */
    private static String plain(MessageBody body) {
        if (MessageBody.HTML.equals(body.kind())) {
            return android.text.Html.fromHtml(body.body(), android.text.Html.FROM_HTML_MODE_COMPACT)
                    .toString()
                    .trim();
        }
        return body.body().trim();
    }

    /** When the message was sent, as a quote's intro says it. */
    private String when(MailStore.StoredMessage message) {
        return message.stamp > 0 ? Dates.full(host, message.stamp) : "";
    }

    /** Stages one marker on the open message, then redraws the buttons. */
    private void write(Flags.Marker flag, boolean add) {
        MailStore.StoredMessage message = current;
        stageFlag(
                message,
                flag,
                add,
                () -> {
                    if (current != message) {
                        return;
                    }
                    if (flag == Flags.Marker.SEEN) {
                        seen = add;
                    } else if (flag == Flags.Marker.FLAGGED) {
                        flagged = add;
                    }
                    actions();
                    mailList.reload();
                });
    }

    /**
     * Stages one marker on a message, then runs {@code done} on the main
     * thread; the reader's toggles and the list's star both write here.
     *
     * <p>The store and nothing else. The next sync pushes the difference
     * between what is staged and what the server last agreed on, so a
     * marker written with the radio off is a marker written, and the only
     * thing either waits for is a disk write.
     */
    void stageFlag(
            MailStore.StoredMessage message, Flags.Marker flag, boolean add, Runnable done) {
        stageFlag(List.of(message), flag, add, done);
    }

    /** Stages one marker on every message, the list's selection's toggles. */
    void stageFlag(
            List<MailStore.StoredMessage> messages, Flags.Marker flag, boolean add, Runnable done) {
        host.io.execute(
                () -> {
                    Exception failure = null;
                    for (MailStore.StoredMessage message : messages) {
                        if (message.pending) {
                            continue;
                        }
                        try {
                            new MailStager(parts.pimdir(), parts.clients())
                                    .stageFlag(message, flag, add);
                        } catch (Exception error) {
                            Log.w("pimalaya", "message flag failed: " + message.id, error);
                            if (failure == null) {
                                failure = error;
                            }
                        }
                    }

                    Exception error = failure;
                    host.postAlive(
                            () -> {
                                if (error != null) {
                                    host.showError(error, R.string.message_write_failed);
                                    return;
                                }
                                done.run();
                            });
                });
    }

    /** Draws a star toggle: filled in the star's yellow when on, outlined when off. */
    static void starOf(Host host, ImageView star, boolean on) {
        star.setImageResource(on ? R.drawable.ic_star_filled : R.drawable.ic_star);
        star.setColorFilter(
                on
                        ? host.getColor(R.color.star)
                        : host.ui.resolveColor(android.R.attr.textColorSecondary));
    }

    /** Asks before deleting: the reader is one tap from losing a message. */
    private void confirmDelete() {
        MailStore.StoredMessage message = current;
        confirmDelete(
                List.of(message),
                () -> {
                    if (current == message) {
                        host.showBack(Panels.MAIL);
                    }
                });
    }

    /**
     * Asks before deleting {@code messages}, then stages it and runs
     * {@code done}; the reader's delete and the list's selection both come
     * here.
     *
     * <p>The question counts the messages a delete removes for good while
     * the phone holds no body of them, which no restore can bring back:
     * Deleted items keeps what was stored, and a header is not a message.
     */
    void confirmDelete(List<MailStore.StoredMessage> messages, Runnable done) {
        host.io.execute(
                () -> {
                    int lost = 0;
                    for (MailStore.StoredMessage message : messages) {
                        if (parts.mail().erases(message) && !parts.mail().holdsBody(message)) {
                            lost++;
                        }
                    }
                    int unrestorable = lost;
                    host.postAlive(
                            () -> {
                                String question =
                                        host.getResources()
                                                .getQuantityString(
                                                        R.plurals.messages_delete_confirm,
                                                        messages.size(),
                                                        messages.size());
                                if (unrestorable > 0) {
                                    String warning =
                                            messages.size() == 1
                                                    ? host.getString(
                                                            R.string.message_delete_unrestorable)
                                                    : host.getResources()
                                                            .getQuantityString(
                                                                    R.plurals
                                                                            .messages_delete_unrestorable,
                                                                    unrestorable,
                                                                    unrestorable);
                                    question += "\n\n" + warning;
                                }
                                new AlertDialog.Builder(host)
                                        .setMessage(question)
                                        .setPositiveButton(
                                                R.string.message_delete,
                                                (dialog, which) -> stageDelete(messages, done))
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .show();
                            });
                });
    }

    /**
     * Stages the messages' deletion, then reloads the list and runs
     * {@code done}; the reader's delete and the list's selection both
     * come here.
     *
     * <p>A delete is a pimdir mutation like any other action, so the list
     * shows its outcome at once and the sync carries it out as staged:
     * what is staged ({@link MailStager#stageDelete}) is what the toast
     * says.
     *
     * <p>Unless the account records no trash and is a JMAP one, which RFC
     * 8621 gives no keyword to mark a message with (section 4.1.1 names
     * three and this is not one of them). Refused here rather than staged,
     * because a change nothing could ever carry out would sit in the store
     * failing once per sync.
     */
    void stageDelete(List<MailStore.StoredMessage> messages, Runnable done) {
        List<MailStore.StoredMessage> staged = new ArrayList<>();
        boolean refused = false;
        for (MailStore.StoredMessage message : messages) {
            AccountEntry account = accountOf(message.accountEmail);
            if (parts.mail().trashOf(message.accountEmail).isEmpty()
                    && !message.pending
                    && account != null
                    && AccountClient.isJmap(account.server(PimDomain.MAIL))) {
                refused = true;
                continue;
            }
            staged.add(message);
        }
        if (refused) {
            host.toast(host.getString(R.string.message_delete_no_trash));
        }
        if (staged.isEmpty()) {
            return;
        }

        host.io.execute(
                () -> {
                    Exception failure = null;
                    MailStager.Deletion first = null;
                    for (MailStore.StoredMessage message : staged) {
                        try {
                            MailStager.Deletion deletion =
                                    new MailStager(parts.pimdir(), parts.clients())
                                            .stageDelete(message);
                            if (first == null) {
                                first = deletion;
                            }
                        } catch (Exception error) {
                            Log.w("pimalaya", "message delete failed: " + message.id, error);
                            if (failure == null) {
                                failure = error;
                            }
                        }
                    }

                    Exception error = failure;
                    MailStager.Deletion deletion = first;
                    host.postAlive(
                            () -> {
                                if (error != null) {
                                    host.showError(error, R.string.message_write_failed);
                                    return;
                                }
                                if (staged.size() > 1) {
                                    host.toast(
                                            host.getResources()
                                                    .getQuantityString(
                                                            R.plurals.messages_deleted,
                                                            staged.size(),
                                                            staged.size()));
                                } else if (deletion != MailStager.Deletion.WITHDRAWN) {
                                    host.toast(deletedOf(staged.get(0), deletion));
                                }
                                mailList.reload();
                                done.run();
                            });
                });
    }

    /** What deleting one message did, as its toast says. */
    private String deletedOf(MailStore.StoredMessage message, MailStager.Deletion deletion) {
        switch (deletion) {
            case MOVED:
                return host.getString(
                        R.string.message_deleted, host.getString(R.string.mail_chip_trash));
            case ERASED:
                return host.getString(R.string.message_deleted_for_good);
            default:
                return host.getString(R.string.message_deleted_in_place);
        }
    }

    /** The account the message came from, null when it is gone. */
    private AccountEntry accountOf(String email) {
        for (AccountEntry account : parts.store().accountsFor(PimDomain.MAIL)) {
            if (account.email.equals(email)) {
                return account;
            }
        }
        return null;
    }

    /** Fills the header in from the fetch, then shows the body. */
    private void render(MessageBody message) {
        loaded = message;
        if (!message.to().isEmpty()) {
            TextView recipients = host.findViewById(R.id.message_view_recipients);
            recipients.setText(recipients(message));
            recipients.setVisibility(View.VISIBLE);
        }

        long stamp = MailDate.toStamp(message.date());
        if (stamp > 0) {
            date(stamp);
        }

        if (MessageBody.HTML.equals(message.kind())) {
            body(html(message.body()));
        } else if (MessageBody.PLAIN.equals(message.kind())) {
            body(text(message.body()));
        } else {
            state(host.getString(R.string.message_no_content));
        }
    }

    /** Who the message went to, Cc included when it carries one. */
    private String recipients(MessageBody message) {
        String to = host.getString(R.string.message_to, message.to());
        return message.cc().isEmpty()
                ? to
                : to + "\n" + host.getString(R.string.message_cc, message.cc());
    }

    /** Shows one view as the body, in place of whatever was there. */
    private void body(View view) {
        FrameLayout frame = clearBody();
        frame.addView(view);
        frame.setVisibility(View.VISIBLE);
        host.findViewById(R.id.message_view_state).setVisibility(View.GONE);
        host.findViewById(R.id.message_view_loading).setVisibility(View.GONE);
    }

    /** A spinner in the body's place, while the body is on its way. */
    private void loading() {
        clearBody().setVisibility(View.GONE);
        host.findViewById(R.id.message_view_state).setVisibility(View.GONE);
        host.findViewById(R.id.message_view_loading).setVisibility(View.VISIBLE);
    }

    /** A word in the body's place: nothing to show, or a failure. */
    private void state(String message) {
        TextView state = host.findViewById(R.id.message_view_state);
        state.setText(message);
        state.setVisibility(View.VISIBLE);

        clearBody().setVisibility(View.GONE);
        host.findViewById(R.id.message_view_loading).setVisibility(View.GONE);
    }

    /**
     * Empties the body frame, destroying a web view rather than only
     * dropping it: a detached WebView keeps its own renderer, and one
     * per message opened would accumulate for the life of the process.
     */
    private FrameLayout clearBody() {
        FrameLayout frame = host.findViewById(R.id.message_view_body);
        for (int index = 0; index < frame.getChildCount(); index++) {
            if (frame.getChildAt(index) instanceof WebView) {
                ((WebView) frame.getChildAt(index)).destroy();
            }
        }
        frame.removeAllViews();
        return frame;
    }

    /** A plain body: selectable text, scrolling on its own. */
    private View text(String body) {
        TextView view = new TextView(host);
        view.setText(body);
        view.setTextIsSelectable(true);
        view.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setPadding(host.dp(16), 0, host.dp(16), host.dp(16));

        ScrollView scroll = new ScrollView(host);
        scroll.addView(view);
        return scroll;
    }

    /** An HTML body: a web view that can neither script nor phone home. */
    private View html(String body) {
        WebView view = new WebView(host);

        // Mail is written for a white page; letting the platform invert
        // it turns some messages fully black.
        view.setBackgroundColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            view.getSettings().setAlgorithmicDarkeningAllowed(false);
        }

        view.getSettings().setJavaScriptEnabled(false);
        view.getSettings().setBlockNetworkLoads(true);
        view.getSettings().setLoadsImagesAutomatically(false);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(false);
        view.getSettings().setUseWideViewPort(true);
        view.getSettings().setLoadWithOverviewMode(true);

        // A link opens where links open, which is not inside a message;
        // only one the reader tapped, in the message itself
        // (linkIntent), never a refresh or a frame loading on its own.
        view.setWebViewClient(
                new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(
                            WebView view, WebResourceRequest request) {
                        Intent intent =
                                linkIntent(
                                        request.getUrl(),
                                        request.hasGesture(),
                                        request.isForMainFrame());
                        if (intent != null) {
                            open(intent);
                        }
                        return true;
                    }
                });

        view.loadDataWithBaseURL(null, fitToWidth(body), "text/html", "UTF-8", null);
        return view;
    }

    /** The schemes a link in a message may open. */
    private static final Set<String> LINK_SCHEMES = Set.of("http", "https", "mailto");

    /**
     * The intent a link in a message opens, null when it opens nothing:
     * one the reader did not tap (a meta refresh opens a tracker on its
     * own), one in a frame, or one of a scheme other than the web's and
     * mail's (another app's deep link). Browsable only, as a browser
     * would open it, so it never reaches an activity no browser could.
     */
    public static Intent linkIntent(Uri url, boolean gesture, boolean mainFrame) {
        String scheme = url == null ? null : url.getScheme();
        if (!gesture
                || !mainFrame
                || scheme == null
                || !LINK_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return null;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, url);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setComponent(null);
        intent.setSelector(null);
        return intent;
    }

    /** Hands a link to whatever handles it, and says nothing when
     *  nothing does: a message must not crash the reader. The link itself
     *  stays out of the log, a sign-in or reset link carrying its token. */
    private void open(Intent intent) {
        try {
            host.startActivity(intent);
        } catch (Exception error) {
            Log.w("pimalaya", "no handler for a " + intent.getScheme() + " link", error);
        }
    }

    /**
     * Wraps a message's HTML so it fits a phone: nothing may exceed the
     * viewport, so a mail laid out for a desktop reflows instead of
     * forcing the reader to scroll sideways through it.
     */
    private static String fitToWidth(String body) {
        return "<!DOCTYPE html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<style>"
                + "html, body { margin: 0; padding: 0; background: #ffffff; color: #000000; }"
                + "body { padding: 12px; overflow-wrap: break-word; word-break: break-word; }"
                + "* { max-width: 100% !important; box-sizing: border-box; }"
                + "img, video { height: auto !important; }"
                + "table { table-layout: fixed !important; width: 100% !important; }"
                + "td, th { overflow-wrap: break-word; word-break: break-word; }"
                + "pre { white-space: pre-wrap; word-wrap: break-word; }"
                + "</style></head><body>"
                + body
                + "</body></html>";
    }

    /**
     * One badge per attachment, packed onto as many rows as it takes.
     *
     * <p>Packed by hand because the platform has no wrapping row: the
     * alternative is a horizontal scroll, which hides the third
     * attachment of a message behind an edge nothing says is there.
     */
    private void badges(List<FileStore.StoredFile> attachments) {
        LinearLayout container = host.findViewById(R.id.message_view_attachments);
        container.removeAllViews();
        container.setVisibility(attachments.isEmpty() ? View.GONE : View.VISIBLE);

        int available =
                host.getResources().getDisplayMetrics().widthPixels - host.dp(CARD_INSET);
        int free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);

        LinearLayout row = null;
        int used = 0;
        MailStore.StoredMessage message = current;
        for (FileStore.StoredFile attachment : attachments) {
            View badge = badge(attachment);
            badge.setOnClickListener(view -> choose(message, attachment));
            badge.measure(free, free);
            int width = badge.getMeasuredWidth() + host.dp(8);

            if (row == null || used + width > available) {
                row = new LinearLayout(host);
                row.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(row);
                used = 0;
            }

            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(host.dp(8));
            params.topMargin = host.dp(8);
            row.addView(badge, params);
            used += width;
        }
    }

    /** One attachment badge: a paperclip, the name, and how big it is. */
    private View badge(FileStore.StoredFile attachment) {
        ImageView icon = new ImageView(host);
        icon.setImageResource(R.drawable.ic_attach_file);
        icon.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        host.ui.resolveColor(android.R.attr.textColorSecondary)));

        TextView label = new TextView(host);
        label.setText(name(attachment));
        label.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMarginStart(host.dp(6));

        LinearLayout badge = new LinearLayout(host);
        badge.setOrientation(LinearLayout.HORIZONTAL);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        badge.setBackgroundResource(R.drawable.badge_background);
        badge.setPadding(host.dp(10), host.dp(6), host.dp(12), host.dp(6));
        badge.addView(icon, new LinearLayout.LayoutParams(host.dp(16), host.dp(16)));
        badge.addView(label, labelParams);
        return badge;
    }

    /** What a badge calls an attachment, size included. */
    private String name(FileStore.StoredFile attachment) {
        String name = label(attachment);
        return attachment.size == null || attachment.size <= 0
                ? name
                : name + " · " + size(attachment.size);
    }

    /** An attachment's name, or what stands for none. */
    private String label(FileStore.StoredFile attachment) {
        return attachment.name.isEmpty()
                ? host.getString(R.string.message_attachment_unnamed)
                : attachment.name;
    }

    /** A byte count in the largest unit that keeps it under a thousand. */
    private String size(long bytes) {
        if (bytes < 1024) {
            return host.getString(R.string.size_bytes, bytes);
        }
        if (bytes < 1024 * 1024) {
            return host.getString(R.string.size_kilobytes, bytes / 1024);
        }
        return host.getString(R.string.size_megabytes, bytes / (1024 * 1024));
    }
}
