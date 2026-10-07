package org.pimalaya;

import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
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
 * screen narrows what it shows without touching what syncs: a search
 * over the sender and the subject, and the two chips (unread,
 * attachments). A long press starts a selection the bar then acts on.
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

    /** The selected messages, keyed by their store id, in no order. */
    private final java.util.Set<String> selected = new java.util.HashSet<>();

    /** The rows on screen, what select-all and the actions walk. */
    private final List<MailStore.StoredMessage> shown = new ArrayList<>();

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
        header.empty(host.findViewById(R.id.mail_empty));
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
                    if (message == null) {
                        return;
                    }
                    if (isSelectionMode()) {
                        toggle(message);
                    } else {
                        host.messageView.open(message);
                    }
                });
        list.setOnItemLongClickListener(
                (parent, view, position, id) -> {
                    MailStore.StoredMessage message = sections.row(header.rowAt(position));
                    if (message == null) {
                        return false;
                    }
                    toggle(message);
                    return true;
                });

        host.findViewById(R.id.mail_select_seen).setOnClickListener(view -> markSelected());
        host.findViewById(R.id.mail_select_flag).setOnClickListener(view -> flagSelected());
        host.findViewById(R.id.mail_select_delete).setOnClickListener(view -> deleteSelected());

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

        shown.clear();
        shown.addAll(rows);
        // NOTE: a selected message the list no longer shows (deleted,
        // filtered out) leaves the selection with it.
        java.util.Set<String> kept = new java.util.HashSet<>();
        for (MailStore.StoredMessage message : rows) {
            if (selected.contains(keyOf(message))) {
                kept.add(keyOf(message));
            }
        }
        selected.retainAll(kept);
        sections.fill(rows, this::sectionOf);
        adapter.notifyDataSetChanged();
        updateSelectionUi();

        android.content.res.Resources resources = host.getResources();
        header.meta(
                unread > 0
                        ? resources.getQuantityString(
                                R.plurals.mail_meta_unread, unread, unread, rows.size())
                        : resources.getQuantityString(R.plurals.mail_meta, rows.size(), rows.size()));
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
            view.findViewById(R.id.message_divider)
                    .setVisibility(sections.opensCard(position) ? View.GONE : View.VISIBLE);

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
            boolean checked = selected.contains(keyOf(message));
            avatar.setVisibility(checked ? View.INVISIBLE : View.VISIBLE);
            ImageView check = view.findViewById(R.id.message_check);
            check.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked) {
                check.setBackground(host.getDrawable(R.drawable.unread_dot));
                check.setImageTintList(
                        android.content.res.ColorStateList.valueOf(host.accentContrast()));
            }

            // Unread is carried by the dot, the weight of the sender and
            // the subject, and the time taking the accent.
            boolean unread = !message.seen && !message.pending;
            view.findViewById(R.id.message_dot)
                    .setVisibility(unread ? View.VISIBLE : View.GONE);
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
     * The marks: the star of an important message, the paperclip of one
     * carrying an attachment, and the replied mark. Shown only when they
     * hold, so the subject's line ends in as few glyphs as it can.
     */
    private void bindMarks(View view, MailStore.StoredMessage message) {
        view.findViewById(R.id.message_star)
                .setVisibility(message.flagged ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.message_attachment)
                .setVisibility(message.hasAttachment ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.message_answered)
                .setVisibility(message.answered ? View.VISIBLE : View.GONE);
    }

    // ---- the selection ----------------------------------------------------

    boolean isSelectionMode() {
        return !selected.isEmpty();
    }

    /** What a message is known by in the selection, across accounts. */
    private static String keyOf(MailStore.StoredMessage message) {
        return message.collection + "/" + message.id;
    }

    /** Adds a message to the selection, or takes it out. */
    private void toggle(MailStore.StoredMessage message) {
        if (!selected.remove(keyOf(message))) {
            selected.add(keyOf(message));
        }
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    void exitSelection() {
        selected.clear();
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    /** Selects every shown message, or clears them when all already are. */
    void toggleSelectAll() {
        boolean all = selected.size() == shown.size();
        selected.clear();
        if (!all) {
            for (MailStore.StoredMessage message : shown) {
                selected.add(keyOf(message));
            }
        }
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    /** The selected messages, in list order. */
    private List<MailStore.StoredMessage> selection() {
        List<MailStore.StoredMessage> messages = new ArrayList<>();
        for (MailStore.StoredMessage message : shown) {
            if (selected.contains(keyOf(message))) {
                messages.add(message);
            }
        }
        return messages;
    }

    /**
     * The bar during a selection: the count where the title was, then
     * read, star and delete over the whole of it, and select-all. Each
     * toggle goes the way that changes something: read while any is
     * unread, star while any is not starred.
     */
    void updateSelectionUi() {
        if (host.screen != MainActivity.PANEL_MAIL) {
            return;
        }
        boolean selecting = isSelectionMode();
        TextView title = host.findViewById(R.id.bar_title);
        if (selecting) {
            title.animate().cancel();
            title.setText(host.getString(R.string.selected_count, selected.size()));
            title.setAlpha(1f);
        } else {
            host.showDomainTitle(MainActivity.PANEL_MAIL);
        }
        host.findViewById(R.id.bar_menu).setVisibility(selecting ? View.GONE : View.VISIBLE);
        host.findViewById(R.id.bar_filter).setVisibility(selecting ? View.GONE : View.VISIBLE);
        host.findViewById(R.id.selection_close)
                .setVisibility(selecting ? View.VISIBLE : View.GONE);
        host.findViewById(R.id.selection_all_slot)
                .setVisibility(selecting ? View.VISIBLE : View.GONE);
        ((android.widget.CheckBox) host.findViewById(R.id.selection_all))
                .setChecked(selecting && selected.size() == shown.size());
        host.findViewById(R.id.fab_extended).setVisibility(selecting ? View.GONE : View.VISIBLE);

        boolean anyUnread = false;
        boolean anyPlain = false;
        for (MailStore.StoredMessage message : selection()) {
            anyUnread |= !message.seen && !message.pending;
            anyPlain |= !message.flagged && !message.pending;
        }
        ImageButton seen = host.findViewById(R.id.mail_select_seen);
        seen.setVisibility(selecting ? View.VISIBLE : View.GONE);
        seen.setImageResource(anyUnread ? R.drawable.ic_check : R.drawable.ic_visibility_off);
        seen.setContentDescription(
                host.getString(
                        anyUnread ? R.string.message_mark_read : R.string.message_mark_unread));
        ImageButton flag = host.findViewById(R.id.mail_select_flag);
        flag.setVisibility(selecting ? View.VISIBLE : View.GONE);
        flag.setImageResource(anyPlain ? R.drawable.ic_star : R.drawable.ic_star_filled);
        flag.setContentDescription(
                host.getString(anyPlain ? R.string.message_flag : R.string.message_unflag));
        host.findViewById(R.id.mail_select_delete)
                .setVisibility(selecting ? View.VISIBLE : View.GONE);
    }

    /** Marks the selection read, or unread when it all already is. */
    private void markSelected() {
        List<MailStore.StoredMessage> messages = selection();
        boolean anyUnread = false;
        for (MailStore.StoredMessage message : messages) {
            anyUnread |= !message.seen && !message.pending;
        }
        host.messageView.stageFlag(messages, MailEngine.SEEN, anyUnread, this::reload);
    }

    /** Stars the selection, or unstars it when it all already is. */
    private void flagSelected() {
        List<MailStore.StoredMessage> messages = selection();
        boolean anyPlain = false;
        for (MailStore.StoredMessage message : messages) {
            anyPlain |= !message.flagged && !message.pending;
        }
        host.messageView.stageFlag(messages, MailEngine.FLAGGED, anyPlain, this::reload);
    }

    /** Asks, then stages the selection's deletion. */
    private void deleteSelected() {
        List<MailStore.StoredMessage> messages = selection();
        new android.app.AlertDialog.Builder(host)
                .setMessage(
                        host.getResources()
                                .getQuantityString(
                                        R.plurals.messages_delete_confirm,
                                        messages.size(),
                                        messages.size()))
                .setPositiveButton(
                        R.string.message_delete,
                        (dialog, which) ->
                                host.messageView.stageDelete(messages, this::exitSelection))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
