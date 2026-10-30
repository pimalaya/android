package org.pimalaya.contact;

import android.view.View;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pimalaya.R;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.LocalBook;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.pimdir.DefaultCollection;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.ui.Avatar;
import org.pimalaya.ui.CardSections;
import org.pimalaya.ui.FilterPanel;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.ListFilter;
import org.pimalaya.ui.ListHeader;
import org.pimalaya.ui.Panels;
import org.pimalaya.ui.SectionsAdapter;
import org.pimalaya.ui.Ui;

/**
 * The contacts screen: the merged list by letter, search and multi-select.
 *
 * <p>It owns the display state and rebuilds it off the main thread through
 * {@link ContactPool}; the host keeps the shared chrome bar (re-running
 * {@link #updateSelectionUi} on every return) and the flows leaving it.
 */
public final class ContactsList {
    private final Host host;
    private final ContactParts parts;
    private final ContactEditor editor;

    /** The pull-down's sync of the contacts. */
    private final Runnable pull;

    /** The vCard file import and export over this list. */
    private final VcfTransfer transfer;

    /** The replica pool: every subscribed book's cards, unmerged. */
    private List<Entry> contacts = new ArrayList<>();

    /** The merged rows as the bridge grouped them, unfiltered. */
    private List<Group> groupedContacts = new ArrayList<>();

    /** The merged rows, search-filtered and sorted; backs the adapter. */
    private List<Group> sortedContacts = new ArrayList<>();

    private final CardSections<Group> sections = new CardSections<>();
    private final Adapter adapter = new Adapter();
    private ListHeader header;

    /** The panel's sections: which books show, and All or Default. */
    private static final String BOOKS = "books";

    private static final String SHOW = "show";

    private static final String[] SHOWS = {"all", "default"};

    private static final int[] SHOW_LABELS = {R.string.filter_all, R.string.chip_default};

    private static final String HAS = "has";

    private static final String KINDS = "kinds";

    private static final String CATEGORIES = "categories";

    /** The vCard properties Has offers (RFC 6350), a contact passing with any on. */
    private static final String[] HAS_KEYS = {"EMAIL", "TEL", "ADR", "BDAY", "ORG", "PHOTO"};

    private static final int[] HAS_LABELS = {
        R.string.filter_email,
        R.string.filter_phone,
        R.string.filter_address,
        R.string.filter_birthday,
        R.string.filter_organisation,
        R.string.filter_photo,
    };

    /** The {@code KIND} values Kind offers (RFC 6350 6.1.4). */
    private static final String[] KIND_KEYS = {"individual", "group", "org"};

    private static final int[] KIND_LABELS = {
        R.string.filter_individuals, R.string.filter_groups, R.string.filter_organisations,
    };

    /** What the list keeps of the books synced, drafted by the panel. */
    private ListFilter filter;

    private FilterPanel panel;

    /** Multi-select state, keyed by merged group. */
    private boolean selectionMode;

    private final Set<String> selectedKeys = new java.util.HashSet<>();

    /** Lower-cased raw-vCard filter; empty shows all. */
    private String searchQuery = "";

    /** The contacts screen; {@code pull} runs the pull-to-refresh sync. */
    public ContactsList(Host host, ContactParts parts, ContactEditor editor, Runnable pull) {
        this.host = host;
        this.parts = parts;
        this.editor = editor;
        this.pull = pull;
        this.transfer = new VcfTransfer(host, parts, editor, this);
        editor.list = this;
    }

