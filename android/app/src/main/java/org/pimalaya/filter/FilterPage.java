package org.pimalaya.filter;

import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import org.pimalaya.R;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.mail.MailOffline;
import org.pimalaya.pimdir.DefaultCollection;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.Panels;

/**
 * The filter page over a domain's {@link MergedFilter}, a box per collection.
 *
 * <p>Checkboxes, not switches: an account's switch in its settings decides what
 * syncs at all. Ticks are a draft until Apply keeps them and syncs what they
 * newly take ({@link Filters#apply}); closing the page drops them.
 */
public final class FilterPage {
    /** One account and its collections, as the page lists them. */
    static final class Account {
        final String email;
        final String label;
        final List<PimdirCollections.Stored> collections;

        Account(String email, String label, List<PimdirCollections.Stored> collections) {
            this.email = email;
            this.label = label;
            this.collections = collections;
        }

        List<String> ids() {
            List<String> ids = new ArrayList<>(collections.size());
            for (PimdirCollections.Stored collection : collections) {
                ids.add(collection.id);
            }
            return ids;
        }
    }

    private final Host host;

    /** The rosters the page lists and what Apply runs. */
    private final Filters filters;

    /** Run on a filter change: the lists and the bar's filter icon follow. */
    private final Runnable filterChanged;

    /** The domain on the page, null while it is closed. */
    private PimDomain domain;

    /** Whether the page filters the Files tab, which is no domain. */
    private boolean files;

    /** The filter the page edits, applied by Apply and dropped otherwise. */
    private MergedFilter draft;

    /** The list screen the page was raised over, restored on the way out. */
    private int cameFrom = Panels.MAIL;

    /** The one account the page lists, from its settings; null for every one. */
    private String onlyAccount;

    /** Run once the page closes, for the settings it was raised over. */
    private Runnable closed;

    /** The filter page; {@code filterChanged} runs once a filter changed. */
    public FilterPage(Host host, Filters filters, Runnable filterChanged) {
        this.host = host;
        this.filters = filters;
        this.filterChanged = filterChanged;
    }

    /** Wires the page's buttons and list. */
    public void setUp() {
        host.findViewById(R.id.filter_back).setOnClickListener(view -> leave());
        host.findViewById(R.id.filter_reset)
                .setOnClickListener(
                        view -> {
                            draft.reset();
                            render();
                        });
        ((TextView) host.findViewById(R.id.filter_apply)).setTextColor(host.accentContrast());
        host.findViewById(R.id.filter_apply).setOnClickListener(view -> apply());
    }

    /** Opens the page over the list of {@code domain}. */
    public void open(PimDomain domain) {
        this.domain = domain;
        this.files = false;
        this.onlyAccount = null;
        this.closed = null;
        this.cameFrom = host.screen;
        ((TextView) host.findViewById(R.id.filter_title))
                .setText(host.getString(R.string.filter_title_of, host.getString(domain.label)));
        draft = MergedFilter.of(host, domain).draft();
        render();
        host.openOverlay(Panels.FILTER);
    }

    /**
     * Opens the page on what {@code email} syncs of {@code domain}, from the
     * account's settings (cairn/changes/one-filter-panel): its collections
     * alone, Apply syncing what it newly takes; {@code closed} runs once the
     * page is gone.
     */
    public void openSync(PimDomain domain, String email, Runnable closed) {
        this.domain = domain;
        this.files = false;
        this.onlyAccount = email;
        this.closed = closed;
        this.cameFrom = host.screen;
        ((TextView) host.findViewById(R.id.filter_title)).setText(domain.syncLabel());
        draft = MergedFilter.of(host, domain).draft();
        render();
        host.openOverlay(Panels.FILTER);
    }


    /** Opens the page over the Files tab. */
    public void openFiles() {
        this.domain = null;
        this.files = true;
        this.cameFrom = host.screen;
        ((TextView) host.findViewById(R.id.filter_title))
                .setText(
                        host.getString(
                                R.string.filter_title_of, host.getString(R.string.files_title)));
        draft = MergedFilter.files(host).draft();
        render();
        host.openOverlay(Panels.FILTER);
    }

    /** The filter on the page, a draft until applied. */
    private MergedFilter filter() {
        return draft;
    }

    /** Keeps the ticks and closes the page, syncing what they newly take. */
    private void apply() {
        MergedFilter kept = files ? MergedFilter.files(host) : MergedFilter.of(host, domain);
        PimDomain applied = files ? null : domain;
        MergedFilter ticks = draft;
        Runnable after = closed;
        leave();
        filters.apply(applied, kept, ticks, filterChanged);
        if (after != null) {
            after.run();
        }
    }

    /** Closes the page onto the screen it was opened over. */
    public void leave() {
        Runnable after = closed;
        closed = null;
        domain = null;
        draft = null;
        host.closeOverlay(Panels.FILTER);
        host.screen = cameFrom;
        host.applyChrome(cameFrom);
        if (after != null) {
            after.run();
        }
    }

    /** A default chosen: the page and the lists behind it follow. */
    private void changed() {
        render();
        filterChanged.run();
    }

