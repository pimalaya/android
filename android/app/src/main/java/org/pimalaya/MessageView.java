package org.pimalaya;

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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.pimalaya.client.MessageBody;

import java.util.ArrayList;
import java.util.List;

/**
 * The reader for one message: the header card, then the message itself.
 *
 * <p>The card is drawn from the list row the reader tapped, so the
 * screen is never blank: the subject, the sender and the date are
 * already in the store, and only the body and the recipients have to be
 * fetched. Nothing is cached: a message body is fetched each time it is
 * opened, which the merged list's spine-only store makes the honest
 * default until bodies have somewhere to live.
 *
 * <p>An HTML body renders in a web view with scripting off, network
 * loads blocked and images not loaded at all. That is the whole point
 * of showing HTML rather than the text alternative: a remote image is a
 * read receipt the sender did not ask permission for, and a script in a
 * message has no business running.
 */
final class MessageView {
    /** How wide a badge row may run: the card's own inner width. */
    private static final int CARD_INSET = 64;

    private final MainActivity host;

    /** The row the reader opened, and what the header is drawn from. */
    private MailStore.StoredMessage current;

    MessageView(MainActivity host) {
        this.host = host;
    }

    /** Opens the reader on one row, then fetches what it does not hold. */
    void open(MailStore.StoredMessage message) {
        current = message;

        header(message);
        badges(new ArrayList<>());
        state(host.getString(R.string.message_loading));
        host.show(MainActivity.PANEL_MESSAGE);

        load(message);
    }

    /** What the bar titles itself with while the reader is up. */
    String title() {
        if (current == null) {
            return "";
        }
        return current.sender().isEmpty()
                ? host.getString(R.string.message_no_sender)
                : current.sender();
    }

    /** The header the store already knows, drawn before anything loads. */
    private void header(MailStore.StoredMessage message) {
        TextView avatar = host.findViewById(R.id.message_view_avatar);
        avatar.setText(Avatar.letter(message.fromAddress));
        avatar.setBackground(Avatar.circle(message.fromAddress));

        ((TextView) host.findViewById(R.id.message_view_subject))
                .setText(
                        message.subject.isEmpty()
                                ? host.getString(R.string.message_no_subject)
                                : message.subject);
        ((TextView) host.findViewById(R.id.message_view_from)).setText(sender(message));
        ((TextView) host.findViewById(R.id.message_view_recipients))
                .setText(message.mailbox + " · " + message.accountEmail);
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

    /** Fetches the message, then fills in what the row could not say. */
    private void load(MailStore.StoredMessage message) {
        AccountEntry account = accountOf(message.accountEmail);
        if (account == null) {
            state(host.getString(R.string.message_no_account));
            return;
        }

        host.io.execute(
                () -> {
                    MessageBody loaded = null;
                    Exception failure = null;
                    try {
                        loaded =
                                host.runner
                                        .session(account, PimDomain.MAIL)
                                        .call(
                                                server ->
                                                        host.client.fetchMessage(
                                                                server.baseUrl,
                                                                server.login,
                                                                server.password,
                                                                message.mailbox,
                                                                message.id));
                    } catch (Exception error) {
                        Log.w("pimalaya", "message fetch failed: " + message.id, error);
                        failure = error;
                    }

                    MessageBody outcome = loaded;
                    Exception error = failure;
                    host.postAlive(
                            () -> {
                                // NOTE: the reader may have left while the
                                // fetch ran, and a second message may
                                // already be on screen.
                                if (current != message) {
                                    return;
                                }
                                if (outcome == null) {
                                    state(host.message(error, R.string.message_failed));
                                } else {
                                    render(outcome);
                                }
                            });
                });
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
        if (!message.to.isEmpty()) {
            ((TextView) host.findViewById(R.id.message_view_recipients))
                    .setText(recipients(message));
        }

        long stamp = MailDate.toStamp(message.date);
        if (stamp > 0) {
            date(stamp);
        }

        badges(message.attachments);

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
    }

    /** Shows a word in the body's place: loading, empty, or a failure. */
    private void state(String message) {
        TextView state = host.findViewById(R.id.message_view_state);
        state.setText(message);
        state.setVisibility(View.VISIBLE);

        clearBody().setVisibility(View.GONE);
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
    private void badges(List<MessageBody.Attachment> attachments) {
        LinearLayout container = host.findViewById(R.id.message_view_attachments);
        container.removeAllViews();
        container.setVisibility(attachments.isEmpty() ? View.GONE : View.VISIBLE);

        int available =
                host.getResources().getDisplayMetrics().widthPixels - host.dp(CARD_INSET);
        int free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);

        LinearLayout row = null;
        int used = 0;
        for (MessageBody.Attachment attachment : attachments) {
            View badge = badge(attachment);
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
    private View badge(MessageBody.Attachment attachment) {
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
    private String name(MessageBody.Attachment attachment) {
        String name =
                attachment.name.isEmpty()
                        ? host.getString(R.string.message_attachment_unnamed)
                        : attachment.name;
        return attachment.size <= 0 ? name : name + " · " + size(attachment.size);
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
