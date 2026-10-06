package org.pimalaya;

import android.app.AlertDialog;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Account;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * The composer: who it is from, who it goes to, and what it says.
 *
 * <p>Plain text, one recipient field per kind, and the copies hidden
 * until they are asked for. What it is not is a draft editor: nothing is
 * stored until the message is sent, so leaving the screen loses what was
 * typed and the screen says so before it does.
 *
 * <p>Sending stores rather than submits. The message is composed here,
 * which needs nothing but the fields, and lands in the account's outbox;
 * the sync that follows hands it over. So a message can be written and
 * sent with the radio off, and what the composer reports is that it is
 * queued, not that it arrived.
 *
 * <p>The account is the one the composer opened on, and it is fixed for
 * the message: the sender is what a server checks a submission against,
 * so choosing it after the fact would mean re-authenticating somewhere
 * else. With more than one mail account, the picker comes first.
 */
final class MessageCompose {
    /** The pattern an RFC 5322 date is written in (section 3.3). */
    private static final String DATE_FORMAT = "EEE, d MMM yyyy HH:mm:ss Z";

    /** The three address fields. */
    private static final int[] RECIPIENTS = {
        R.id.compose_to, R.id.compose_cc, R.id.compose_bcc
    };

    private final MainActivity host;

    /** Which account the message is sent from; null while none is open. */
    private AccountEntry account;

    MessageCompose(MainActivity host) {
        this.host = host;
    }

    /**
     * Opens the composer, asking which account to send from when there
     * is more than one and taking it silently when there is one.
     */
    void open() {
        List<AccountEntry> accounts = new ArrayList<>();
        for (AccountEntry candidate : host.accountsFor(PimDomain.MAIL)) {
            // NOTE: an account with nowhere to submit is not offered
            // rather than offered and refused at the send: that is a JMAP
            // account, whose EmailSubmission is not wired, or one
            // connected before submission was discovered at all.
            Account server = candidate.server(PimDomain.MAIL);
            if (server != null && server.submitUrl != null && !server.submitUrl.isEmpty()) {
                accounts.add(candidate);
            }
        }

        if (accounts.isEmpty()) {
            host.toast(host.getString(R.string.compose_no_account));
            return;
        }
        if (accounts.size() == 1) {
            open(accounts.get(0));
            return;
        }

        CharSequence[] labels = new CharSequence[accounts.size()];
        for (int index = 0; index < accounts.size(); index++) {
            labels[index] = accounts.get(index).email;
        }

        new AlertDialog.Builder(host)
                .setTitle(R.string.compose_from_title)
                .setItems(labels, (dialog, which) -> open(accounts.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Opens the composer on one account, blank. */
    private void open(AccountEntry account) {
        this.account = account;

        ((TextView) host.findViewById(R.id.compose_from))
                .setText(host.getString(R.string.compose_from, account.email));
        for (int id : RECIPIENTS) {
            ((RecipientField) host.findViewById(id)).clear();
        }
        for (int id : new int[] {R.id.compose_subject, R.id.compose_body}) {
            ((EditText) host.findViewById(id)).setText("");
        }

        copies(false);
        host.findViewById(R.id.compose_copies).setOnClickListener(view -> copies(true));
        host.show(MainActivity.PANEL_COMPOSE);
    }

    /** Whether the two copy fields are showing, and their reveal with them. */
    private void copies(boolean shown) {
        int visibility = shown ? View.VISIBLE : View.GONE;
        host.findViewById(R.id.compose_cc).setVisibility(visibility);
        host.findViewById(R.id.compose_bcc).setVisibility(visibility);
        host.findViewById(R.id.compose_copies).setVisibility(shown ? View.GONE : View.VISIBLE);
    }

    /**
     * Leaves the composer, asking first when there is anything to lose.
     *
     * <p>Asked rather than saved: a draft nobody can come back to is
     * worse than no draft, and coming back to one means storing it,
     * which is the mailbox the composer does not have yet.
     */
    void close() {
        if (empty()) {
            host.showBack(MainActivity.PANEL_MAIL);
            return;
        }

        new AlertDialog.Builder(host)
                .setMessage(R.string.compose_discard_confirm)
                .setPositiveButton(
                        R.string.compose_discard,
                        (dialog, which) -> host.showBack(MainActivity.PANEL_MAIL))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Whether every field of the composer is still blank. */
    private boolean empty() {
        for (int id : RECIPIENTS) {
            if (!recipients(id).isEmpty()) {
                return false;
            }
        }
        return value(R.id.compose_subject).isEmpty() && value(R.id.compose_body).isEmpty();
    }

    /**
     * Composes what is on screen and puts it in the outbox.
     *
     * <p>Nothing is handed over here, and that is the point: composing
     * reaches for nothing, so a message can be written and sent with the
     * radio off, and the sync that follows is what carries it out. What
     * the composer waits for is a disk write.
     *
     * <p>The two stamps are minted here rather than in the bridge, which
     * keeps the composition a pure function of what it is handed: the
     * date is this device's clock and the identifier is a fresh one under
     * the sender's own domain, which is what RFC 5322 section 3.6.4 asks
     * of whoever mints it.
     */
    void send() {
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
        String messageId = messageId(account.email);
        String date = now();
        String to = recipients(R.id.compose_to);
        String subject = value(R.id.compose_subject);

        String draft;
        try {
            draft =
                    new JSONObject()
                            .put("from", account.email)
                            .put("to", to)
                            .put("cc", recipients(R.id.compose_cc))
                            .put("bcc", recipients(R.id.compose_bcc))
                            .put("subject", subject)
                            .put("body", value(R.id.compose_body))
                            .put("date", date)
                            .put("messageId", messageId)
                            .toString();
        } catch (JSONException error) {
            host.showError(error, R.string.compose_failed);
            return;
        }

        AccountEntry sender = account;
        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
        host.io.execute(
                () -> {
                    Exception failure = null;
                    try {
                        byte[] source = host.client.composeMessage(draft);
                        // The `Message-ID` is the identity, which is what
                        // the payload names it by: it is minted here and
                        // stamped on the message, so the copy the drain
                        // files in the sent mailbox comes back under the
                        // same name.
                        host.mail.queueSubmission(
                                sender.email,
                                PimdirSummary.bare(messageId),
                                subject,
                                PimdirSummary.mailSortKey(date),
                                source);
                    } catch (Exception error) {
                        Log.w("pimalaya", "compose failed: " + sender.email, error);
                        failure = error;
                    }

                    Exception outcome = failure;
                    host.postAlive(
                            () -> {
                                host.setAuthLoading(R.id.fab, R.id.fab_progress, false);
                                if (outcome != null) {
                                    host.showError(outcome, R.string.compose_failed);
                                    return;
                                }
                                host.toast(host.getString(R.string.compose_queued));
                                host.mailList.reload();
                                host.showBack(MainActivity.PANEL_MAIL);
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

    /** Now, as the date RFC 5322 section 3.3 writes. */
    private static String now() {
        java.text.SimpleDateFormat format =
                new java.text.SimpleDateFormat(DATE_FORMAT, Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new java.util.Date());
    }

    /**
     * A fresh `Message-ID` under the sender's own domain, which is the
     * one RFC 5322 section 3.6.4 says whoever mints it should use.
     */
    private static String messageId(String email) {
        int at = email.lastIndexOf('@');
        String domain = at < 0 ? "pimalaya.android" : email.substring(at + 1);
        return "<" + UUID.randomUUID() + "@" + domain + ">";
    }
}
