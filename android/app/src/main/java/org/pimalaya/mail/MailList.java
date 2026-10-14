package org.pimalaya.mail;

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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
import org.pimalaya.AccountActivation;
import org.pimalaya.Avatar;
import org.pimalaya.Chips;
import org.pimalaya.Dates;
import org.pimalaya.ListHeader;
import org.pimalaya.MainActivity;
import org.pimalaya.PimDomain;
import org.pimalaya.R;
import org.pimalaya.sync.MergedFilter;
import org.pimalaya.sync.SyncScope;

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
 * <p>It shows what the steps brought ({@link MailStep}): down to its floor,
 * the most recent of the shown accounts' frontiers and of the shown
 * mailboxes' coverage, so what it shows is on the phone, and it ends on a
 * footer loading more. It loads lazily. Its size is a count of what
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
public final class MailList {
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
     * What a list shows and a pull syncs under a role chip: every mailbox
     * holding {@code role} ({@code roles} by collection id) of the accounts
     * {@code filter} shows, ticked or not, a chip being a view of its own;
     * what the filter lets through with no chip on. The outbox holds no
     * role, so a chip leaves it out.
     */
    public static SyncScope narrowed(MergedFilter filter, String role, Map<String, String> roles) {
        if (role == null) {
            return filter;
        }
        return new SyncScope() {
            @Override
            public boolean account(String email) {
                return filter.showsAccount(email);
            }

            @Override
            public boolean collection(String email, String collection) {
                return account(email) && role.equals(roles.get(collection));
            }
        };
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

    public MailList(MainActivity host, MailStore store) {
        this.host = host;
        this.store = store;
    }

    public void setUp() {
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
                    host.syncMail(role);
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
    public java.util.concurrent.Executor reads() {
        return reads;
    }

    public ListHeader header() {
        return header;
    }

    // ---- the layout -------------------------------------------------------

    /**
     * Reads the list's shape from the store again: the counts that size
     * it, the per-day counts that place its headers, the badge, and the
     * pages around where the list stands, then swaps it in whole, so what
     * is on screen never flashes through placeholders.
     */
    public void reload() {
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

        reads.execute(
                () -> {
                    Map<String, String> roles = store.roles();
                    BiPredicate<String, String> shown =
                            narrowed(filter, narrowing, roles)::collection;
                    MailStore.Query wanted = store.query(shown, unread, attachments, words);
                    List<MailStore.Edge> edges = store.edges();

                    Map<String, String> windows = new HashMap<>();
                    Map<String, String> mailboxWindows = new HashMap<>();
                    for (MailStore.Edge edge : edges) {
                        if (!windows.containsKey(edge.accountEmail)) {
                            windows.put(edge.accountEmail, store.windowOf(edge.accountEmail));
                        }
                        mailboxWindows.put(edge.collection, store.mailboxWindow(edge.collection));
                    }
                    long badge = badge(filter, windows);

                    // NOTE: a search covers every stored message; the list
                    // reaches down to its floor otherwise, ending on the
                    // footer that loads more.
                    String floor = null;
                    Footer footer = null;
                    if (words.trim().isEmpty()) {
                        floor = store.floorOf(wanted, edges);
                        footer = footerOf(wanted, edges, roles);
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

                    int minutes = offsetMinutes();
                    List<MailStore.Day> days = store.countByDay(listed, shift(minutes));
                    long shownUnread = store.count(listed.unread());
                    Layout next = Layout.of(host, outbox, outboxLabel, undated, days, shownUnread);
                    next.days = days;
                    next.minutes = minutes;
                    next.footer = footer;
                    next.windows = mailboxWindows;
                    next.roles = roles;

                    // NOTE: the pages around where the list stands, read
                    // before the swap, so the rows on screen stay drawn.
                    Map<Integer, List<MailStore.StoredMessage>> read = new HashMap<>();
                    int first = Math.max(0, around - outbox.size()) / PAGE;
                    MailStore.StoredMessage after = null;
                    for (int page = Math.max(0, first - 1); page <= first + 1; page++) {
                        if ((long) page * PAGE >= next.synced) {
                            break;
                        }
                        List<MailStore.StoredMessage> rows = read(listed, next, after, page);
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
        // NOTE: an empty list with more to load shows its footer instead.
        host.findViewById(R.id.mail_empty)
                .setVisibility(rows == 0 && layout.footer == null ? View.VISIBLE : View.GONE);
    }

    // ---- the footer -------------------------------------------------------

    /** One step the footer's button takes: an account's, or one mailbox's alone. */
    public static final class Step {
        public final String email;

        /** The mailbox stepped alone, null for the account's step. */
        public final String only;

        Step(String email, String only) {
            this.email = email;
            this.only = only;
        }
    }

    /**
     * What the list's footer offers, read with the layout: the steps a tap
     * takes ({@link MailStep}), and whether any steps one mailbox of an
     * account stepped mailbox by mailbox, which the line under it says.
     */
    private static final class Footer {
        final List<Step> steps;
        final boolean perMailbox;

        Footer(List<Step> steps, boolean perMailbox) {
            this.steps = steps;
            this.perMailbox = perMailbox;
        }
    }

    /**
     * The footer under {@code wanted}, null for none, over the shown
     * accounts that are on, a mailbox kept whole taking no part. A Gmail
     * account, listed whole through All Mail, takes one step while it has
     * more to load ({@link MailWindow#more}), of the one label it shows
     * alone if it does. Any other account takes one step of each mailbox it
     * shows that has more ({@link MailWindow#more(android.content.Context,
     * String, String)}). The junk or the trash, which no step of its account
     * brings, loads more only when its account shows it alone, by itself
     * ({@link #moreAlone}).
     */
    private Footer footerOf(
            MailStore.Query wanted, List<MailStore.Edge> edges, Map<String, String> roles) {
        Map<String, List<String>> gmail = new LinkedHashMap<>();
        Map<String, Integer> stepped = new HashMap<>();
        Map<String, List<MailStore.Edge>> quiet = new LinkedHashMap<>();
        Set<String> showing = new HashSet<>();
        List<Step> steps = new ArrayList<>();
        boolean perMailbox = false;
        for (MailStore.Edge edge : edges) {
            // NOTE: an account that is off loads nothing.
            boolean held =
                    wanted.holds(edge.collection)
                            && AccountActivation.enabled(host, edge.accountEmail);
            if (MailOffline.whole(host, edge.collection)) {
                continue;
            }
            if (!MailBodies.downloads(roles.get(edge.collection), false)) {
                if (held) {
                    quiet.computeIfAbsent(edge.accountEmail, email -> new ArrayList<>())
                            .add(edge);
                }
                continue;
            }
            String accountId = store.accountIdOf(edge.accountEmail);
            boolean whole = MailStore.keepsAllMail(host, accountId);
            stepped.merge(edge.accountEmail, 1, Integer::sum);
            if (held) {
                showing.add(edge.accountEmail);
            }
            if (held && whole && MailWindow.more(host, accountId)) {
                gmail.computeIfAbsent(edge.accountEmail, email -> new ArrayList<>())
                        .add(edge.collection);
            } else if (held && !whole && MailWindow.more(host, accountId, edge.collection)) {
                steps.add(new Step(edge.accountEmail, edge.collection));
                perMailbox = true;
            }
        }
        for (Map.Entry<String, List<MailStore.Edge>> account : quiet.entrySet()) {
            String email = account.getKey();
            MailStore.Edge alone = account.getValue().get(0);
            if (!showing.contains(email) && account.getValue().size() == 1 && moreAlone(alone)) {
                steps.add(new Step(email, alone.collection));
            }
        }
        for (Map.Entry<String, List<String>> account : gmail.entrySet()) {
            String email = account.getKey();
            String only = null;
            if (account.getValue().size() == 1 && stepped.get(email) > 1) {
                only = account.getValue().get(0);
                // NOTE: a label stepped alone down to all of its mail has
                // nothing more to load, whatever its account's others hold.
                if (!MailWindow.more(host, store.accountIdOf(email), only)) {
                    continue;
                }
            }
            steps.add(new Step(email, only));
        }
        if (steps.isEmpty()) {
            return null;
        }
        return new Footer(steps, perMailbox);
    }

    /**
     * Whether the junk or the trash shown alone has more to load: on Gmail,
     * where it is stepped by its label, while its own frontier is not all
     * mail; elsewhere, where it is widened chunk by chunk, until it is
     * listed down to its account's bound.
     */
    private boolean moreAlone(MailStore.Edge edge) {
        String accountId = store.accountIdOf(edge.accountEmail);
        if (MailStore.keepsAllMail(host, accountId)) {
            return MailWindow.moreAlone(host, accountId, edge.collection);
        }
        return !edge.listed || edge.limit != null;
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

    /**
     * The line under the footer's button: about how many messages a step
     * brings each account, or each mailbox where one is stepped mailbox by
     * mailbox ({@code perMailbox}), and that it waits for Wi-Fi when there
     * is none.
     */
    static String info(android.content.Context context, boolean wifi, boolean perMailbox) {
        String line =
                context.getResources()
                        .getQuantityString(
                                perMailbox
                                        ? R.plurals.mail_more_info_mailbox
                                        : R.plurals.mail_more_info,
                                MailStep.COUNT,
                                MailStep.COUNT);
        return wifi ? line : line + " · " + context.getString(R.string.mail_more_wifi);
    }

    /** What the footer's button offers. */
    enum FooterState {
        /** Load more: a tap takes the next step. */
        LOAD,
        /** A step or another pass holds the sync: disabled, saying it is loading. */
        LOADING,
        /** No Wi-Fi: disabled, its line saying it waits for it. */
        WIFI
    }

    /**
     * The footer's state: waiting off Wi-Fi, where no step runs; loading
     * while the sync is held ({@code busy}), so a second tap queues
     * nothing; else ready to load.
     */
    static FooterState footerState(boolean wifi, boolean busy) {
        if (!wifi) {
            return FooterState.WIFI;
        }
        return busy ? FooterState.LOADING : FooterState.LOAD;
    }

    /** Redraws the footer, a sync having started or ended. */
    public void redrawFooter() {
        if (layout.footer != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /** The list's footer: "Load more" and the line under it. */
    private View footer(View recycled, ViewGroup parent) {
        View view = recycled;
        if (view == null) {
            view = LayoutInflater.from(host).inflate(R.layout.item_mail_window, parent, false);
        }
        Footer footer = layout.footer;
        boolean wifi = host.wifi();
        FooterState state = footerState(wifi, host.syncHeld());
        android.widget.Button load = view.findViewById(R.id.mail_window_load);
        load.setEnabled(state == FooterState.LOAD);
        load.setAlpha(state == FooterState.LOAD ? 1f : 0.6f);
        load.setText(
                state == FooterState.LOADING ? R.string.mail_more_loading : R.string.mail_more_load);
        load.setOnClickListener(tapped -> host.loadMore(footer.steps));
        ((TextView) view.findViewById(R.id.mail_window_info))
                .setText(info(host, wifi, footer.perMailbox));
        return view;
    }

    /**
     * The SQLite modifier moving a UTC instant onto the device's wall clock
     * today, so a day header falls where the reader's midnight does.
     */
    private static String shift(int minutes) {
        return String.format(Locale.ROOT, "%+d minutes", minutes);
    }

    /** The device's offset from UTC today, in minutes. */
    private static int offsetMinutes() {
        return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000;
    }

    /** Where a page's read starts: below a sort key, past some of its rows. */
    static final class Seek {
        final String key;
        final long skip;

        Seek(String key, long skip) {
            this.key = key;
            this.skip = skip;
        }
    }

    /**
     * Where the list's synced row {@code row} is read from, by the per-day
     * counts it was laid out from ({@code days}, newest first, undated last,
     * counted {@code minutes} off UTC): below the first instant of the day
     * after its own, past the rows of its day ahead of it. An undated row
     * reads below {@link #UNDATED}. Null past the counted rows.
     */
    static Seek seek(List<MailStore.Day> days, int minutes, long row) {
        long before = 0;
        for (MailStore.Day day : days) {
            if (row < before + day.count) {
                String key = day.day == null ? UNDATED : keyAfter(day.day, minutes);
                return new Seek(key, row - before);
            }
            before += day.count;
        }
        return null;
    }

    /**
     * The sort key of the first instant of the day after {@code day} on a
     * wall clock {@code minutes} off UTC, in the shape the store writes keys.
     */
    private static String keyAfter(String day, int minutes) {
        return LocalDate.parse(day)
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .minusMinutes(minutes)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT));
    }

    /** A sort key above the undated one ('') and below every date. */
    static final String UNDATED = "0";

    /** Whether the search finds a waiting message in its sender or subject. */
    private static boolean matches(MailStore.StoredMessage message, String words) {
        return words.isEmpty()
                || (message.subject + " " + message.fromName + " " + message.fromAddress)
                        .toLowerCase(java.util.Locale.ROOT)
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

        /** Each mailbox's frontier, null for all mail: what a row not on the phone says. */
        Map<String, String> windows = Map.of();

        /** Each mailbox's role, by collection id. */
        Map<String, String> roles = Map.of();

        int size;

        /** The per-day counts it was laid out from, newest day first, undated last. */
        List<MailStore.Day> days = List.of();

        /** The wall clock's offset from UTC the days were counted on, in minutes. */
        int minutes;

        /** Where the synced row {@code row} is read from ({@link MailList#seek}). */
        Seek seek(long row) {
            return MailList.seek(days, minutes, row);
        }

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
     * keyset, what a scroll costs), from the start of the day it lands on
     * otherwise (what a fling costs: the rows of that day before it).
     */
    private void load(int page) {
        if (pages.containsKey(page) || currentQuery == null || !loading.add(page)) {
            return;
        }
        int asked = generation;
        MailStore.Query listed = currentQuery;
        Layout shown = layout;
        List<MailStore.StoredMessage> previous = pages.get(page - 1);
        MailStore.StoredMessage after =
                previous == null || previous.size() < PAGE ? null : previous.get(PAGE - 1);

        reads.execute(
                () -> {
                    List<MailStore.StoredMessage> rows = read(listed, shown, after, page);
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
     * One page of the list: after {@code after} when the page before it is
     * read, else from the start of the day its first row falls on, which the
     * layout's day counts place ({@link Layout#seek}).
     */
    private List<MailStore.StoredMessage> read(
            MailStore.Query listed, Layout shown, MailStore.StoredMessage after, int page) {
        long first = (long) page * PAGE;
        if (after != null || first == 0) {
            return store.page(listed, after, first, PAGE);
        }
        Seek seek = shown.seek(first);
        return seek == null
                ? store.page(listed, null, first, PAGE)
                : store.pageBelow(listed, seek.key, seek.skip, PAGE);
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
     * message whose body failed every attempt of a run says so; one from its
     * mailbox's frontier on (outside the junk and the trash), or one owed
     * ({@link MailOwed}), is on its way; any other is not downloaded, one
     * the server answered gone ({@link MailBodies#GONE}) included.
     */
    private int notOnPhone(MailStore.StoredMessage message) {
        if (message.bodied) {
            return 0;
        }
        boolean taken =
                MailBodies.takes(
                        message.sortKey,
                        layout.windows.get(message.collection),
                        layout.roles.get(message.collection),
                        MailOffline.whole(host, message.collection));
        String key = MailOwed.key(message.collection, message.id);
        if (MailBodies.FAILED.contains(key) || MailPoison.poisoned(host, key)) {
            return R.string.mail_row_failed;
        }
        if (MailBodies.GONE.contains(key)) {
            return R.string.mail_row_not_downloaded;
        }
        boolean owed = MailOwed.owed(host, message.collection, message.id);
        return taken || owed ? 0 : R.string.mail_row_not_downloaded;
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
        if (message.uncertain) {
            return host.getString(R.string.message_send_uncertain);
        }
        if (message.failed) {
            return host.getString(R.string.message_send_failed);
        }
        if (message.pending) {
            return host.getString(R.string.message_pending);
        }
        // NOTE: deleted on a server expunging no single message, so still
        // listed until something expunges the mailbox.
        return message.deleted
                ? message.mailboxLabel + " · " + host.getString(R.string.message_marked_deleted)
                : message.mailboxLabel;
    }

    /**
     * The marks: the star of an important message, the paperclip of one
     * carrying an attachment and the replied mark. Shown only when they
     * hold, so a line ends in as few glyphs as it can.
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

    public boolean isSelectionMode() {
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

    public void exitSelection() {
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
    public void toggleSelectAll() {
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
