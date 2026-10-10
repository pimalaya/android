package org.pimalaya;

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

import org.pimalaya.client.MailSession;
import org.pimalaya.client.MessageBody;
import org.pimalaya.client.PimalayaClient;

import java.util.ArrayList;
import java.util.List;

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
final class MessageView {
    /** How wide a badge row may run: the reader's own inner width. */
    private static final int CARD_INSET = 32;

    private final MainActivity host;

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

    MessageView(MainActivity host) {
        this.host = host;
    }

    /** Opens the reader on one row, then fetches what it does not hold. */
    void open(MailStore.StoredMessage message) {
        current = message;
        loaded = null;
        seen = message.seen;
        flagged = message.flagged;

        header(message);
        badges(new ArrayList<>());
        host.findViewById(R.id.message_view_linked).setVisibility(View.GONE);
        actions();
        loading();
        host.show(MainActivity.PANEL_MESSAGE);

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

        host.io.execute(
                () -> {
                    MessageBody loaded = null;
                    boolean fetched = false;
                    Exception failure = null;
                    try {
                        byte[] source = host.mail.source(message);
                        if (source == null && message.pending) {
                            // NOTE: never fetched. A message waiting to go out
                            // is nothing but the body its queue row carries, so
                            // there is no server that has it and no mailbox to
                            // ask: a missing body here is a lost message, not a
                            // message yet to be read.
                            throw new IllegalStateException(
                                    host.getString(R.string.message_failed));
                        }
                        if (source == null && !host.online()) {
                            // NOTE: not on the phone, and no network to
                            // fetch it: within its window the body step
                            // brings it once back online, else an open does.
                            boolean taken =
                                    MailBodies.takes(
                                            message.sortKey,
                                            host.mail.windowOf(message.accountEmail),
                                            host.mail.roles().get(message.collection),
                                            MailOffline.whole(host, message.collection));
                            throw new IllegalStateException(
                                    host.getString(
                                            taken
                                                    ? R.string.message_not_on_phone
                                                    : R.string.message_not_on_phone_later));
                        }
                        if (source == null) {
                            source = fetch(message, account);
                            fetched = true;
                        }
                        loaded = host.client.parseMessage(source);
                        if (!message.pending) {
                            // NOTE: the listing marked the attachment off the
                            // top-level type alone; the parts are in now.
                            host.mail.markAttachment(
                                    message.collection, message.id, loaded.attachmentMark);
                            host.files.recordAttachments(
                                    host.mail.accountIdOf(message.accountEmail),
                                    message.id,
                                    loaded.attachments);
                        }
                    } catch (Exception error) {
                        Log.w("pimalaya", "message fetch failed: " + message.id, error);
                        failure = error;
                    }
                    // NOTE: read whether or not the body came: a message
                    // whose body was released keeps its attachments listed.
                    List<FileStore.StoredFile> files =
                            message.pending
                                    ? new ArrayList<>()
                                    : host.files.attachments(
                                            host.mail.accountIdOf(message.accountEmail),
                                            message.id);
                    ItemLinks.Endpoint self = ItemLinks.ofMessage(message);
                    int links = self == null ? -1 : host.links.links(self).size();

                    MessageBody outcome = loaded;
                    boolean stored = fetched;
                    Exception error = failure;
                    host.postAlive(
                            () -> {
                                // NOTE: its row is on the phone now, no
                                // longer dimmed.
                                if (stored) {
                                    host.mailList.reload();
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
                                    write(MailEngine.SEEN, true);
                                }
                            });
                });
    }