    private void render() {
        LinearLayout content = host.findViewById(R.id.filter_content);
        content.removeAllViews();
        MergedFilter filter = filter();
        boolean mail = !files && domain == PimDomain.MAIL;
        boolean defaults = !files && !mail;
        String kind = domain == PimDomain.CALENDAR ? PimdirSummary.CALENDAR : PimdirSummary.CONTACT;

        for (Account account : files ? filters.filesRoster() : filters.roster(domain)) {
            if (onlyAccount != null && !onlyAccount.equals(account.email)) {
                continue;
            }
            List<String> ids = account.ids();
            MergedFilter.Tick tick = filter.tick(account.email, ids);
            content.addView(
                    row(
                            account.label,
                            null,
                            0,
                            tick,
                            true,
                            () -> {
                                filter.toggleAccount(account.email);
                                render();
                            },
                            0,
                            null));
            if (tick == MergedFilter.Tick.OFF) {
                continue;
            }

            PimdirCollections.Stored fallback =
                    defaults
                            ? DefaultCollection.of(
                                    host, account.email, kind, account.collections)
                            : null;
            for (PimdirCollections.Stored collection : account.collections) {
                boolean ticked = filter.chosen(collection.id);
                boolean isDefault = fallback != null && fallback.id.equals(collection.id);
                boolean whole = mail && MailOffline.whole(host, collection.id);
                String detail =
                        isDefault
                                ? host.getString(R.string.filter_default)
                                : whole ? host.getString(R.string.filter_downloaded) : null;
                Runnable choose =
                        defaults
                                        && !isDefault
                                        && DefaultCollection.choosable(
                                                collection, account.collections)
                                ? () -> {
                                    DefaultCollection.choose(
                                            host, account.email, kind, collection.id);
                                    changed();
                                }
                                : null;
                content.addView(
                        row(
                                mail
                                        ? filters.mail.mailboxLabel(collection)
                                        : files ? filters.files.label(collection) : collection.name,
                                detail,
                                host.dp(24),
                                ticked ? MergedFilter.Tick.ON : MergedFilter.Tick.OFF,
                                false,
                                () -> {
                                    filter.toggleCollection(collection.id);
                                    render();
                                },
                                defaults
                                        ? R.string.filter_set_default
                                        : whole
                                                ? R.string.filter_download_stop
                                                : R.string.filter_download,
                                defaults
                                        ? choose
                                        : mail ? () -> download(collection, !whole) : null));
            }
        }
    }

    /**
     * "Download this mailbox": keeps one mailbox whole, once confirmed.
     *
     * <p>Listed past its account's bound, every body downloaded by the body
     * step ({@link org.pimalaya.mail.MailBodies}). Stopping keeps what is
     * stored until the bound narrows again.
     */
    private void download(PimdirCollections.Stored collection, boolean whole) {
        if (!whole) {
            MailOffline.setWhole(host, collection.id, false);
            render();
            return;
        }
        new android.app.AlertDialog.Builder(host)
                .setTitle(R.string.filter_download_title)
                .setMessage(
                        host.getString(
                                R.string.filter_download_message,
                                filters.mail.mailboxLabel(collection)))
                .setPositiveButton(
                        R.string.filter_download,
                        (dialog, which) -> {
                            MailOffline.setWhole(host, collection.id, true);
                            render();
                            filters.sync.fillMail();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * One row: a checkbox, the name and an optional line under it.
     *
     * <p>Indented by {@code indent} for a collection; {@code action}, when
     * given, sits at the end under {@code actionLabel}: "Set as default", or a
     * mailbox's "Download" and "Stop".
     */
    private View row(
            String label,
            String detail,
            int indent,
            MergedFilter.Tick tick,
            boolean heading,
            Runnable toggle,
            int actionLabel,
            Runnable action) {
        ImageView box = new ImageView(host);
        box.setImageResource(
                tick == MergedFilter.Tick.ON
                        ? R.drawable.ic_check_box
                        : tick == MergedFilter.Tick.PARTIAL
                                ? R.drawable.ic_check_box_partial
                                : R.drawable.ic_check_box_blank);
        box.setImageTintList(
                ColorStateList.valueOf(
                        tick == MergedFilter.Tick.OFF
                                ? host.resolveColor(android.R.attr.textColorSecondary)
                                : host.resolveColor(android.R.attr.colorAccent)));

        TextView name = new TextView(host);
        name.setText(label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTextColor(host.resolveColor(android.R.attr.textColorPrimary));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (heading) {
            name.setTypeface(null, android.graphics.Typeface.BOLD);
        }

        LinearLayout text = new LinearLayout(host);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(name);
        if (detail != null) {
            TextView line = new TextView(host);
            line.setText(detail);
            line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            line.setTextColor(host.resolveColor(android.R.attr.colorAccent));
            text.addView(line);
        }

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(host.dp(48));
        row.setPadding(host.dp(16) + indent, 0, host.dp(16), 0);
        row.setBackgroundResource(host.ui.resolveAttr(android.R.attr.selectableItemBackground));
        row.addView(box, new LinearLayout.LayoutParams(host.dp(24), host.dp(24)));
        LinearLayout.LayoutParams textParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginStart(host.dp(16));
        row.addView(text, textParams);
        row.setOnClickListener(view -> toggle.run());
        if (action != null) {
            TextView set = new TextView(host);
            set.setText(actionLabel);
            set.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            set.setTextColor(host.resolveColor(android.R.attr.colorAccent));
            set.setGravity(Gravity.CENTER_VERTICAL);
            set.setMinHeight(host.dp(48));
            set.setPadding(host.dp(12), 0, 0, 0);
            set.setOnClickListener(view -> action.run());
            row.addView(set);
        }
        return row;
    }
}
