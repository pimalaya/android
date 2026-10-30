package org.pimalaya.mail;

import android.app.AlertDialog;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.pimalaya.R;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Account;
import org.pimalaya.client.Flags;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.ui.ChipField;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.Panels;
import org.pimalaya.ui.PillField;

import java.util.ArrayList;
import java.util.List;

/**
 * The composer: who it is from, who it goes to, and what it says.
 *
 * <p>Rich (styled, with a formatting bar, sent with its Markdown) or plain; no
 * draft: leaving loses what was typed, and says so. Sending queues for the
 * next sync, offline too. The account is fixed once opened (a
 * server checks the sender), so with several the picker comes first.
 */
public final class MessageCompose {
    /** The three address fields. */
    private static final int[] RECIPIENTS = {
        R.id.compose_to, R.id.compose_cc, R.id.compose_bcc
    };

    private final Host host;

    private final MailParts parts;

    /** The reader, which stages the parent's answered marker. */
    private final MessageView messageView;

    /** The list read again once a message is queued. */
    private final MailList mailList;

    /** Which account the message is sent from; null while none is open. */
    private AccountEntry account;

    /** What the composer opened on, so leaving untouched asks nothing. */
    private Prefill opened;

    /** The text as it opened, as Markdown, so leaving untouched asks nothing. */
    private String loaded = "";

    /**
     * What the composer opens with: blank, or a parent's reply or forward.
     */
    static final class Prefill {
        /** The account to send from, when the message has one; null to ask. */
        String from;

        String to = "";
        String cc = "";
        String subject = "";
        String body = "";

        /** Whether it starts rich; null for the account's default. */
        Boolean rich;

        /** The parent's `Message-ID`, in brackets; empty outside a reply. */
        String inReplyTo = "";

        /** The thread a reply continues, space separated; else empty. */
        String references = "";

        /** The message replied to, marked answered once the reply is queued. */
        MailStore.StoredMessage parent;
    }

    /** The composer of {@code messageView}, reloading {@code mailList}. */
    public MessageCompose(Host host, MailParts parts, MessageView messageView, MailList mailList) {
        this.host = host;
        this.parts = parts;
        this.messageView = messageView;
        this.mailList = mailList;
        messageView.compose = this;

        MarkdownField body = body();
        body.setOnStateChanged(this::formatBar);
        body.setOnFocusChangeListener((view, focused) -> formatBar());
        inline(R.id.compose_bold, "strong");
        inline(R.id.compose_italic, "emphasis");
        inline(R.id.compose_strike, "strike");
        inline(R.id.compose_code, "code");
        lines(R.id.compose_bullets, "bullet");
        lines(R.id.compose_numbers, "ordered");
        lines(R.id.compose_quote, "quote");
        host.findViewById(R.id.compose_link).setOnClickListener(view -> link());
        host.findViewById(R.id.compose_clear).setOnClickListener(view -> body.clearInline());

        // NOTE: a label puts the caret in its field, as a form's label does.
        for (int[] pair :
                new int[][] {
                    {R.id.compose_to_label, R.id.compose_to},
                    {R.id.compose_cc_label, R.id.compose_cc},
                    {R.id.compose_bcc_label, R.id.compose_bcc},
                    {R.id.compose_subject_label, R.id.compose_subject},
                }) {
            View field = host.findViewById(pair[1]);
            host.findViewById(pair[0])
                    .setOnClickListener(
                            view -> {
                                if (field instanceof ChipField recipients) {
                                    recipients.focus();
                                } else {
                                    field.requestFocus();
                                }
                                host.showKeyboard();
                            });
        }
    }

    /** Opens the composer on a blank message. */
    public void open() {
        open(new Prefill());
    }

