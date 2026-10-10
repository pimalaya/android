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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * The mail screen: one list merging every mailbox of every account,
 * newest first, grouped into one card per day.
 *
 * <p>This is the view the plan is really about. Plain IMAP cannot give
 * it: a client selects one mailbox at a time, so a cross-mailbox,
 * cross-account list has to be assembled locally. Here the store holds
 * every account's mailboxes whole, and the list is one descending scan
 * over them, with the account and mailbox shown per row and filterable on
 * both axes.
 *
 * <p>It shows the windows ({@link MailWindow}): down to its floor, the
 * most recent of the shown accounts' windows and of the shown mailboxes'
 * coverage, so what it shows is on the phone, and it ends on a footer
 * moving the windows back. It loads lazily. Its size is a count of what
 * the filter, the chips, the search and the floor let through, its day
 * headers are placed from one count per day, and its rows are read a page
 * at a time around the scroll position, far pages evicted, a placeholder
 * standing in for a row whose page is still on its way. Search, the chips,
 * the floor and the unread badge are conditions of the store's query,
 * search covering every stored message and the badge each account's
 * window; none of them changes what syncs, which the filter alone decides. The
 * messages waiting to go out stay on top, outside the paged query. A long
 * press starts a selection the bar then acts on, keyed by store id, and
 * selecting all is a query rather than a walk over rows in memory.
 */
final class MailList {
    /** How many rows one page read brings in. */
    private static final int PAGE = 50;

    /** How many pages are kept around the scroll position. */
    private static final int CACHED_PAGES = 24;

    /** How long a burst of sync writes is let settle before the list redraws. */
    private static final long SETTLE_MILLIS = 400;

    /**
     * The roles the chips give direct access to, pimdir's mail roles (STORAGE
     * section 14), each every shown account's mailbox of that role.
     */
    private static final String[] ROLES = {"inbox", "sent", "drafts", "trash", "junk", "archive"};

    private static final int[] ROLE_LABELS = {
        R.string.mail_chip_inbox,
        R.string.mail_chip_sent,
        R.string.mail_chip_drafts,
        R.string.mail_chip_trash,
        R.string.mail_chip_junk,
        R.string.mail_chip_archive,
    };

    /** The chip label of a mail role, or 0 for a role no chip names. */
    static int roleLabel(String role) {
        for (int index = 0; index < ROLES.length; index++) {
            if (ROLES[index].equals(role)) {
                return ROLE_LABELS[index];
            }
        }
        return 0;
    }

    private static final int[] ROLE_ICONS = {
        R.drawable.ic_domain_mail,
        R.drawable.ic_send,
        R.drawable.ic_edit_note,
        R.drawable.ic_delete_bar,
        R.drawable.ic_cancel,
        R.drawable.ic_folder_open,
    };

    /**
     * What a list shows under a role chip: the collections {@code accepts}
     * lets through, narrowed to those holding {@code role} ({@code roles} by
     * collection id), all of them with no chip on. The outbox holds no role,
     * so a chip leaves it out.
     */
    static BiPredicate<String, String> narrowed(
            BiPredicate<String, String> accepts, String role, Map<String, String> roles) {
        if (role == null) {
            return accepts;
        }
        return (account, collection) ->
                accepts.test(account, collection) && role.equals(roles.get(collection));
    }

    private final MainActivity host;
    private final MailStore store;
    private final Adapter adapter = new Adapter();

    /** One reader, so pages and counts never race each other. */
    private final ExecutorService reads = Executors.newSingleThreadExecutor();

    /** What the list shows now; replaced whole by each reload. */
    private Layout layout = Layout.empty();

    /** The query the current layout was read under; null before the first. */
    private MailStore.Query currentQuery;

    /** Bumped by every reload, so an answer to an older one is dropped. */
    private int generation;

