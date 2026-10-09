package org.pimalaya;

import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A list's filter page: each account with a checkbox, its collections
 * indented below with theirs, over the domain's {@link MergedFilter}.
 *
 * <p>Checkboxes rather than switches, to stay apart from an account's on and
 * off in the drawer, which decides what syncs at all: an account that is off
 * is not listed here. An account's box reads partly ticked while some of its
 * collections are hidden. Every tick applies at once, the list behind
 * narrowing as it goes, and is kept.
 *
 * <p>On calendars and address books, the account's default collection says
 * so, and a writable one of an account whose source names no default offers
 * "Set as default" ({@link DefaultCollection}). A mailbox offers "Download"
 * instead, which keeps it whole offline ({@link MailOffline}).
 */
final class FilterPage {
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

    private final MainActivity host;

    /** The domain on the page, null while it is closed. */
    private PimDomain domain;

    /** The list screen the page was raised over, restored on the way out. */
    private int cameFrom = MainActivity.PANEL_MAIL;

    FilterPage(MainActivity host) {
        this.host = host;
    }

    void setUp() {
        host.findViewById(R.id.filter_back).setOnClickListener(view -> leave());
        host.findViewById(R.id.filter_reset)
                .setOnClickListener(
                        view -> {
                            host.filterOf(domain).reset();
                            changed();
                        });
    }

    /** Opens the page over the list of {@code domain}. */
    void open(PimDomain domain) {
        this.domain = domain;
        this.cameFrom = host.screen;
        ((TextView) host.findViewById(R.id.filter_title))
                .setText(host.getString(R.string.filter_title_of, host.getString(domain.label)));
        render();
        host.openOverlay(MainActivity.PANEL_FILTER);
    }

    /** Closes the page onto the list it was opened over. */
    void leave() {
        domain = null;
        host.closeOverlay(MainActivity.PANEL_FILTER);
        host.screen = cameFrom;
        host.applyChrome(cameFrom);
    }

    /** A tick flipped: the page and the lists behind it follow. */
    private void changed() {
        render();
        host.filterChanged();
    }

    private void render() {
        LinearLayout content = host.findViewById(R.id.filter_content);
        content.removeAllViews();
        MergedFilter filter = host.filterOf(domain);
        boolean defaults = domain != PimDomain.MAIL;
        String kind = domain == PimDomain.CALENDAR ? PimdirSummary.CALENDAR : PimdirSummary.CONTACT;

        for (Account account : host.filterRoster(domain)) {
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
                                filter.toggleAccount(account.email, ids);
                                changed();
                            },
                            0,
                            null));

            PimdirCollections.Stored fallback =
                    defaults
                            ? DefaultCollection.of(
                                    host, account.email, kind, account.collections)
                            : null;
            for (PimdirCollections.Stored collection : account.collections) {
                boolean ticked = filter.ticked(account.email, collection.id);
                boolean isDefault = fallback != null && fallback.id.equals(collection.id);
                boolean whole = !defaults && MailOffline.whole(host, collection.id);
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
                                domain == PimDomain.MAIL
                                        ? host.mail.mailboxLabel(collection)
                                        : collection.name,
                                detail,
                                host.dp(24),
                                ticked ? MergedFilter.Tick.ON : MergedFilter.Tick.OFF,
                                false,
                                () -> {
                                    filter.toggleCollection(account.email, collection.id, ids);
                                    changed();
                                },
                                defaults
                                        ? R.string.filter_set_default
                                        : whole ? R.string.filter_download_stop : R.string.filter_download,
                                defaults ? choose : () -> download(collection, !whole)));
            }
        }
    }

    /**
     * "Download this mailbox": keeps one mailbox whole, listed past its
     * account's bound and every body downloaded by the body step
     * ({@link MailBodies}), once confirmed. Stopping keeps what is stored
     * until the bound is narrowed again.
     */
    private void download(PimdirCollections.Stored collection, boolean whole) {
        if (!whole) {
            MailOffline.setWhole(host, collection.id, false);
            render();
            return;
        }
        new android.app.AlertDialog.Builder(host)
                .setTitle(R.string.filter_download_title)
                .setMessage(host.getString(R.string.filter_download_message, host.mail.mailboxLabel(collection)))
                .setPositiveButton(
                        R.string.filter_download,
                        (dialog, which) -> {
                            MailOffline.setWhole(host, collection.id, true);
                            render();
                            host.fillMail();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * One row: the checkbox at the start, the name beside it with an
     * optional line under it, indented by {@code indent} for a collection;
     * {@code action}, when given, is offered at the end under {@code actionLabel}:
     * "Set as default", or a mailbox's "Download" and "Stop".
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
        row.setBackgroundResource(android.R.drawable.list_selector_background);
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