    /**
     * Opens the composer filled, on the prefill's account when it can send.
     *
     * <p>Otherwise asks which account sends when several, taking the only one.
     */
    void open(Prefill prefill) {
        List<AccountEntry> accounts = new ArrayList<>();
        for (AccountEntry candidate : parts.store().accountsFor(PimDomain.MAIL)) {
            // NOTE: an account with nowhere to submit is not offered rather
            // than offered and refused at the send: one connected before
            // submission was discovered at all. JMAP, Graph and Gmail submit
            // over the session they read from, whatever endpoint is stored.
            Account server = candidate.server(PimDomain.MAIL);
            boolean endpoint =
                    server != null && server.submitUrl != null && !server.submitUrl.isEmpty();
            if (endpoint || server != null && server.info().submitsOverSession()) {
                accounts.add(candidate);
            }
        }

        if (accounts.isEmpty()) {
            host.toast(host.getString(R.string.compose_no_account));
            return;
        }
        for (AccountEntry candidate : accounts) {
            if (candidate.email.equals(prefill.from)) {
                open(candidate, prefill);
                return;
            }
        }
        if (accounts.size() == 1) {
            open(accounts.get(0), prefill);
            return;
        }

        CharSequence[] labels = new CharSequence[accounts.size()];
        for (int index = 0; index < accounts.size(); index++) {
            labels[index] = accounts.get(index).email;
        }

        new AlertDialog.Builder(host)
                .setTitle(R.string.compose_from_title)
                .setItems(labels, (dialog, which) -> open(accounts.get(which), prefill))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Opens the composer on one account, filled with the prefill. */
    private void open(AccountEntry account, Prefill prefill) {
        this.account = account;
        this.opened = prefill;

        ((TextView) host.findViewById(R.id.compose_from)).setText(account.email);
        ((ChipField) host.findViewById(R.id.compose_to)).set(prefill.to);
        ((ChipField) host.findViewById(R.id.compose_cc)).set(prefill.cc);
        ((ChipField) host.findViewById(R.id.compose_bcc)).clear();
        ((EditText) host.findViewById(R.id.compose_subject)).setText(prefill.subject);
        MarkdownField body = body();
        boolean rich = prefill.rich != null ? prefill.rich : ComposeFormat.rich(host, account.email);
        body.open(prefill.body, rich);
        formatBar();
        loaded = body.markdown();
        // NOTE: the caret above a quote, where a reply is written.
        body.setSelection(0);

        copies(!prefill.cc.isEmpty());
        host.findViewById(R.id.compose_copies).setOnClickListener(view -> copies(true));
        host.show(Panels.COMPOSE);
    }

    /** Whether the two copy rows are showing, and their reveal with them. */
    private void copies(boolean shown) {
        int visibility = shown ? View.VISIBLE : View.GONE;
        host.findViewById(R.id.compose_cc_row).setVisibility(visibility);
        host.findViewById(R.id.compose_bcc_row).setVisibility(visibility);
        host.findViewById(R.id.compose_copies).setVisibility(shown ? View.GONE : View.VISIBLE);
    }

    /**
     * Leaves the composer, asking first when there is anything to lose.
     *
     * <p>Asked rather than saved: a draft nobody can come back to is
     * worse than no draft, and coming back to one means storing it,
     * which is the mailbox the composer does not have yet.
     */
    public void close() {
        if (untouched()) {
            host.showBack(Panels.MAIL);
            return;
        }

        new AlertDialog.Builder(host)
                .setMessage(R.string.compose_discard_confirm)
                .setPositiveButton(
                        R.string.compose_discard,
                        (dialog, which) -> host.showBack(Panels.MAIL))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Whether every field still reads what the composer opened with. */
    private boolean untouched() {
        Prefill prefill = opened != null ? opened : new Prefill();
        return recipients(R.id.compose_to).equals(normalized(prefill.to))
                && recipients(R.id.compose_cc).equals(normalized(prefill.cc))
                && recipients(R.id.compose_bcc).isEmpty()
                && value(R.id.compose_subject).equals(prefill.subject.trim())
                && body().markdown().trim().equals(loaded.trim());
    }

    /** Addresses as a recipient field reads them back once chipped. */
    private static String normalized(String addresses) {
        List<String> all = new ArrayList<>();
        for (String address : addresses.split("[,; ]+")) {
            if (!address.isEmpty()) {
                all.add(address);
            }
        }
        return String.join(", ", all);
    }

    /**
     * Told of a mail sent with {@link #sendAndWait}: its subject, its first
     * recipient and the `Message-ID` it was queued under, bare.
     */
    public interface Waiting {
        void sent(String subject, String recipient, String messageId);
    }

    /** Who files a Waiting For on a send that waits; set by the host. */
    public Waiting waiting;

    /** Composes what is on screen into the outbox ({@link MailSubmit}). */
    public void send() {
        send(false);
    }

    /** Sends, then files a Waiting For on the reply ({@link #waiting}). */
    public void sendAndWait() {
        send(true);
    }

    private void send(boolean wait) {
        if (account == null) {
            return;
        }
        if (recipients(R.id.compose_to).isEmpty()
                && recipients(R.id.compose_cc).isEmpty()
                && recipients(R.id.compose_bcc).isEmpty()) {
            host.toast(host.getString(R.string.compose_no_recipient));
            return;
        }

        // NOTE: everything the fields say, read before the work moves off
        // this thread. A view is the main thread's, and the composition
        // that follows is not.
        MailSubmit.Draft draft =
                new MailSubmit.Draft(
                        account.email,
                        SenderName.of(host, account.email),
                        recipients(R.id.compose_to),
                        recipients(R.id.compose_cc),
                        recipients(R.id.compose_bcc),
                        value(R.id.compose_subject),
                        body().markdown().trim(),
                        body().isRich(),
                        opened != null ? opened.inReplyTo : "",
                        opened != null ? opened.references : "");

        AccountEntry sender = account;
        Account server = account.server(PimDomain.MAIL);
        MailStore.StoredMessage parent =
                opened == null || !server.info().keepsAnswered() ? null : opened.parent;
        host.setSending(true);
        host.io.execute(
                () -> {
                    Exception failure = null;
                    try {
                        String queued =
                                new MailSubmit(
                                                parts.clients().mail(),
                                                parts.mail(),
                                                new MailStager(parts.pimdir(), parts.clients()))
                                        .queue(draft);
                        if (wait && waiting != null) {
                            String to = draft.to() == null ? "" : draft.to();
                            waiting.sent(
                                    draft.subject(),
                                    to.split(",")[0].trim(),
                                    PimdirSummary.bare(queued));
                        }
                    } catch (Exception error) {
                        Log.w("pimalaya", "compose failed: " + AccountEntry.redacted(sender.email), error);
                        failure = error;
                    }

                    Exception outcome = failure;
                    host.postAlive(
                            () -> {
                                host.setSending(false);
                                if (outcome != null) {
                                    host.showError(outcome, R.string.compose_failed);
                                    return;
                                }
                                host.toast(host.getString(R.string.compose_queued));
                                host.showBack(Panels.MAIL);
                                // NOTE: the parent is answered once the
                                // reply is queued: both go out on the
                                // next sync.
                                if (parent != null && !parent.pending()) {
                                    messageView.stageFlag(
                                            parent,
                                            Flags.Marker.ANSWERED,
                                            true,
                                            mailList::reload);
                                } else {
                                    mailList.reload();
                                }
                            });
                });
    }

    /**
     * The formatting bar: shown while a rich text has the focus, each button
     * pressed while the caret sits in its style.
     */
    private void formatBar() {
        MarkdownField body = body();
        boolean shown = body.isRich() && body.hasFocus();
        host.findViewById(R.id.compose_format_bar)
                .setVisibility(shown ? View.VISIBLE : View.GONE);
        if (!shown) {
            return;
        }
        int[] ids = {
            R.id.compose_bold, R.id.compose_italic, R.id.compose_strike, R.id.compose_code,
            R.id.compose_link, R.id.compose_bullets, R.id.compose_numbers, R.id.compose_quote
        };
        String[] kinds = {
            "strong", "emphasis", "strike", "code", "link", "bullet", "ordered", "quote"
        };
        for (int index = 0; index < ids.length; index++) {
            host.findViewById(ids[index]).setActivated(body.active(kinds[index]));
        }
    }

    /** A button toggling an inline style's markers. */
    private void inline(int id, String kind) {
        host.findViewById(id).setOnClickListener(view -> body().toggle(kind));
    }

    /** A button toggling a list or a quote over the selected lines. */
    private void lines(int id, String kind) {
        host.findViewById(id).setOnClickListener(view -> body().toggleLines(kind));
    }

    /**
     * The link sheet: the text and the address of the link the caret sits
     * in, or of a new one around the selection.
     */
    private void link() {
        MarkdownField body = body();
        MarkdownField.Link current = body.link();
        PillField text =
                PillField.of(
                        host,
                        R.string.compose_link_text,
                        current != null ? current.text() : body.selected(),
                        false,
                        true);
        text.input.setInputType(InputType.TYPE_CLASS_TEXT);
        PillField url =
                PillField.of(
                        host, R.string.compose_link_url, current != null ? current.url() : "", false, true);

        LinearLayout fields = new LinearLayout(host);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(host.ui.dp(24), 0, host.ui.dp(24), 0);
        fields.addView(text.view);
        fields.addView(url.view);

        AlertDialog.Builder dialog =
                new AlertDialog.Builder(host)
                        .setTitle(R.string.compose_link_title)
                        .setView(fields)
                        .setPositiveButton(
                                R.string.compose_link_apply,
                                (shown, which) -> {
                                    String label = text.text().isEmpty() ? url.text() : text.text();
                                    body.writeLink(current, label, url.text());
                                })
                        .setNegativeButton(android.R.string.cancel, null);
        if (current != null) {
            dialog.setNeutralButton(
                    R.string.compose_link_remove,
                    (shown, which) -> body.writeLink(current, current.text(), ""));
        }
        dialog.show();
        (current == null && !body.selected().isEmpty() ? url : text).input.requestFocus();
    }

    private MarkdownField body() {
        return host.findViewById(R.id.compose_body);
    }

    private String value(int id) {
        return ((EditText) host.findViewById(id)).getText().toString().trim();
    }

    /** One address field's recipients, comma separated. */
    private String recipients(int id) {
        return ((ChipField) host.findViewById(id)).value();
    }
}
