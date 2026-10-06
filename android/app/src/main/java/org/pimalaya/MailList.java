package org.pimalaya;

import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The mail screen: one list merging every mailbox of every account,
 * newest first, grouped into one card per day.
 *
 * <p>This is the view the plan is really about. Plain IMAP cannot give
 * it: a client selects one mailbox at a time, so a cross-mailbox,
 * cross-account list has to be assembled locally. Here the store holds
 * every account's envelope spines in one table and the list is a single
 * descending scan over it, with the account and mailbox shown per row
 * and filterable on both axes.
 *
 * <p>On top of the merged filter, which also decides what syncs, the
 * screen narrows what it shows without touching what syncs: a mailbox
 * picked in the drawer, a search over the sender and the subject, and
 * the two chips (unread, attachments).
 */
final class MailList {
    /** How many rows the merged list holds at once. */
    private static final int PAGE = 500;

    private final MainActivity host;
    private final MailStore store;
    private final Adapter adapter = new Adapter();
    private final CardSections<MailStore.StoredMessage> sections = new CardSections<>();

    /** The page the merged filter lets through, before the view narrows it. */
    private final List<MailStore.StoredMessage> page = new ArrayList<>();

    private ListHeader header;

    /** The mailbox the drawer narrowed the list to, null for all of them. */
    private String mailbox;

    private String query = "";
    private boolean unreadOnly;
    private boolean attachmentsOnly;

    MailList(MainActivity host, MailStore store) {
        this.host = host;
        this.store = store;
    }