    /** The pages read, by index, the least recently drawn dropped first. */
    private final Map<Integer, List<MailStore.StoredMessage>> pages =
            new LinkedHashMap<Integer, List<MailStore.StoredMessage>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<Integer, List<MailStore.StoredMessage>> eldest) {
                    return size() > CACHED_PAGES;
                }
            };

    /** The pages on their way, so a row drawn twice asks once. */
    private final Set<Integer> loading = new HashSet<>();

    private ListHeader header;

    private String query = "";

    /** The role chip on, pimdir's mail role it narrows to, null for none. */
    private String role;

    private boolean unreadOnly;
    private boolean attachmentsOnly;

    /** The messages selected one by one, keyed by their store id. */
    private final Map<String, MailStore.StoredMessage> selected = new LinkedHashMap<>();

    /**
     * Whether everything the query lets through is selected, the rows in
     * {@link #excluded} aside: a query rather than a set of rows.
     */
    private boolean allSelected;

    private final Set<String> excluded = new HashSet<>();

    /** A reload asked for by a sync write, held until the burst settles. */
    private final Runnable settled = this::reload;

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
                    reload();
                });
        LinearLayout chips = header.chips();
        Chips.exclusive(
                host,
                chips,
                ROLE_LABELS,
                ROLE_ICONS,
                picked -> {
                    role = picked < 0 ? null : ROLES[picked];
                    reload();
                });
        Chips.add(
                host,
                chips,
                R.string.mail_chip_unread,
                R.drawable.ic_visibility_off,
                on -> {
                    unreadOnly = on;
                    reload();
                });
        Chips.add(
                host,
                chips,
                R.string.mail_chip_attachments,
                R.drawable.ic_attach_file,
                on -> {
                    attachmentsOnly = on;
                    reload();
                });

        list.setAdapter(adapter);
        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    MailStore.StoredMessage message = messageAt(header.rowAt(position));
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
                    MailStore.StoredMessage message = messageAt(header.rowAt(position));
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

        // NOTE: a first pass lands its pages one write at a time, and the
        // top of the list is what has to show while the rest fills in.
        MailEngine.onWrite =
                () ->
                        host.main.post(
                                () -> {
                                    host.main.removeCallbacks(settled);
                                    host.main.postDelayed(settled, SETTLE_MILLIS);
                                });
    }

    /** The list's one reader, for a read that has to stay off the sync's thread. */
    java.util.concurrent.Executor reads() {
        return reads;
    }

    ListHeader header() {
        return header;
    }

    // ---- the layout -------------------------------------------------------

    /**
     * Reads the list's shape from the store again: the counts that size
     * it, the per-day counts that place its headers, the badge, and the
     * pages around where the list stands, then swaps it in whole, so what
     * is on screen never flashes through placeholders.
     */
    void reload() {
        int asked = ++generation;
        ListView list = host.findViewById(R.id.mail_list);
        int around = Math.max(0, header.rowAt(list.getFirstVisiblePosition()));
        MergedFilter filter = host.filterOf(PimDomain.MAIL);
        String narrowing = role;
        String words = query;
        boolean unread = unreadOnly;
        boolean attachments = attachmentsOnly;
        String outboxLabel = store.outboxName();
        String undated = host.getString(R.string.date_unknown);
        boolean metered = host.metered();

        reads.execute(
                () -> {
                    Map<String, String> roles = store.roles();
                    BiPredicate<String, String> shown = narrowed(filter::accepts, narrowing, roles);
                    MailStore.Query wanted = store.query(shown, unread, attachments, words);
                    List<MailStore.Edge> edges = store.edges();

                    Map<String, String> windows = new HashMap<>();
                    for (MailStore.Edge edge : edges) {
                        if (!windows.containsKey(edge.accountEmail)) {
                            windows.put(edge.accountEmail, store.windowOf(edge.accountEmail));
                        }
                    }
                    long badge = badge(filter, windows);

                    // NOTE: a search covers every stored message; the list
                    // reaches down to its floor otherwise, ending on the
                    // footer that moves the windows back.
                    String floor = null;
                    Footer footer = null;
                    if (words.trim().isEmpty()) {
                        floor = store.floorOf(wanted, edges);
                        footer = footerOf(wanted, edges, floor);
                    }
                    MailStore.Query listed = wanted.since(floor);

                    List<MailStore.StoredMessage> outbox = new ArrayList<>();
                    for (MailStore.StoredMessage message : store.outgoing()) {
                        if (shown.test(message.accountEmail, message.collection)
                                && !unread
                                && !attachments
                                && matches(message, words)) {
                            outbox.add(message);
                        }
                    }

                    List<MailStore.Day> days = store.countByDay(listed, shift());
                    long shownUnread = store.count(listed.unread());
                    Layout next = Layout.of(host, outbox, outboxLabel, undated, days, shownUnread);
                    // NOTE: nothing shown and nothing older stored: the
                    // empty state alone, not a lone "Choose a date".
                    next.footer =
                            footer != null && footer.date == null && next.rows() == 0
                                    ? null
                                    : footer;
                    next.windows = windows;
                    next.roles = roles;
                    next.metered = metered;

                    // NOTE: the pages around where the list stands, read
                    // before the swap, so the rows on screen stay drawn.
                    Map<Integer, List<MailStore.StoredMessage>> read = new HashMap<>();
                    int first = Math.max(0, around - outbox.size()) / PAGE;
                    MailStore.StoredMessage after = null;
                    for (int page = Math.max(0, first - 1); page <= first + 1; page++) {
                        if ((long) page * PAGE >= next.synced) {
                            break;
                        }
                        List<MailStore.StoredMessage> rows =
                                store.page(listed, after, (long) page * PAGE, PAGE);
                        read.put(page, rows);
                        after = rows.size() < PAGE ? null : rows.get(rows.size() - 1);
                    }

                    host.main.post(
                            () -> {
                                if (asked != generation) {
                                    return;
                                }
                                currentQuery = listed;
                                layout = next;
                                pages.clear();
                                loading.clear();
                                pages.putAll(read);
                                host.showMailBadge((int) Math.min(Integer.MAX_VALUE, badge));
                                render();
                            });
                });
    }

    /** Draws the current layout: the rows, the line under the title, the empty state. */
    private void render() {
        adapter.notifyDataSetChanged();
        updateSelectionUi();

        android.content.res.Resources resources = host.getResources();
        int rows = (int) Math.min(Integer.MAX_VALUE, layout.rows());
        int unread = (int) Math.min(Integer.MAX_VALUE, layout.unread);
        header.meta(
                unread > 0
                        ? resources.getQuantityString(
                                R.plurals.mail_meta_unread, unread, unread, rows)
                        : resources.getQuantityString(R.plurals.mail_meta, rows, rows));
        // NOTE: an empty window over older mail shows its footer instead.
        host.findViewById(R.id.mail_empty)
                .setVisibility(rows == 0 && layout.footer == null ? View.VISIBLE : View.GONE);
    }

    // ---- the footer -------------------------------------------------------

    /**
     * What the list's footer offers, read with the layout: the date it
     * moves the windows back to (null for none, "Choose a date" alone),
     * what that takes in, the floor it reaches down from, whether shown
     * mailboxes still have headers to list below it, and the accounts whose
     * windows move.
     */
    private static final class Footer {
        final String date;
        final MailStore.Sum sum;
        final String floor;
        final boolean listing;
        final List<String> accounts;

        Footer(
                String date,
                MailStore.Sum sum,
                String floor,
                boolean listing,
                List<String> accounts) {
            this.date = date;
            this.sum = sum;
            this.floor = floor;
            this.listing = listing;
            this.accounts = accounts;
        }
    }

    /**
     * The footer under {@code wanted}, null for none: the next date
     * ({@link MailWindow#next}) when one is worth a tap, "Choose a date"
     * while a shown account is bounded. Only accounts showing a mailbox not
     * kept whole have a window to move.
     */
    private Footer footerOf(MailStore.Query wanted, List<MailStore.Edge> edges, String floor) {
        boolean listing = false;
        boolean bounded = false;
        List<String> accounts = new ArrayList<>();
        for (MailStore.Edge edge : edges) {
            if (!wanted.holds(edge.collection)) {
                continue;
            }
            listing |= edge.limit != null;
            if (!MailOffline.whole(host, edge.collection)
                    && !accounts.contains(edge.accountEmail)) {
                accounts.add(edge.accountEmail);
                bounded |= store.monthsOf(edge.accountEmail) > 0;
            }
        }
        if (accounts.isEmpty()) {
            return null;
        }
        String date =
                MailWindow.next(
                        store.newestBelow(wanted, floor),
                        floor,
                        listing,
                        java.time.ZoneId.systemDefault());
        if (date == null && !bounded) {
            return null;
        }
        MailStore.Sum sum =
                date == null ? null : store.sum(store.downloading(wanted), date, floor);
        return new Footer(date, sum, floor, listing, accounts);
    }

    /**
     * The badge: the unread mail of each account within its window, every
     * mailbox the filter shows, chips and search aside.
     */
    private long badge(MergedFilter filter, Map<String, String> windows) {
        long badge = 0;
        for (Map.Entry<String, String> window : windows.entrySet()) {
            String email = window.getKey();
            MailStore.Query mail =
                    store.query(
                            (account, collection) ->
                                    account.equals(email) && filter.accepts(account, collection),
                            false,
                            false,
                            "");
            badge += store.unread(mail.since(window.getValue()));
        }
        return badge;
    }

    /** The list's footer: the button, the line under it, and "Choose a date". */
    private View footer(View recycled, ViewGroup parent) {
        View view = recycled;
        if (view == null) {
            view = LayoutInflater.from(host).inflate(R.layout.item_mail_window, parent, false);
        }
        Footer footer = layout.footer;
        java.util.function.Predicate<String> shown = currentQuery::holds;

        android.widget.Button load = view.findViewById(R.id.mail_window_load);
        load.setVisibility(footer.date != null ? View.VISIBLE : View.GONE);
        TextView info = view.findViewById(R.id.mail_window_info);
        info.setVisibility(footer.date != null ? View.VISIBLE : View.GONE);
        if (footer.date != null) {
            load.setText(
                    host.getString(
                            R.string.mail_window_load, WindowPicker.label(host, footer.date)));
            load.setOnClickListener(
                    tapped -> WindowPicker.apply(host, footer.accounts, footer.date, shown, null));
            info.setText(
                    WindowPicker.info(
                            host, footer.sum, footer.listing, host.online(), host.metered()));
        }

        MailStore.Query query = currentQuery;
        view.findViewById(R.id.mail_window_choose)
                .setOnClickListener(
                        tapped ->
                                WindowPicker.open(
                                        host,
                                        reads,
                                        query,
                                        footer.floor,
                                        footer.accounts,
                                        shown,
                                        false,
                                        null));
        return view;
    }

    /**
     * The SQLite modifier moving a UTC instant onto the device's wall clock
     * today, so a day header falls where the reader's midnight does.
     */
    private static String shift() {
        int minutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000;
        return String.format(Locale.ROOT, "%+d minutes", minutes);
    }

    /** Whether the search finds a waiting message in its sender or subject. */
    private static boolean matches(MailStore.StoredMessage message, String words) {
        return words.isEmpty()
                || (message.subject + " " + message.fromName + " " + message.fromAddress)
                        .toLowerCase()
                        .contains(words);
    }

    /**
     * The list's shape: the waiting messages on top, then one section per
     * day label, each sized by its count, and where each header falls.
     */
    private static final class Layout {
        final List<MailStore.StoredMessage> outbox;

        /** How many synced rows follow the waiting ones. */
        final long synced;

        /** How many shown rows are unread. */
        final long unread;

        final List<String> labels = new ArrayList<>();

        /** Each section's first row, counted from the first waiting one. */
        final List<Long> firstRow = new ArrayList<>();

        final List<Long> rowCount = new ArrayList<>();

        /** Each section's header position. */
        int[] headerAt = new int[0];

        /** The footer the list ends on, null for none. */
        Footer footer;

        /** Each account's window, null for all mail: what a row not on the phone says. */
        Map<String, String> windows = Map.of();

        /** Each mailbox's role, by collection id. */
        Map<String, String> roles = Map.of();

        /** Whether the network was metered when read, holding the larger bodies back. */
        boolean metered;

        int size;

        private Layout(List<MailStore.StoredMessage> outbox, long synced, long unread) {
            this.outbox = outbox;
            this.synced = synced;
            this.unread = unread;
        }

        static Layout empty() {
            return new Layout(List.of(), 0, 0);
        }

        /**
         * Lays the counts out: the outbox under its own name, then the days,
         * consecutive days sharing a label (two weeks ago) sharing a card.
         */
        static Layout of(
                MainActivity host,
                List<MailStore.StoredMessage> outbox,
                String outboxLabel,
                String undated,
                List<MailStore.Day> days,
                long unread) {
            long synced = 0;
            for (MailStore.Day day : days) {
                synced += day.count;
            }
            Layout layout = new Layout(outbox, synced, unread);

            long row = 0;
            if (!outbox.isEmpty()) {
                layout.add(outboxLabel, row, outbox.size(), false);
                row += outbox.size();
            }
            boolean daysStarted = false;
            for (MailStore.Day day : days) {
                if (day.count <= 0) {
                    continue;
                }
                String label = day.day == null ? undated : labelOf(host, day.day, undated);
                layout.add(label, row, day.count, daysStarted);
                daysStarted = true;
                row += day.count;
            }
            layout.place();
            return layout;
        }

        private void add(String label, long first, long count, boolean merge) {
            int last = labels.size() - 1;
            if (merge && last >= 0 && labels.get(last).equals(label)) {
                rowCount.set(last, rowCount.get(last) + count);
                return;
            }
            labels.add(label);
            firstRow.add(first);
            rowCount.add(count);
        }

        private void place() {
            headerAt = new int[labels.size()];
            long position = 0;
            for (int section = 0; section < labels.size(); section++) {
                headerAt[section] = (int) Math.min(Integer.MAX_VALUE, position);
                position += 1 + rowCount.get(section);
            }
            size = (int) Math.min(Integer.MAX_VALUE, position);
        }

        long rows() {
            return outbox.size() + synced;
        }

        /** The section a position falls in. */
        int sectionAt(int position) {
            int low = 0;
            int high = headerAt.length - 1;
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (headerAt[middle] <= position) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            return low;
        }

        boolean isHeader(int position) {
            return headerAt.length > 0 && headerAt[sectionAt(position)] == position;
        }

        /** The row a position draws, counted from the first waiting one; -1 at a header. */
        long rowAt(int position) {
            if (position < 0 || position >= size || headerAt.length == 0) {
                return -1;
            }
            int section = sectionAt(position);
            if (headerAt[section] == position) {
                return -1;
            }
            return firstRow.get(section) + (position - headerAt[section] - 1);
        }

        /** The background a row draws with, by its place in its card. */
        int shapeAt(int position) {
            int section = sectionAt(position);
            long offset = position - headerAt[section] - 1;
            boolean first = offset == 0;
            boolean last = offset == rowCount.get(section) - 1;
            if (first && last) {
                return R.drawable.row_card_single;
            }
            if (first) {
                return R.drawable.row_card_top;
            }
            return last ? R.drawable.row_card_bottom : R.drawable.row_card_middle;
        }

        /** A day of the store's count, labelled the way a row's day is. */
        private static String labelOf(MainActivity host, String day, String undated) {
            try {
                long noon =
                        LocalDate.parse(day)
                                .atTime(12, 0)
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli();
                String label = Dates.day(host, noon);
                return label.isEmpty() ? undated : label;
            } catch (RuntimeException error) {
                return undated;
            }
        }
    }

    // ---- the rows ---------------------------------------------------------

    /**
     * The message a position draws, or null at a header or while its page
     * is on its way, in which case the page is asked for.
     */
    private MailStore.StoredMessage messageAt(int position) {
        long row = layout.rowAt(position);
        if (row < 0) {
            return null;
        }
        if (row < layout.outbox.size()) {
            return layout.outbox.get((int) row);
        }
        long synced = row - layout.outbox.size();
        int page = (int) (synced / PAGE);
        int offset = (int) (synced % PAGE);

        List<MailStore.StoredMessage> rows = pages.get(page);
        if (rows == null) {
            load(page);
            return null;
        }
        // NOTE: the next page too, so a steady scroll finds it read.
        if (offset > PAGE / 2 && (long) (page + 1) * PAGE < layout.synced) {
            load(page + 1);
        }
        return offset < rows.size() ? rows.get(offset) : null;
    }

    /**
     * Reads one page: after the page before it when that one is read (a
     * keyset, what a scroll costs), from its offset otherwise (what a fling
     * to the far end costs).
     */
    private void load(int page) {
        if (pages.containsKey(page) || currentQuery == null || !loading.add(page)) {
            return;
        }
        int asked = generation;
        MailStore.Query listed = currentQuery;
        List<MailStore.StoredMessage> previous = pages.get(page - 1);
        MailStore.StoredMessage after =
                previous == null || previous.size() < PAGE ? null : previous.get(PAGE - 1);

        reads.execute(
                () -> {
                    List<MailStore.StoredMessage> rows =
                            store.page(listed, after, (long) page * PAGE, PAGE);
                    host.main.post(
                            () -> {
                                if (asked != generation) {
                                    return;
                                }
                                loading.remove(page);
                                pages.put(page, rows);
                                adapter.notifyDataSetChanged();
                            });
                });
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
            return layout.size + (layout.footer != null ? 1 : 0);
        }

        /** Whether a position is the footer past the last message. */
        private boolean isFooter(int position) {
            return layout.footer != null && position == layout.size;
        }

        @Override
        public Object getItem(int position) {
            return messageAt(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            if (isFooter(position)) {
                return 2;
            }
            return layout.isHeader(position) ? 1 : 0;
        }

        @Override
        public boolean isEnabled(int position) {
            return !isFooter(position) && !layout.isHeader(position);
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            if (isFooter(position)) {
                return footer(recycled, parent);
            }
            if (layout.isHeader(position)) {
                View view = recycled;
                if (view == null) {
                    view =
                            LayoutInflater.from(parent.getContext())
                                    .inflate(R.layout.item_section, parent, false);
                }
                ((TextView) view).setText(layout.labels.get(layout.sectionAt(position)));
                return view;
            }

            View view = recycled;
            if (view == null) {
                view = LayoutInflater.from(host).inflate(R.layout.item_message, parent, false);
            }
            int shape = layout.shapeAt(position);
            view.findViewById(R.id.row_card).setBackgroundResource(shape);
            view.findViewById(R.id.message_divider)
                    .setVisibility(
                            shape == R.drawable.row_card_top || shape == R.drawable.row_card_single
                                    ? View.GONE
                                    : View.VISIBLE);

            MailStore.StoredMessage message = messageAt(position);
            if (message == null) {
                placeholder(view);
            } else {
                bind(view, message);
            }
            return view;
        }
    }

    /** A row whose page is still on its way: its card, and nothing in it yet. */
    private void placeholder(View view) {
        ((TextView) view.findViewById(R.id.message_subject)).setText("");
        ((TextView) view.findViewById(R.id.message_from)).setText("");
        ((TextView) view.findViewById(R.id.message_date)).setText("");
        ((TextView) view.findViewById(R.id.message_origin)).setText("");
        TextView avatar = view.findViewById(R.id.message_avatar);
        avatar.setText("");
        avatar.setBackground(Avatar.circle(host, ""));
        avatar.setVisibility(View.VISIBLE);
        view.findViewById(R.id.message_check).setVisibility(View.GONE);
        view.findViewById(R.id.message_dot).setVisibility(View.GONE);
        view.findViewById(R.id.message_star).setVisibility(View.GONE);
        view.findViewById(R.id.message_attachment).setVisibility(View.GONE);
        view.findViewById(R.id.message_answered).setVisibility(View.GONE);
        view.findViewById(R.id.message_unsynced).setVisibility(View.GONE);
        dim(view, false);
    }

    /** Draws a row's content faded, its card left as it is. */
    private static void dim(View view, boolean dimmed) {
        ViewGroup card = view.findViewById(R.id.row_card);
        for (int index = 0; index < card.getChildCount(); index++) {
            card.getChildAt(index).setAlpha(dimmed ? 0.5f : 1f);
        }
    }

    /**
     * What a row whose body the phone does not hold says, 0 for nothing: a
     * message within its account's window (outside the junk and the trash)
     * is on its way, saying so only while a metered network holds a large
     * one back; any other is not downloaded.
     */
    private int notOnPhone(MailStore.StoredMessage message) {
        if (message.bodied) {
            return 0;
        }
        boolean taken =
                MailBodies.takes(
                        message.sortKey,
                        layout.windows.get(message.accountEmail),
                        layout.roles.get(message.collection),
                        MailOffline.whole(host, message.collection));
        if (!taken) {
            return R.string.mail_row_not_downloaded;
        }
        return layout.metered && !MailBodies.fits(message.size, message.attachment)
                ? R.string.mail_row_wifi
                : 0;
    }

    /** One message's row. */
    private void bind(View view, MailStore.StoredMessage message) {
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
        int missing = notOnPhone(message);
        ((TextView) view.findViewById(R.id.message_origin))
                .setText(
                        state(message)
                                + " · "
                                + message.accountEmail
                                + (missing == 0 ? "" : " · " + host.getString(missing)));
        dim(view, !message.bodied);

        // The disc stands for the sender rather than the message, so
        // it is keyed by the address alone: a sender who changes how
        // their name is spelled keeps the colour the eye learned.
        TextView avatar = view.findViewById(R.id.message_avatar);
        avatar.setText(Avatar.letter(message.fromAddress));
        avatar.setBackground(Avatar.circle(host, message.fromAddress));
        boolean checked = isSelected(message);
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
        view.findViewById(R.id.message_dot).setVisibility(unread ? View.VISIBLE : View.GONE);
        from.setTypeface(null, unread ? Typeface.BOLD : Typeface.NORMAL);
        subject.setTypeface(null, unread ? Typeface.BOLD : Typeface.NORMAL);
        date.setTextColor(
                host.ui.resolveColor(
                        unread ? android.R.attr.colorAccent : android.R.attr.textColorSecondary));

        bindMarks(view, message);
    }

    /** What a row says of where its message stands. */
    private String state(MailStore.StoredMessage message) {
        if (message.failed) {
            return host.getString(R.string.message_send_failed);
        }
        if (message.pending) {
            return host.getString(R.string.message_pending);
        }
        if (message.refused) {
            return host.getString(R.string.row_refused) + " · " + message.mailboxLabel;
        }
        // NOTE: deleted on a server expunging no single message, so still
        // listed until something expunges the mailbox.
        return message.deleted
                ? message.mailboxLabel + " · " + host.getString(R.string.message_marked_deleted)
                : message.mailboxLabel;
    }

    /**
     * The marks: the star of an important message, the paperclip of one
     * carrying an attachment, the replied mark, and the sync mark of a
     * change the server has not taken yet. Shown only when they hold, so
     * a line ends in as few glyphs as it can.
     */
    private void bindMarks(View view, MailStore.StoredMessage message) {
        view.findViewById(R.id.message_star)
                .setVisibility(message.flagged ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.message_attachment)
                .setVisibility(message.hasAttachment ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.message_answered)
                .setVisibility(message.answered ? View.VISIBLE : View.GONE);
        host.ui.syncMark(
                view.findViewById(R.id.message_unsynced), message.unsynced, message.refused);
    }

    // ---- the selection ----------------------------------------------------

    boolean isSelectionMode() {
        return allSelected || !selected.isEmpty();
    }

    /** What a message is known by in the selection, across accounts. */
    private static String keyOf(MailStore.StoredMessage message) {
        return message.pending
                ? "\u0001queued/" + message.queued
                : message.collection + "/" + message.id;
    }

    private boolean isSelected(MailStore.StoredMessage message) {
        return allSelected
                ? !excluded.contains(keyOf(message))
                : selected.containsKey(keyOf(message));
    }

    /** How many messages the selection holds. */
    private long selectedCount() {
        return allSelected ? Math.max(0, layout.rows() - excluded.size()) : selected.size();
    }

    /** Adds a message to the selection, or takes it out. */
    private void toggle(MailStore.StoredMessage message) {
        String key = keyOf(message);
        if (allSelected) {
            if (!excluded.remove(key)) {
                excluded.add(key);
            }
            if (excluded.size() >= layout.rows()) {
                allSelected = false;
                excluded.clear();
            }
        } else if (selected.remove(key) == null) {
            selected.put(key, message);
        }
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    void exitSelection() {
        selected.clear();
        excluded.clear();
        allSelected = false;
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    /**
     * Selects everything the query lets through, or clears the selection
     * when it already is: a flag over the query, read whole only when the
     * bar acts on it.
     */
    void toggleSelectAll() {
        boolean all = allSelected && excluded.isEmpty();
        selected.clear();
        excluded.clear();
        allSelected = !all && layout.rows() > 0;
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    /**
     * Hands the selected messages over once they are read: the rows picked
     * one by one as they are, everything the query lets through read from
     * the store, off the main thread, when all of it is selected.
     */
    private void withSelection(Consumer<List<MailStore.StoredMessage>> act) {
        if (!allSelected) {
            act.accept(new ArrayList<>(selected.values()));
            return;
        }
        MailStore.Query listed = currentQuery;
        List<MailStore.StoredMessage> outbox = new ArrayList<>(layout.outbox);
        Set<String> left = new HashSet<>(excluded);
        reads.execute(
                () -> {
                    List<MailStore.StoredMessage> messages = new ArrayList<>(outbox);
                    if (listed != null) {
                        messages.addAll(store.all(listed));
                    }
                    messages.removeIf(message -> left.contains(keyOf(message)));
                    host.main.post(() -> act.accept(Collections.unmodifiableList(messages)));
                });
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
            title.setText(
                    host.getString(
                            R.string.selected_count,
                            (int) Math.min(Integer.MAX_VALUE, selectedCount())));
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
                .setChecked(selecting && allSelected && excluded.isEmpty());
        host.findViewById(R.id.fab_extended).setVisibility(selecting ? View.GONE : View.VISIBLE);

        // NOTE: a selection of everything is read from the counts: unread
        // when the store counts any, and starring rather than reading every
        // flag back, the store counting the unread and not the starred.
        boolean anyUnread = allSelected && layout.unread > 0;
        boolean anyPlain = allSelected;
        for (MailStore.StoredMessage message : selected.values()) {
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
        withSelection(
                messages -> {
                    boolean anyUnread = false;
                    for (MailStore.StoredMessage message : messages) {
                        anyUnread |= !message.seen && !message.pending;
                    }
                    host.messageView.stageFlag(messages, MailEngine.SEEN, anyUnread, this::reload);
                });
    }

    /** Stars the selection, or unstars it when it all already is. */
    private void flagSelected() {
        withSelection(
                messages -> {
                    boolean anyPlain = false;
                    for (MailStore.StoredMessage message : messages) {
                        anyPlain |= !message.flagged && !message.pending;
                    }
                    host.messageView.stageFlag(
                            messages, MailEngine.FLAGGED, anyPlain, this::reload);
                });
    }

    /** Asks, then stages the selection's deletion. */
    private void deleteSelected() {
        withSelection(messages -> host.messageView.confirmDelete(messages, this::exitSelection));
    }
}
