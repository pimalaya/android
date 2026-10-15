package org.pimalaya.mail;

import android.app.AlertDialog;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import org.pimalaya.R;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Account;
import org.pimalaya.client.Flags;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.Panels;

import java.util.ArrayList;
import java.util.List;

/**
 * The composer: who it is from, who it goes to, and what it says.
 *
 * <p>Plain text, no draft: leaving loses what was typed, and says so. Sending
 * queues for the next sync, offline too. The account is fixed once opened (a
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
        ((RecipientField) host.findViewById(R.id.compose_to)).set(prefill.to);
        ((RecipientField) host.findViewById(R.id.compose_cc)).set(prefill.cc);
        ((RecipientField) host.findViewById(R.id.compose_bcc)).clear();
        ((EditText) host.findViewById(R.id.compose_subject)).setText(prefill.subject);
        EditText body = host.findViewById(R.id.compose_body);
        body.setText(prefill.body);
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
                && value(R.id.compose_body).equals(prefill.body.trim());
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

    /** Composes what is on screen into the outbox ({@link MailSubmit}). */
    public void send() {
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
                        value(R.id.compose_body),
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
                        new MailSubmit(
                                        parts.clients().mail(),
                                        parts.mail(),
                                        new MailStager(parts.pimdir(), parts.clients()))
                                .queue(draft);
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

    private String value(int id) {
        return ((EditText) host.findViewById(id)).getText().toString().trim();
    }

    /** One address field's recipients, comma separated. */
    private String recipients(int id) {
        return ((RecipientField) host.findViewById(id)).value();
    }
}
