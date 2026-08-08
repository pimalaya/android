package org.pimalaya;

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

    private String dateLabel(long stamp) {
        if (stamp <= 0) {
            return "";
        }
        java.util.Date date = new java.util.Date(stamp);
        java.util.Calendar today = java.util.Calendar.getInstance();
        java.util.Calendar then = java.util.Calendar.getInstance();
        then.setTime(date);

        // Same day shows a time, anything older a date: the usual mail
        // client convention, and it keeps the column narrow.
        boolean sameDay =
                today.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)
                        && today.get(java.util.Calendar.DAY_OF_YEAR)
                                == then.get(java.util.Calendar.DAY_OF_YEAR);
        return sameDay
                ? android.text.format.DateFormat.getTimeFormat(host).format(date)
                : android.text.format.DateFormat.getMediumDateFormat(host).format(date);
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
            TextView from = view.findViewById(R.id.message_from);
            TextView subject = view.findViewById(R.id.message_subject);

            from.setText(
                    message.from.isEmpty()
                            ? host.getString(R.string.message_no_sender)
                            : message.from);
            subject.setText(
                    message.subject.isEmpty()
                            ? host.getString(R.string.message_no_subject)
                            : message.subject);

            // Unread is carried by weight alone: a merged list is busy
            // enough without a second colour competing with the accent.
            int weight = message.seen ? android.graphics.Typeface.NORMAL
                    : android.graphics.Typeface.BOLD;
            from.setTypeface(null, weight);
            subject.setTypeface(null, weight);

            ((TextView) view.findViewById(R.id.message_date))
                    .setText(dateLabel(message.stamp));
            ((TextView) view.findViewById(R.id.message_origin))
                    .setText(message.mailbox + " · " + message.accountEmail);

            return view;
        }
    }
}