    /** Wires the list, its bar buttons, the header search and the pull-down. */
    public void setUp() {
        // NOTE: pull-to-refresh runs the same syncAll as the drawer; its own
        // spinner retracts right away, the sync dock carries the wait.
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout refresh =
                host.findViewById(R.id.contacts_refresh);
        refresh.setColorSchemeColors(host.ui.resolveColor(android.R.attr.colorAccent));
        refresh.setOnRefreshListener(
                () -> {
                    refresh.setRefreshing(false);
                    pull.run();
                });

        host.findViewById(R.id.contacts_birthdays)
                .setOnClickListener(
                        view -> new Birthdays(host).show(new ArrayList<>(sortedContacts)));
        host.findViewById(R.id.contacts_duplicates)
                .setOnClickListener(
                        view ->
                                new DuplicateReview(host, parts, editor, this::reload)
                                        .find(new ArrayList<>(contacts)));
        host.findViewById(R.id.contacts_transfer).setOnClickListener(this::showTransferMenu);
        host.findViewById(R.id.contacts_merge).setOnClickListener(view -> mergeSelected());
        host.findViewById(R.id.contacts_delete)
                .setOnClickListener(view -> confirmDeleteSelected());

        ListView list = host.findViewById(R.id.contacts_list);
        header = new ListHeader(host, list, Panels.CONTACTS);
        header.empty(host.findViewById(R.id.contacts_empty));
        header.search(
                R.string.contacts_search,
                query -> {
                    searchQuery = query;
                    render();
                });
        filter = new ListFilter(host, "contacts", BOOKS, SHOW, HAS, KINDS, CATEGORIES);
        panel = new FilterPanel(host, header, this::render, filter);
        list.setAdapter(adapter);

        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    int row = header.rowAt(position);
                    if (sections.isHeader(row)) {
                        toggleLetter(sections.header(row));
                        return;
                    }
                    Group group = sections.row(row);
                    if (selectionMode) {
                        toggleSelection(group.key);
                    } else {
                        editor.openGroup(group);
                    }
                });
        list.setOnItemLongClickListener(
                (parent, view, position, id) -> {
                    Group group = sections.row(header.rowAt(position));
                    if (group == null) {
                        return false;
                    }
                    selectionMode = true;
                    toggleSelection(group.key);
                    return true;
                });
    }

    /** The list's large header. */
    public ListHeader header() {
        return header;
    }

    /** The vCard file import and export, answered by the host's pickers. */
    public VcfTransfer transfer() {
        return transfer;
    }

    /**
     * Rebuilds the list from the store, staged edits included.
     *
     * <p>Scan and grouping run on the single-threaded io executor, so
     * overlapping reloads apply in order; the account snapshot keeps the
     * grouping off the live main-thread cache.
     */
    public void reload() {
        List<AccountEntry> snapshot = new ArrayList<>(parts.accounts());
        host.io.execute(
                () -> {
                    List<Entry> entries = parts.pool().loadEntries();
                    List<Group> groups = parts.pool().group(entries, snapshot);
                    host.postAlive(
                            () -> {
                                contacts = entries;
                                groupedContacts = groups;
                                render();
                            });
                });
    }

    /**
     * The rows on screen (search-filtered), for export and the birthday peek.
     */
    List<Group> visibleGroups() {
        return sortedContacts;
    }

    /**
     * The merged row holding a card of this key, filter or not; null for none.
     */
    public Group groupOf(String linkId) {
        for (Group group : groupedContacts) {
            for (Entry entry : group.replicas) {
                if (entry.card.id().equals(linkId)) {
                    return group;
                }
            }
        }
        return null;
    }

    /** Whether a multi-selection is running. */
    public boolean isSelectionMode() {
        return selectionMode;
    }

    /** Whether a search query filters the list. */
    public boolean isSearchOpen() {
        return !searchQuery.isEmpty();
    }

    /** Re-applies the filter and the query to the rows already grouped. */
    public void reRender() {
        render();
    }

    /**
     * Whether a card passes Has, Kind and Categories: any property on, any
     * kind on, any category on (compared lowercased), each none on passing.
     */
    private static boolean described(ListFilter.State kept, String vcard) {
        Set<String> has = kept.set(HAS);
        if (!has.isEmpty()) {
            boolean any = false;
            for (String property : has) {
                any |= CardFacts.has(vcard, property);
            }
            if (!any) {
                return false;
            }
        }
        Set<String> kinds = kept.set(KINDS);
        if (!kinds.isEmpty() && !kinds.contains(CardFacts.kind(vcard))) {
            return false;
        }
        Set<String> categories = kept.set(CATEGORIES);
        if (categories.isEmpty()) {
            return true;
        }
        for (String category : CardFacts.categories(vcard)) {
            if (categories.contains(category.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The filter panel: the books synced under their accounts, any hidden
     * from this list alone, then All or Default.
     */
    private void drawFilters(MergedFilter synced) {
        Map<String, Map<String, String>> books = new LinkedHashMap<>();
        for (BookEntry entry : parts.cards().loadAllAddressbooks()) {
            if (entry.subscribed && synced.accepts(entry.accountEmail, entry.book.url())) {
                String account =
                        LocalBook.is(entry.accountEmail)
                                ? host.getString(R.string.local_book)
                                : entry.accountEmail;
                books.computeIfAbsent(account, key -> new LinkedHashMap<>())
                        .put(entry.book.url(), entry.book.name());
            }
        }
        ListFilter.State edited = filter.edited();
        Runnable changed =
                () -> {
                    filter.save();
                    render();
                };
        panel.begin();
        panel.collections("books", R.string.filter_books, books, edited.set(BOOKS), changed);
        panel.choice("show", R.string.filter_show, SHOWS, SHOW_LABELS, edited.set(SHOW), changed);
        panel.checks("has", R.string.filter_has, HAS_KEYS, HAS_LABELS, edited.set(HAS), changed);
        panel.checks(
                "kinds", R.string.filter_kind, KIND_KEYS, KIND_LABELS, edited.set(KINDS), changed);
        Map<String, String> names = new LinkedHashMap<>();
        for (Group group : groupedContacts) {
            for (Entry entry : group.replicas) {
                for (String category : CardFacts.categories(entry.card.vcard())) {
                    names.putIfAbsent(category.toLowerCase(java.util.Locale.ROOT), category);
                }
            }
        }
        panel.checks(
                "categories",
                R.string.filter_categories,
                names.keySet().toArray(new String[0]),
                names.values().toArray(new String[0]),
                edited.set(CATEGORIES),
                changed);
        panel.end(filter.applied().active());
    }

    /**
     * Filters the grouped rows by the search query and refreshes the list.
     *
     * <p>A group matches when any replica does; conflicts float to the top.
     */
    private void render() {
        updateSelectionUi();

        sortedContacts = new ArrayList<>();
        MergedFilter synced = MergedFilter.of(host, PimDomain.CONTACTS);
        drawFilters(synced);
        ListFilter.State kept = filter.applied();
        Set<String> defaults =
                kept.set(SHOW).contains("default")
                        ? DefaultCollection.shown(
                                host,
                                PimdirSummary.CONTACT,
                                parts.cards()
                                        .subscribedBooks(
                                                new PimdirCollections(parts.pimdir(), host)))
                        : null;
        for (Group group : groupedContacts) {
            // NOTE: hiding an account thins a row's replicas, and only empties
            // the row when that account held all of them.
            boolean matches = false;
            for (Entry entry : group.replicas) {
                if (!synced.accepts(entry.accountEmail, entry.book.url())
                        || kept.set(BOOKS).contains(entry.book.url())
                        || !described(kept, entry.card.vcard())
                        || defaults != null && !defaults.contains(entry.book.url())) {
                    continue;
                }
                if (searchQuery.isEmpty()
                        || entry.card.vcard().toLowerCase(java.util.Locale.ROOT).contains(searchQuery)) {
                    matches = true;
                    break;
                }
            }
            if (matches) {
                sortedContacts.add(group);
            }
        }

        // NOTE: conflicts float to the top, under a header of their own;
        // the stable sort keeps the bridge's display-name order within
        // each bucket, which is what makes each letter one run.
        java.util.Collections.sort(
                sortedContacts,
                (left, right) -> Boolean.compare(right.conflicted(), left.conflicted()));
        sections.fill(sortedContacts, this::sectionOf);
        adapter.notifyDataSetChanged();
        header.meta(
                host.getResources()
                        .getQuantityString(
                                R.plurals.contacts_meta,
                                sortedContacts.size(),
                                sortedContacts.size()));

        host.findViewById(R.id.contacts_empty)
                .setVisibility(sortedContacts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** The card a contact goes in: its letter, or the conflicts' own. */
    private String sectionOf(Group group) {
        return group.conflicted()
                ? host.getString(R.string.contacts_conflicts)
                : Avatar.letter(group.primary().displayName());
    }

    /** Recycling adapter for the contacts list. */
    private final class Adapter extends SectionsAdapter<Group> {
        Adapter() {
            super(sections);
        }

        @Override
        protected View rowView(int position, View convertView, android.view.ViewGroup parent) {
            View row =
                    convertView != null
                            ? convertView
                            : host.getLayoutInflater().inflate(R.layout.item_contact, parent, false);
            sections.shape(row, position);
            row.findViewById(R.id.contact_divider)
                    .setVisibility(sections.opensCard(position) ? View.GONE : View.VISIBLE);

            Group group = sections.row(position);
            Entry entry = group.primary();
            String name = entry.displayName();

            TextView avatar = row.findViewById(R.id.contact_avatar);
            avatar.setText(Avatar.letter(name));
            avatar.setBackground(Avatar.circle(host, entry.card != null ? entry.card.vcard() : name));
            boolean checked = selectedKeys.contains(group.key);
            avatar.setVisibility(checked ? View.INVISIBLE : View.VISIBLE);
            android.widget.ImageView check = row.findViewById(R.id.contact_check);
            check.setVisibility(checked ? View.VISIBLE : View.GONE);
            check.setImageTintList(
                    android.content.res.ColorStateList.valueOf(host.accentContrast()));

            ((TextView) row.findViewById(R.id.contact_name)).setText(name);

            String subtitle = entry.phone;
            if (subtitle == null || subtitle.isEmpty()) {
                subtitle = entry.email;
            }
            if (subtitle == null || subtitle.isEmpty()) {
                subtitle = entry.info;
            }
            bindLine(row.findViewById(R.id.contact_subtitle), subtitle);

            int cards = parts.pool().distinctRefs(group.replicas).size();
            row.findViewById(R.id.contact_link_icon)
                    .setVisibility(cards > 1 ? View.VISIBLE : View.GONE);
            TextView links = row.findViewById(R.id.contact_links);
            links.setVisibility(cards > 1 ? View.VISIBLE : View.GONE);
            links.setText(String.valueOf(cards));

            ((TextView) row.findViewById(R.id.contact_origin)).setText(originOf(group));

            android.widget.ImageView danger = row.findViewById(R.id.contact_diverged);
            danger.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            host.ui.resolveColor(android.R.attr.colorError)));
            danger.setVisibility(
                    group.conflicted() || diverged(group) ? View.VISIBLE : View.GONE);

            // NOTE: a contact only in the hidden local book (attached to no
            // addressbook) shows muted.
            row.setAlpha(attachedToNoBook(group) ? 0.5f : 1f);

            return row;
        }
    }

    /**
     * Where a contact lives: its addressbook, then its account.
     *
     * <p>Several cards name the first one an account holds: the on-device book
     * is where cards wait, not where they belong.
     */
    private String originOf(Group group) {
        for (Entry entry : group.replicas) {
            if (!LocalBook.is(entry.accountEmail)) {
                return entry.book.name() + " · " + entry.accountEmail;
            }
        }
        String book = group.primary().book.name();
        String device = host.getString(R.string.local_account);
        return book == null || book.isEmpty() ? device : book + " · " + device;
    }

    /** True when every replica of the group is in the hidden local book. */
    private boolean attachedToNoBook(Group group) {
        for (Entry entry : group.replicas) {
            if (!LocalBook.is(entry.accountEmail)) {
                return false;
            }
        }
        return true;
    }

    /** The selected rows' replicas, in list order. */
    private List<Entry> selectedReplicas() {
        List<Entry> replicas = new ArrayList<>();
        for (Group group : sortedContacts) {
            if (selectedKeys.contains(group.key)) {
                replicas.addAll(group.replicas);
            }
        }
        return replicas;
    }

    /**
     * Merges the selected rows through the merge form (survivor and fan-out).
     */
    private void mergeSelected() {
        editor.mergeReplicas(selectedReplicas());
    }

    /** Toggles a contact's selection and refreshes the list and app bar. */
    private void toggleSelection(String key) {
        if (!selectedKeys.remove(key)) {
            selectedKeys.add(key);
        }
        if (selectedKeys.isEmpty()) {
            exitSelection();
        } else {
            updateSelectionUi();
            adapter.notifyDataSetChanged();
        }
    }

    /** Leaves the multi-selection, clearing it. */
    public void exitSelection() {
        selectionMode = false;
        selectedKeys.clear();
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    /** True when every listed contact is selected. */
    private boolean allSelected() {
        if (sortedContacts.isEmpty()) {
            return false;
        }
        for (Group group : sortedContacts) {
            if (!selectedKeys.contains(group.key)) {
                return false;
            }
        }
        return true;
    }

    /** Selects every contact, or clears them when all are already selected. */
    public void toggleSelectAll() {
        boolean all = allSelected();
        selectedKeys.clear();
        if (!all) {
            for (Group group : sortedContacts) {
                selectedKeys.add(group.key);
            }
        }
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    /**
     * Selects every contact under a letter, or clears them when all are.
     *
     * <p>Outside a selection it starts one: a whole section is the list's only
     * unit beside one contact.
     */
    private void toggleLetter(String letter) {
        List<String> keys = new ArrayList<>();
        for (Group group : sortedContacts) {
            if (letter.equals(sectionOf(group))) {
                keys.add(group.key);
            }
        }
        if (keys.isEmpty()) {
            return;
        }

        boolean all = true;
        for (String key : keys) {
            all &= selectedKeys.contains(key);
        }
        for (String key : keys) {
            if (all) {
                selectedKeys.remove(key);
            } else {
                selectedKeys.add(key);
            }
        }

        selectionMode = !selectedKeys.isEmpty();
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    /** Clears the query, which the header's field holds. */
    public void closeSearch() {
        // NOTE: the watcher clears the query only after its debounce, so
        // reset it now too, or an immediate reload filters the stale one.
        searchQuery = "";
        header.clearSearch();
    }

    /** Raises or hides the contacts' own bar buttons (not the filter). */
    private void showActions(boolean shown) {
        int visibility = shown ? View.VISIBLE : View.GONE;
        for (int id :
                new int[] {
                    R.id.contacts_birthdays,
                    R.id.contacts_duplicates,
                    R.id.contacts_transfer,
                }) {
            host.findViewById(id).setVisibility(visibility);
        }
    }

    /**
     * The contacts screen's chrome, per selection and search state.
     *
     * <p>The bar is shared, so this runs only while the contacts screen shows;
     * the host re-runs it on every return.
     */
    public void updateSelectionUi() {
        if (host.screen != Panels.CONTACTS) {
            return;
        }

        // NOTE: a selection's count shows whatever the scroll, since
        // navigating away mid-selection is not what the bar is for.
        TextView title = host.findViewById(R.id.bar_title);
        boolean navigating = !selectionMode;
        if (navigating) {
            host.showDomainTitle(Panels.CONTACTS);
        } else {
            title.setText(host.getString(R.string.selected_count, selectedKeys.size()));
            title.setAlpha(1f);
        }
        host.findViewById(R.id.bar_menu).setVisibility(navigating ? View.VISIBLE : View.GONE);
        showActions(navigating);
        host.findViewById(R.id.selection_close)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        // NOTE: merging needs at least two physical cards.
        host.findViewById(R.id.contacts_merge)
                .setVisibility(
                        selectionMode && parts.pool().distinctRefs(selectedReplicas()).size() > 1
                                ? View.VISIBLE
                                : View.GONE);
        host.findViewById(R.id.contacts_delete)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        host.findViewById(R.id.selection_all_slot)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        ((CheckBox) host.findViewById(R.id.selection_all))
                .setChecked(selectionMode && allSelected());
        host.findViewById(R.id.fab_extended)
                .setVisibility(selectionMode ? View.GONE : View.VISIBLE);
    }

    /** Confirms, then stages a delete for every selected contact. */
    private void confirmDeleteSelected() {
        new android.app.AlertDialog.Builder(host)
                .setMessage(R.string.delete_selected_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> deleteSelected())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteSelected() {
        // NOTE: deleting a merged row deletes every replica behind it, each
        // against its own account.
        for (Group group : sortedContacts) {
            if (selectedKeys.contains(group.key)) {
                for (Entry entry : group.replicas) {
                    parts.contacts().stageDelete(entry.book.url(), entry.card.id());
                }
            }
        }
        selectionMode = false;
        selectedKeys.clear();
        reload();
    }

    /** Sets a diminished sub-line, hiding it when the value is empty. */
    private void bindLine(TextView view, String value) {
        if (value == null || value.isEmpty()) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(value);
            view.setVisibility(View.VISIBLE);
        }
    }

    /** True when the linked replicas' normalized contents differ. */
    private static boolean diverged(Group group) {
        String hash = group.primary().hash;
        for (Entry entry : group.replicas) {
            if (!hash.equals(entry.hash)) {
                return true;
            }
        }
        return false;
    }

    /** The import/export button's menu: the two directions it covers. */
    private void showTransferMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(host, anchor);
        Ui.item(menu, R.string.import_contacts, transfer::importContacts);
        Ui.item(menu, R.string.export_contacts, transfer::exportContacts);
        menu.show();
    }
}
