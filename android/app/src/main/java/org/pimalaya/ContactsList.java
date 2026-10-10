package org.pimalaya;

import android.view.View;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The contacts screen: the merged list grouped into one card per
 * letter, its recycling adapter, the header search and the multi-select
 * mode. It owns the display state
 * (the replica pool, the grouped rows, the sorted-and-filtered rows,
 * the selection and search flags) and rebuilds it off the main thread
 * through {@link ContactPool}; the host keeps the chrome bar it shares
 * with the other screens (re-running {@link #updateSelectionUi} on
 * every return) and the flows that leave the screen (opening a contact,
 * merging, importing, syncing).
 */
final class ContactsList {
    private final MainActivity host;

    /** The replica pool: every subscribed book's cards, unmerged. */
    private List<Entry> contacts = new ArrayList<>();

    /** The merged rows as the bridge grouped them, unfiltered. */
    private List<Group> groupedContacts = new ArrayList<>();

    /** The merged rows, filtered by the search query and sorted; backs
     *  the adapter. */
    private List<Group> sortedContacts = new ArrayList<>();

    private final Adapter adapter = new Adapter();
    private final CardSections<Group> sections = new CardSections<>();
    private ListHeader header;

    /** Multi-select state, keyed by merged group. */
    private boolean selectionMode;

    private final Set<String> selectedKeys = new java.util.HashSet<>();

    /** Lower-cased raw-vCard filter; empty shows all. */
    private String searchQuery = "";

    /** Whether the Default chip narrows the list to the shown accounts' default books. */
    private boolean defaultOnly;

    ContactsList(MainActivity host) {
        this.host = host;
    }

    /** Wires the list, its bar buttons, the header search and the pull-down. */
    void setUp() {
        // Pull-to-refresh runs the same syncAll as the drawer; its own
        // spinner retracts right away, the sync strip carries the wait.
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout refresh =
                host.findViewById(R.id.contacts_refresh);
        refresh.setColorSchemeColors(host.ui.resolveColor(android.R.attr.colorAccent));
        refresh.setOnRefreshListener(
                () -> {
                    refresh.setRefreshing(false);
                    host.syncContacts();
                });

        host.findViewById(R.id.contacts_birthdays)
                .setOnClickListener(
                        view -> new Birthdays(host).show(new ArrayList<>(sortedContacts)));
        host.findViewById(R.id.contacts_duplicates)
                .setOnClickListener(
                        view -> new DuplicateReview(host).find(new ArrayList<>(contacts)));
        host.findViewById(R.id.contacts_transfer).setOnClickListener(this::showTransferMenu);
        host.findViewById(R.id.contacts_merge).setOnClickListener(view -> mergeSelected());
        host.findViewById(R.id.contacts_delete)
                .setOnClickListener(view -> confirmDeleteSelected());

        ListView list = host.findViewById(R.id.contacts_list);
        header = new ListHeader(host, list, MainActivity.PANEL_CONTACTS);
        header.empty(host.findViewById(R.id.contacts_empty));
        header.search(
                R.string.contacts_search,
                query -> {
                    searchQuery = query;
                    render();
                });
        Chips.add(
                host,
                header.chips(),
                R.string.chip_default,
                R.drawable.ic_star,
                on -> {
                    defaultOnly = on;
                    render();
                });
        list.setAdapter(adapter);

        // A letter's header selects its whole section, the only unit the
        // list has beside one contact; a row opens or, during a
        // selection, toggles.
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
                        host.openGroup(group);
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

    ListHeader header() {
        return header;
    }

    /**
     * Rebuilds the contacts list from the base (staged edits included):
     * the full table scan and the bridge grouping run on the io
     * executor, and the render lands back on the main thread; the io
     * executor is single-threaded, so overlapping reloads apply in
     * order. The account snapshot keeps the grouping off the live
     * main-thread cache.
     */
    void reload() {
        List<AccountEntry> snapshot = new ArrayList<>(host.accounts);
        host.io.execute(
                () -> {
                    List<Entry> entries = host.pool.loadEntries();
                    List<Group> groups = host.pool.group(entries, snapshot);
                    host.postAlive(
                            () -> {
                                contacts = entries;
                                groupedContacts = groups;
                                render();
                            });
                });
    }

    /** The rows currently on screen (search-filtered), for export and
     *  the birthday peek. */
    List<Group> visibleGroups() {
        return sortedContacts;
    }

    /** The merged row holding a card of this key, filter or no filter; null for none. */
    Group groupOf(String linkId) {
        for (Group group : groupedContacts) {
            for (Entry entry : group.replicas) {
                if (entry.card.id.equals(linkId)) {
                    return group;
                }
            }
        }
        return null;
    }

    boolean isSelectionMode() {
        return selectionMode;
    }

    boolean isSearchOpen() {
        return !searchQuery.isEmpty();
    }

    /** Re-applies the filter and the query to the rows already grouped. */
    void reRender() {
        render();
    }

    /**
     * Filters the grouped rows by the search query and refreshes the
     * list; a group matches when any of its replicas does. Conflicts
     * float to the top so they cannot be missed.
     */
    private void render() {
        updateSelectionUi();

        sortedContacts = new ArrayList<>();
        MergedFilter filter = host.filterOf(PimDomain.CONTACTS);
        Set<String> defaults = defaultOnly ? host.shownDefaults(PimDomain.CONTACTS) : null;
        for (Group group : groupedContacts) {
            // A merged row survives when any replica of it passes both
            // the filter axes and the query: hiding an account thins a
            // row's replicas, and only empties the row when that account
            // held all of them.
            boolean matches = false;
            for (Entry entry : group.replicas) {
                if (!filter.accepts(entry.accountEmail, entry.book.url)
                        || defaults != null && !defaults.contains(entry.book.url)) {
                    continue;
                }
                if (searchQuery.isEmpty()
                        || entry.card.vcard.toLowerCase().contains(searchQuery)) {
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

        // One empty state for every cause: a search miss, an empty
        // addressbook, or no account.
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
    private final class Adapter extends android.widget.BaseAdapter {
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
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            if (sections.isHeader(position)) {
                return sections.headerView(position, convertView, parent);
            }

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
            avatar.setBackground(Avatar.circle(host, entry.card != null ? entry.card.vcard : name));
            boolean checked = selectedKeys.contains(group.key);
            avatar.setVisibility(checked ? View.INVISIBLE : View.VISIBLE);
            android.widget.ImageView check = row.findViewById(R.id.contact_check);
            check.setVisibility(checked ? View.VISIBLE : View.GONE);
            check.setImageTintList(
                    android.content.res.ColorStateList.valueOf(host.accentContrast()));

            ((TextView) row.findViewById(R.id.contact_name)).setText(name);

            // One supporting line: the phone, else the email, else the
            // card's fallback info.
            String subtitle = entry.phone;
            if (subtitle == null || subtitle.isEmpty()) {
                subtitle = entry.email;
            }
            if (subtitle == null || subtitle.isEmpty()) {
                subtitle = entry.info;
            }
            bindLine(row.findViewById(R.id.contact_subtitle), subtitle);

            // The link glyph and card count, only for a contact backed
            // by several physical cards.
            int cards = host.pool.distinctRefs(group.replicas).size();
            row.findViewById(R.id.contact_link_icon)
                    .setVisibility(cards > 1 ? View.VISIBLE : View.GONE);
            TextView links = row.findViewById(R.id.contact_links);
            links.setVisibility(cards > 1 ? View.VISIBLE : View.GONE);
            links.setText(String.valueOf(cards));

            ((TextView) row.findViewById(R.id.contact_origin)).setText(originOf(group));
            host.ui.syncMark(row.findViewById(R.id.contact_unsynced), group.unsynced(), false);

            // The warning flag for a conflict or divergence, ending the
            // name's line.
            android.widget.ImageView danger = row.findViewById(R.id.contact_diverged);
            danger.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            host.ui.resolveColor(android.R.attr.colorError)));
            danger.setVisibility(
                    group.conflicted() || diverged(group) ? View.VISIBLE : View.GONE);

            // A contact living only in the hidden local book (attached to
            // no addressbook) shows muted.
            row.setAlpha(attachedToNoBook(group) ? 0.5f : 1f);

            return row;
        }
    }

    /**
     * Where a contact lives: its addressbook, then its account, the way
     * a mail row names its mailbox and account. A contact on several
     * cards names the first one held by an account, the on-device book
     * being where cards wait rather than where they belong.
     */
    private String originOf(Group group) {
        for (Entry entry : group.replicas) {
            if (!LocalBook.is(entry.accountEmail)) {
                return entry.book.name + " · " + entry.accountEmail;
            }
        }
        String book = group.primary().book.name;
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
     * Merges the selected rows into one single card through the merge
     * form (the host owns the survivor choice and the fan-out).
     */
    private void mergeSelected() {
        host.mergeReplicas(selectedReplicas());
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

    void exitSelection() {
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
    void toggleSelectAll() {
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
     * Selects every contact under a letter, or clears them when they are
     * all already selected: a letter header's tap.
     *
     * <p>Outside a selection it starts one, so the button does something
     * wherever it is pressed. A long press on a row starts a selection
     * from one contact; this starts one from a whole section, which is
     * the only other unit the list has.
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
    void closeSearch() {
        // NOTE: the watcher clears the query only after its debounce, so
        // reset it now too, or an immediate reload filters the stale one.
        searchQuery = "";
        header.clearSearch();
    }

    /**
     * Raises or hides the contacts' own bar buttons: birthdays,
     * duplicates and import/export. The filter is every list's and
     * follows its own rule.
     */
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
     * The contacts screen's chrome, per selection and search state (the
     * bar is shared across screens, so this only runs while the
     * contacts screen shows; the host's chrome re-runs it on every
     * return).
     */
    void updateSelectionUi() {
        if (!host.onContactsScreen()) {
            return;
        }

        // A selection takes the domain name's place with its count,
        // shown whatever the scroll, since navigating away mid-selection
        // is not what the bar is for.
        TextView title = host.findViewById(R.id.bar_title);
        boolean navigating = !selectionMode;
        if (navigating) {
            host.showDomainTitle(MainActivity.PANEL_CONTACTS);
        } else {
            title.setText(host.getString(R.string.selected_count, selectedKeys.size()));
            title.setAlpha(1f);
        }
        host.findViewById(R.id.bar_menu).setVisibility(navigating ? View.VISIBLE : View.GONE);
        showActions(navigating);
        host.findViewById(R.id.bar_filter)
                .setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        host.findViewById(R.id.selection_close)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        // Merging needs at least two physical cards.
        host.findViewById(R.id.contacts_merge)
                .setVisibility(
                        selectionMode && host.pool.distinctRefs(selectedReplicas()).size() > 1
                                ? View.VISIBLE
                                : View.GONE);
        host.findViewById(R.id.contacts_delete)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        // The select-all box, checked when every contact is selected.
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
        // Deleting a merged row deletes every replica behind it, each
        // against its own account.
        for (Group group : sortedContacts) {
            if (selectedKeys.contains(group.key)) {
                for (Entry entry : group.replicas) {
                    host.contacts.stageDelete(entry.book.url, entry.card.id);
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
        MainActivity.item(menu, R.string.import_contacts, host::importContacts);
        MainActivity.item(menu, R.string.export_contacts, host::exportContacts);
        menu.show();
    }
}