    /** Fetches one message the store does not hold, and files it. */
    private byte[] fetch(MailStore.StoredMessage message, AccountEntry account) {
        String[] address = addressOf(message);
        byte[] source;
        // NOTE: a connection of its own, opened and closed around this one
        // read. A reader opening a message is not a pass, and holding one
        // open for the time someone spends reading would be holding it for
        // no work at all.
        try (MailSession session = host.openMail(account)) {
            source = host.client.fetchMessageSource(session, address[0], address[1]);
        }
        host.mail.saveSource(message.collection, message.id, source);
        return source;
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
                        host.linked.dialog(
                                ItemLinks.ofMessage(message),
                                message.subject,
                                () -> {
                                    if (current == message) {
                                        linked(
                                                message,
                                                host.links
                                                        .links(ItemLinks.ofMessage(message))
                                                        .size());
                                    }
                                }));
    }

    /** What a tap on an attachment offers: open it, share it, or save it to a folder. */
    private void choose(MailStore.StoredMessage message, FileStore.StoredFile attachment) {
        FileActions.Bytes bytes = () -> bytesOf(message, attachment);
        CharSequence[] actions = {
            host.getString(R.string.attachment_open),
            host.getString(R.string.files_share),
            host.getString(R.string.attachment_save)
        };
        new AlertDialog.Builder(host)
                .setTitle(host.fileActions.label(attachment))
                .setItems(
                        actions,
                        (dialog, which) -> {
                            if (which == 0) {
                                host.fileActions.open(attachment, bytes);
                            } else if (which == 1) {
                                host.fileActions.share(attachment, bytes);
                            } else {
                                host.fileActions.saveToFolder(attachment, bytes);
                            }
                        })
                .show();
    }

    /**
     * The bytes of one attachment: a saved copy's body when one was saved,
     * else the part read from the message's body, the message fetched first
     * when the store does not hold it.
     *
     * <p>NOTE: fetching the part alone (IMAP {@code BODY.PEEK[<section>]},
     * Gmail {@code attachments.get}, Graph {@code /attachments/{id}}, a JMAP
     * blob) would spare the rest of the message; the whole message is what
     * every backend reads today.
     */
    byte[] bytesOf(MailStore.StoredMessage message, FileStore.StoredFile attachment) {
        if (attachment.objectHash != null) {
            byte[] saved = host.files.saved(attachment);
            if (saved != null) {
                return saved;
            }
        }
        byte[] source = host.mail.source(message);
        if (source == null) {
            AccountEntry account = accountOf(message.accountEmail);
            if (account == null) {
                throw new IllegalStateException(host.getString(R.string.message_no_account));
            }
            if (!host.online()) {
                throw new IllegalStateException(
                        host.getString(R.string.message_not_on_phone_later));
            }
            source = fetch(message, account);
            host.postAlive(host.mailList::reload);
        }
        return host.client.messagePart(source, attachment.part);
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
        unread.setOnClickListener(view -> write(MailEngine.SEEN, !seen));

        ImageButton flag = host.findViewById(R.id.message_view_flag);
        flag.setVisibility(pending ? View.GONE : View.VISIBLE);
        flag.setContentDescription(
                host.getString(flagged ? R.string.message_unflag : R.string.message_flag));
        starOf(host, flag, flagged);
        flag.setOnClickListener(view -> write(MailEngine.FLAGGED, !flagged));

        host.findViewById(R.id.message_view_delete).setOnClickListener(view -> confirmDelete());

        // A message still waiting to go out has nothing to answer yet.
        host.findViewById(R.id.message_view_replies)
                .setVisibility(pending ? View.GONE : View.VISIBLE);
        TextView reply = host.findViewById(R.id.message_view_reply);
        reply.setTextColor(host.accentContrast());
        reply.setCompoundDrawableTintList(
                android.content.res.ColorStateList.valueOf(host.accentContrast()));
        reply.setOnClickListener(view -> host.compose.open(reply(current, loaded)));
        host.findViewById(R.id.message_view_forward)
                .setOnClickListener(view -> host.compose.open(forward(current, loaded)));
    }

    /**
     * A reply to everyone on the message: the sender, and every other
     * address it went to, the account's own left out. A message the
     * account sent itself answers its recipients instead, which is who a
     * follow-up goes to. The quote and the people beyond the sender wait
     * for the body; before it loads, the reply goes to the sender alone.
     */
    private MessageCompose.Prefill reply(MailStore.StoredMessage message, MessageBody body) {
        String self = message.accountEmail.toLowerCase();
        java.util.Set<String> to = new java.util.LinkedHashSet<>();
        java.util.Set<String> cc = new java.util.LinkedHashSet<>();
        boolean own = message.fromAddress.equalsIgnoreCase(self);
        if (!own && !message.fromAddress.isEmpty()) {
            to.add(message.fromAddress);
        }
        if (body != null) {
            for (String address : addressesOf(body.to)) {
                if (!address.equalsIgnoreCase(self)) {
                    to.add(address);
                }
            }
            for (String address : addressesOf(body.cc)) {
                if (!address.equalsIgnoreCase(self) && !to.contains(address)) {
                    cc.add(address);
                }
            }
        }

        MessageCompose.Prefill prefill = new MessageCompose.Prefill();
        prefill.from = message.accountEmail;
        prefill.to = String.join(", ", to);
        prefill.cc = String.join(", ", cc);
        prefill.subject = prefixed("Re:", message.subject);
        prefill.parent = message;
        List<String> thread = host.mail.threadOf(message.collection, message.id);
        if (!thread.isEmpty()) {
            prefill.inReplyTo = thread.get(thread.size() - 1);
            prefill.references = String.join(" ", thread);
        }
        if (body != null) {
            prefill.body =
                    "\n\n"
                            + host.getString(
                                    R.string.compose_quote_intro, when(message), sender(message))
                            + "\n"
                            + quoted(plain(body));
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
        prefill.subject = prefixed("Fwd:", message.subject);
        StringBuilder text = new StringBuilder("\n\n");
        text.append(host.getString(R.string.compose_forward_intro)).append('\n');
        text.append("From: ").append(sender(message)).append('\n');
        text.append("Date: ").append(when(message)).append('\n');
        text.append("Subject: ").append(message.subject).append('\n');
        if (body != null) {
            text.append("To: ").append(body.to).append('\n');
            if (!body.cc.isEmpty()) {
                text.append("Cc: ").append(body.cc).append('\n');
            }
            text.append('\n').append(plain(body));
        }
        prefill.body = text.toString();
        return prefill;
    }

    /** A subject carrying a prefix once, however many rounds it went. */
    private static String prefixed(String prefix, String subject) {
        return subject.regionMatches(true, 0, prefix, 0, prefix.length())
                ? subject
                : prefix + " " + subject;
    }

    /** The bare addresses a header value names, display names dropped. */
    private static List<String> addressesOf(String header) {
        List<String> addresses = new ArrayList<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("[^\\s<>,;\"]+@[^\\s<>,;\"]+").matcher(header);
        while (matcher.find()) {
            addresses.add(matcher.group());
        }
        return addresses;
    }

    /** The message's text, an HTML body read down to what it says. */
    private static String plain(MessageBody body) {
        if (MessageBody.HTML.equals(body.kind)) {
            return android.text.Html.fromHtml(body.body, android.text.Html.FROM_HTML_MODE_COMPACT)
                    .toString()
                    .trim();
        }
        return body.body.trim();
    }

    /** Every line marked as quoted, the way RFC 3676 section 4.5 reads it. */
    private static String quoted(String text) {
        StringBuilder quote = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            quote.append(line.startsWith(">") ? ">" : "> ").append(line).append('\n');
        }
        return quote.toString();
    }

    /** When the message was sent, as a quote's intro says it. */
    private String when(MailStore.StoredMessage message) {
        return message.stamp > 0 ? Dates.full(host, message.stamp) : "";
    }

    /** Stages one marker on the open message, then redraws the buttons. */
    private void write(String flag, boolean add) {
        MailStore.StoredMessage message = current;
        stageFlag(
                message,
                flag,
                add,
                () -> {
                    if (current != message) {
                        return;
                    }
                    if (MailEngine.SEEN.equals(flag)) {
                        seen = add;
                    } else if (MailEngine.FLAGGED.equals(flag)) {
                        flagged = add;
                    }
                    actions();
                    host.mailList.reload();
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
    void stageFlag(MailStore.StoredMessage message, String flag, boolean add, Runnable done) {
        stageFlag(List.of(message), flag, add, done);
    }

    /** Stages one marker on every message, the list's selection's toggles. */
    void stageFlag(
            List<MailStore.StoredMessage> messages, String flag, boolean add, Runnable done) {
        host.io.execute(
                () -> {
                    Exception failure = null;
                    for (MailStore.StoredMessage message : messages) {
                        if (message.pending) {
                            continue;
                        }
                        try {
                            MailEngine engine = host.mailEngine(message.accountEmail);
                            engine.mutateFlags(
                                    message.collection,
                                    engine.offline.handleFor(message.collection, message.id),
                                    MailEngine.withFlag(
                                            host.mail.flagsOf(message.collection, message.id),
                                            flag,
                                            add));
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
    static void starOf(MainActivity host, ImageView star, boolean on) {
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
                        host.showBack(MainActivity.PANEL_MAIL);
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
                        if (host.mail.erases(message) && !host.mail.holdsBody(message)) {
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
     * what is staged ({@link MailEngine#stageDelete}) is what the toast
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
            if (host.mail.trashOf(message.accountEmail).isEmpty()
                    && !message.pending
                    && account != null
                    && PimalayaClient.isJmap(account.server(PimDomain.MAIL))) {
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
                    MailEngine.Deletion first = null;
                    for (MailStore.StoredMessage message : staged) {
                        try {
                            MailEngine.Deletion deletion =
                                    host.mailEngine(message.accountEmail).stageDelete(message);
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
                    MailEngine.Deletion deletion = first;
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
                                } else if (deletion != MailEngine.Deletion.WITHDRAWN) {
                                    host.toast(deletedOf(staged.get(0), deletion));
                                }
                                host.mailList.reload();
                                done.run();
                            });
                });
    }

    /** What deleting one message did, as its toast says. */
    private String deletedOf(MailStore.StoredMessage message, MailEngine.Deletion deletion) {
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

    /**
     * Where the server holds a message, as {@code [mailbox, id]}: where it
     * is listed, or for the target of a move not carried out yet, the
     * mailbox it is moving from.
     */
    private String[] addressOf(MailStore.StoredMessage message) {
        MailEngine engine = host.mailEngine(message.accountEmail);
        String[] source = null;
        synchronized (PimdirEngine.STORE) {
            if (engine.offline.isPendingCreate(message.collection, message.id)) {
                source = engine.offline.moveSourceOf(message.collection, message.id);
            }
        }
        if (source == null) {
            return new String[] {message.mailbox, message.id};
        }
        return new String[] {
            PimdirAccount.nameOf(
                    host.mail.accountIdOf(message.accountEmail), source[0]),
            source[1]
        };
    }

    /** The account the message came from, null when it is gone. */
    private AccountEntry accountOf(String email) {
        for (AccountEntry account : host.accountsFor(PimDomain.MAIL)) {
            if (account.email.equals(email)) {
                return account;
            }
        }
        return null;
    }

    /** Fills the header in from the fetch, then shows the body. */
    private void render(MessageBody message) {
        loaded = message;
        if (!message.to.isEmpty()) {
            TextView recipients = host.findViewById(R.id.message_view_recipients);
            recipients.setText(recipients(message));
            recipients.setVisibility(View.VISIBLE);
        }

        long stamp = MailDate.toStamp(message.date);
        if (stamp > 0) {
            date(stamp);
        }

        if (MessageBody.HTML.equals(message.kind)) {
            body(html(message.body));
        } else if (MessageBody.PLAIN.equals(message.kind)) {
            body(text(message.body));
        } else {
            state(host.getString(R.string.message_no_content));
        }
    }

    /** Who the message went to, Cc included when it carries one. */
    private String recipients(MessageBody message) {
        String to = host.getString(R.string.message_to, message.to);
        return message.cc.isEmpty()
                ? to
                : to + "\n" + host.getString(R.string.message_cc, message.cc);
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

        // A link opens where links open, which is not inside a message.
        view.setWebViewClient(
                new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(
                            WebView view, WebResourceRequest request) {
                        open(request.getUrl());
                        return true;
                    }
                });

        view.loadDataWithBaseURL(null, fitToWidth(body), "text/html", "UTF-8", null);
        return view;
    }

    /** Hands a link to whatever handles it, and says nothing when
     *  nothing does: a message must not crash the reader. */
    private void open(Uri url) {
        try {
            host.startActivity(new Intent(Intent.ACTION_VIEW, url));
        } catch (Exception error) {
            Log.w("pimalaya", "no handler for " + url, error);
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