    void setUp() {
        ListView list = host.findViewById(R.id.mail_list);
        header = new ListHeader(host, list, MainActivity.PANEL_MAIL);
        header.search(
                R.string.mail_search,
                settled -> {
                    query = settled;
                    render();
                });
        LinearLayout chips = header.chips();
        Chips.add(
                host,
                chips,
                R.string.mail_chip_unread,
                R.drawable.ic_visibility_off,
                on -> {
                    unreadOnly = on;
                    render();
                });
        Chips.add(
                host,
                chips,
                R.string.mail_chip_attachments,
                R.drawable.ic_attach_file,
                on -> {
                    attachmentsOnly = on;
                    render();
                });

        list.setAdapter(adapter);
        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    MailStore.StoredMessage message = sections.row(header.rowAt(position));
                    if (message != null) {
                        host.messageView.open(message);
                    }
                });

        androidx.swiperefreshlayout.widget.SwipeRefreshLayout refresh =
                host.findViewById(R.id.mail_refresh);
        refresh.setColorSchemeColors(host.ui.resolveColor(android.R.attr.colorAccent));
        refresh.setOnRefreshListener(
                () -> {
                    refresh.setRefreshing(false);
                    host.syncMail();
                });
    }

    ListHeader header() {
        return header;
    }

    /** The mailbox the list is narrowed to, null for all of them. */
    String mailbox() {
        return mailbox;
    }

    /** Narrows the list to one mailbox, or widens it back with null. */
    void showMailbox(String mailbox) {
        this.mailbox = mailbox;
        render();
    }

    /** Rebuilds the list from the store, dropping what the filter hides. */
    void reload() {
        page.clear();
        int unread = 0;
        for (MailStore.StoredMessage message : store.loadMerged(PAGE)) {
            if (host.filter.accepts(message.accountEmail, message.mailbox)) {
                page.add(message);
                if (!message.seen && !message.pending) {
                    unread++;
                }
            }
        }

        // NOTE: counted over the page the list holds, not the whole
        // store: the badge says there is something new to read, which
        // the newest few hundred messages answer.
        host.showMailBadge(unread);
        render();
    }

    /** Narrows the page to what the screen asks for, and lays it out. */
    private void render() {
        List<MailStore.StoredMessage> rows = new ArrayList<>();
        int unread = 0;
        for (MailStore.StoredMessage message : page) {
            if (mailbox != null && !mailbox.equals(message.mailbox)) {
                continue;
            }
            if (unreadOnly && message.seen) {
                continue;
            }
            if (attachmentsOnly && !message.hasAttachment) {
                continue;
            }
            if (!query.isEmpty() && !matches(message)) {
                continue;
            }
            rows.add(message);
            if (!message.seen && !message.pending) {
                unread++;
            }
        }

        sections.fill(rows, this::sectionOf);
        adapter.notifyDataSetChanged();

        String title = mailbox != null ? mailbox : host.getString(R.string.mail_title);
        header.title(title);
        android.content.res.Resources resources = host.getResources();
        header.meta(
                unread > 0
                        ? resources.getQuantityString(
                                R.plurals.mail_meta_unread, unread, unread, rows.size())
                        : resources.getQuantityString(R.plurals.mail_meta, rows.size(), rows.size()));
        if (host.screen == MainActivity.PANEL_MAIL) {
            host.updateBarTitle(title);
        }
        host.findViewById(R.id.mail_empty)
                .setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Whether the search finds the message in its sender or subject. */
    private boolean matches(MailStore.StoredMessage message) {
        return (message.subject + " " + message.fromName + " " + message.fromAddress)
                .toLowerCase()
                .contains(query);
    }

    /** The day a message falls on, which is the card it goes in. */
    private String sectionOf(MailStore.StoredMessage message) {
        String day = Dates.day(host, message.stamp);
        return day.isEmpty() ? host.getString(R.string.date_unknown) : day;
    }

    /**
     * When a row says it arrived: the time inside a card of today or
     * yesterday, where the header already names the day, and the date
     * past that, where the header only says how long ago.
     */
    private String timeOf(MailStore.StoredMessage message) {
        if (message.stamp <= 0) {
            return "";
        }
        String day = Dates.day(host, message.stamp);
        if (day.equals(host.getString(R.string.date_today))
                || day.equals(host.getString(R.string.date_yesterday))) {
            return DateFormat.getTimeFormat(host).format(new Date(message.stamp));
        }
        return Dates.date(host, message.stamp);
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return sections.size();
        }

        @Override
        public Object getItem(int position) {
            return sections.row(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return sections.isHeader(position) ? 1 : 0;
        }

        @Override
        public boolean isEnabled(int position) {
            return !sections.isHeader(position);
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            if (sections.isHeader(position)) {
                return sections.headerView(position, recycled, parent);
            }

            View view = recycled;
            if (view == null) {
                view = LayoutInflater.from(host).inflate(R.layout.item_message, parent, false);
            }
            sections.shape(view, position);

            MailStore.StoredMessage message = sections.row(position);
            TextView subject = view.findViewById(R.id.message_subject);
            TextView from = view.findViewById(R.id.message_from);
            TextView date = view.findViewById(R.id.message_date);

            subject.setText(
                    message.subject.isEmpty()
                            ? host.getString(R.string.message_no_subject)
                            : message.subject);
            from.setText(
                    message.sender().isEmpty()
                            ? host.getString(R.string.message_no_sender)
                            : message.sender());
            date.setText(timeOf(message));
            // A message waiting to go out says so where a message that has
            // been somewhere says which mailbox: naming the outbox would
            // answer where it is, and what the reader is asking is whether
            // it has gone. One the server refused says that instead, which
            // is the same question answered for good.
            ((TextView) view.findViewById(R.id.message_origin))
                    .setText(state(message) + " · " + message.accountEmail);

            // The disc stands for the sender rather than the message, so
            // it is keyed by the address alone: a sender who changes how
            // their name is spelled keeps the colour the eye learned.
            TextView avatar = view.findViewById(R.id.message_avatar);
            avatar.setText(Avatar.letter(message.fromAddress));
            avatar.setBackground(Avatar.circle(host, message.fromAddress));

            // Unread is carried by the dot, the weight of the sender and
            // the subject, and the time taking the accent.
            boolean unread = !message.seen && !message.pending;
            view.findViewById(R.id.message_dot)
                    .setVisibility(unread ? View.VISIBLE : View.INVISIBLE);
            from.setTypeface(null, unread ? Typeface.BOLD : Typeface.NORMAL);
            subject.setTypeface(null, unread ? Typeface.BOLD : Typeface.NORMAL);
            date.setTextColor(
                    host.ui.resolveColor(
                            unread
                                    ? android.R.attr.colorAccent
                                    : android.R.attr.textColorSecondary));

            bindMarks(view, message);

            return view;
        }
    }

    /** What a row says of where its message stands. */
    private String state(MailStore.StoredMessage message) {
        if (message.failed) {
            return host.getString(R.string.message_send_failed);
        }
        return message.pending ? host.getString(R.string.message_pending) : message.mailbox;
    }

    /**
     * The star toggle, the replied mark and the attachment chip. A
     * message still in the outbox has no markers to move, so it gets no
     * star.
     */
    private void bindMarks(View view, MailStore.StoredMessage message) {
        ImageButton star = view.findViewById(R.id.message_star);
        star.setVisibility(message.pending ? View.INVISIBLE : View.VISIBLE);
        star.setContentDescription(
                host.getString(message.flagged ? R.string.message_unflag : R.string.message_flag));
        star.setColorFilter(
                host.ui.resolveColor(
                        message.flagged
                                ? android.R.attr.colorAccent
                                : android.R.attr.textColorSecondary));
        star.setAlpha(message.flagged ? 1f : 0.5f);
        star.setOnClickListener(
                button ->
                        host.messageView.stageFlag(
                                message, MailEngine.FLAGGED, !message.flagged, this::reload));

        view.findViewById(R.id.message_answered)
                .setVisibility(message.answered ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.message_attachment)
                .setVisibility(message.hasAttachment ? View.VISIBLE : View.GONE);
    }
}
