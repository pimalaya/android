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

    /**
     * The panel's sections: the books, the kinds, the categories and the
     * properties ticked, none ticked meaning any.
     */
    private static final String BOOKS = "books_on";

    private static final String HAS = "has";

    private static final String KINDS = "kinds_on";

    private static final String CATEGORIES = "categories_on";

    /** A card carrying no category, None under Categories. */
    private static final String NO_CATEGORY = " none";

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

    /** Lower-cased search words; empty shows all. */
    private String searchQuery = "";

    /**
     * The cards the search index finds the words in (pimdir SEARCH §8), by
     * account and seq; null when it answered nothing.
     */
    private Set<String> searchHits;

    /** Counts the searches asked, so only the last one's hits land. */
    private int searchAsked;

    /**
     * What the index recorded of each card (pimdir SEARCH §6), by collection
     * and seq, or the same read off each body when the index answers none.
     */
    private Map<String, CardFacts.Indexed> facts = new java.util.HashMap<>();

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
                        view -> new Birthdays(host).show(withBirthdays()));
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
                    search();
                });
        filter = new ListFilter(host, "contacts", BOOKS, KINDS, CATEGORIES, HAS);
        panel = new FilterPanel(host, header, this::render);
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
                    // NOTE: what the Has filter and the categories test is
                    // the index's; a store whose index answers none reads
                    // it off the bodies, as the list did before.
                    Map<String, CardFacts.Indexed> read = parts.contacts().facts();
                    Map<String, CardFacts.Indexed> known =
                            read != null ? read : CardFacts.fromBodies(entries);
                    host.postAlive(
                            () -> {
                                contacts = entries;
                                groupedContacts = groups;
                                facts = known;
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
                if (entry.id.equals(linkId)) {
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
     * Whether a card passes Kind, Categories and Has: its kind ticked, any of
     * its categories (lowercased, or None) ticked, any property ticked, each
     * passing with none ticked.
     *
     * <p>Its email and organization are its summary's, what else it holds
     * and its categories the index's ({@link #facts}): no body is read.
     */
    private boolean described(ListFilter.State kept, Entry entry) {
        CardFacts.Indexed known = facts.get(entry.key());
        Set<String> has = kept.set(HAS);
        if (!has.isEmpty()) {
            boolean any = false;
            for (String property : has) {
                if ("EMAIL".equals(property)) {
                    any |= !entry.email.isEmpty();
                } else if ("ORG".equals(property)) {
                    any |= !entry.info.isEmpty();
                } else {
                    any |= known != null && known.has.contains(property);
                }
            }
            if (!any) {
                return false;
            }
        }
        Set<String> kinds = kept.set(KINDS);
        if (!kinds.isEmpty() && !kinds.contains(entry.kind)) {
            return false;
        }
        Set<String> on = kept.set(CATEGORIES);
        if (on.isEmpty()) {
            return true;
        }
        List<String> categories = known == null ? List.of() : known.categories;
        if (categories.isEmpty()) {
            return on.contains(NO_CATEGORY);
        }
        for (String category : categories) {
            if (on.contains(category)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a card is what the search words find: its name, email or
     * organization holding them, or the search index finding them anywhere
     * in it (pimdir SEARCH §8).
     */
    private boolean searched(Entry entry) {
        if (searchQuery.isEmpty()) {
            return true;
        }
        String words = searchQuery.toLowerCase(java.util.Locale.ROOT);
        for (String shown : new String[] {entry.name, entry.email, entry.info}) {
            if (shown.toLowerCase(java.util.Locale.ROOT).contains(words)) {
                return true;
            }
        }
        return searchHits != null && searchHits.contains(entry.hit());
    }

    /**
     * Asks the search index for the words off the main thread, then renders;
     * the name, email and organization match renders at once.
     */
    private void search() {
        int asked = ++searchAsked;
        String words = searchQuery;
        searchHits = null;
        render();
        if (words.isEmpty()) {
            return;
        }
        host.io.execute(
                () -> {
                    Set<String> found = parts.contacts().found(words);
                    host.postAlive(
                            () -> {
                                if (asked == searchAsked && found != null) {
                                    searchHits = found;
                                    render();
                                }
                            });
                });
    }

    /**
     * The rows on screen whose cards hold a birthday, as the index records it:
     * the only bodies the birthday peek reads.
     */
    private List<Group> withBirthdays() {
        List<Group> found = new ArrayList<>();
        for (Group group : sortedContacts) {
            for (Entry entry : group.replicas) {
                CardFacts.Indexed known = facts.get(entry.key());
                if (known != null && known.has.contains("BDAY")) {
                    found.add(group);
                    break;
                }
            }
        }
        return found;
    }

    /**
     * The filter panel: the books synced under their accounts, any hidden
     * from this list alone, then the kinds, the categories, what it has.
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
        ListFilter.State edited = filter.applied();
        Runnable changed =
                () -> {
                    filter.save();
                    render();
                };
        panel.begin();
        panel.collections("books", R.string.filter_books, books, edited.set(BOOKS), changed);
        panel.checks(
                "kinds", R.string.filter_kind, KIND_KEYS, KIND_LABELS, edited.set(KINDS), changed);
        // NOTE: None first, for the cards carrying no category.
        Map<String, String> names = new LinkedHashMap<>();
        names.put(NO_CATEGORY, host.getString(R.string.filter_none));
        // NOTE: the index records a category folded, so it is offered so.
        java.util.TreeSet<String> recorded = new java.util.TreeSet<>();
        for (CardFacts.Indexed known : facts.values()) {
            recorded.addAll(known.categories);
        }
        for (String category : recorded) {
            names.putIfAbsent(category, category);
        }
        panel.checks(
                "categories",
                R.string.filter_categories,
                names.keySet().toArray(new String[0]),
                names.values().toArray(new String[0]),
                null,
                edited.set(CATEGORIES),
                changed);
        panel.checks("has", R.string.filter_has, HAS_KEYS, HAS_LABELS, edited.set(HAS), changed);
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
        for (Group group : groupedContacts) {
            // NOTE: hiding an account thins a row's replicas, and only empties
            // the row when that account held all of them.
            boolean matches = false;
            for (Entry entry : group.replicas) {
                if (!synced.accepts(entry.accountEmail, entry.book.url())
                        || !kept.set(BOOKS).isEmpty() && !kept.set(BOOKS).contains(entry.book.url())
                        || !described(kept, entry)) {
                    continue;
                }
                if (searched(entry)) {
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
            // NOTE: seeded by the body's hash, as it was by the body's text.
            Avatar.paint(avatar, entry.object());
            boolean checked = selectedKeys.contains(group.key);
            avatar.setVisibility(checked ? View.INVISIBLE : View.VISIBLE);
            android.widget.ImageView check = row.findViewById(R.id.contact_check);
            check.setVisibility(checked ? View.VISIBLE : View.GONE);
            check.setImageTintList(
                    android.content.res.ColorStateList.valueOf(host.accentContrast()));

            ((TextView) row.findViewById(R.id.contact_name)).setText(name);

            // NOTE: what the summary holds (pimdir STORAGE Annex A.2, A.6):
            // the first email, else the organization.
            String subtitle = entry.email.isEmpty() ? entry.info : entry.email;
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
                    group.conflicted() || group.diverged ? View.VISIBLE : View.GONE);

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
                    parts.contacts().stageDelete(entry.book.url(), entry.id);
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

    /** The import/export button's menu: the two directions it covers. */
    private void showTransferMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(host, anchor);
        Ui.item(menu, R.string.import_contacts, transfer::importContacts);
        Ui.item(menu, R.string.export_contacts, transfer::exportContacts);
        menu.show();
    }
}
