package org.pimalaya;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The mail screen: one list merging every mailbox of every account,
 * newest first.
 *
 * <p>This is the view the plan is really about. Plain IMAP cannot give
 * it: a client selects one mailbox at a time, so a cross-mailbox,
 * cross-account list has to be assembled locally. Here the store holds
 * every account's envelope spines in one table and the list is a single
 * descending scan over it, with the account and mailbox shown per row
 * and filterable on both axes.
 */
final class MailList {
    /** How many rows the merged list holds at once. */
    private static final int PAGE = 500;

    private final MainActivity host;
    private final MailStore store;
    private final Adapter adapter = new Adapter();
    private final List<MailStore.StoredMessage> rows = new ArrayList<>();

    MailList(MainActivity host, MailStore store) {
        this.host = host;
        this.store = store;
    }

    void setUp() {
        ListView list = host.findViewById(R.id.mail_list);
        list.setAdapter(adapter);
        list.setOnItemClickListener(
                (parent, view, position, id) -> host.messageView.open(rows.get(position)));

        androidx.swiperefreshlayout.widget.SwipeRefreshLayout refresh =
                host.findViewById(R.id.mail_refresh);
        refresh.setColorSchemeColors(host.ui.resolveColor(android.R.attr.colorAccent));
        refresh.setOnRefreshListener(
                () -> {
                    refresh.setRefreshing(false);
                    host.syncMail();
                });
    }

    /** Rebuilds the list from the store, dropping what the filter hides. */
    void reload() {
        rows.clear();
        for (MailStore.StoredMessage message : store.loadMerged(PAGE)) {
            if (host.filter.accepts(message.accountEmail, message.mailbox)) {
                rows.add(message);
            }
        }

        adapter.notifyDataSetChanged();
        host.findViewById(R.id.mail_empty)
                .setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            View view = recycled;
            if (view == null) {
                view = LayoutInflater.from(host).inflate(R.layout.item_message, parent, false);
            }

            MailStore.StoredMessage message = rows.get(position);
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
            // Which day rather than which date: scrolling an inbox is
            // asking how old a message is, and "Yesterday" answers that
            // where "8 Aug 2026" leaves the reader to work it out. The
            // exact date is one tap away, in the message's own header.
            date.setText(Dates.day(host, message.stamp));
            ((TextView) view.findViewById(R.id.message_origin))
                    .setText(message.mailbox + " · " + message.accountEmail);

            // The disc stands for the sender rather than the message, so
            // it is keyed by the address alone: a sender who changes how
            // their name is spelled keeps the colour the eye learned.
            TextView avatar = view.findViewById(R.id.message_avatar);
            avatar.setText(Avatar.letter(message.fromAddress));
            avatar.setBackground(Avatar.circle(message.fromAddress));

            // Unread is carried by weight alone, across every line of the
            // row including the date (as the Compose client does): a
            // merged list is busy enough without a second colour
            // competing with the accent the flags already use.
            int weight = message.seen ? Typeface.NORMAL : Typeface.BOLD;
            subject.setTypeface(null, weight);
            from.setTypeface(null, weight);
            date.setTypeface(null, weight);

            bindFlags(view, message);

            return view;
        }
    }

    /** Shows the flags the message carries, and hides the strip with none. */
    private void bindFlags(View view, MailStore.StoredMessage message) {
        view.findViewById(R.id.message_answered)
                .setVisibility(message.answered ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.message_flagged)
                .setVisibility(message.flagged ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.message_flags)
                .setVisibility(message.answered || message.flagged ? View.VISIBLE : View.GONE);

        // NOTE: invisible rather than gone, since the leading column is
        // what puts every row's avatar under the bar title; a row that
        // gave it up would sit 48dp to the left of its neighbours.
        view.findViewById(R.id.message_attachment)
                .setVisibility(message.hasAttachment ? View.VISIBLE : View.INVISIBLE);
    }
}
