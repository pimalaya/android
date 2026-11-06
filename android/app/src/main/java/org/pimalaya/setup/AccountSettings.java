package org.pimalaya.setup;

import android.app.AlertDialog;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;
import org.pimalaya.R;
import org.pimalaya.account.AccountActivation;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.Endpoints;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.calendar.PhoneCalendars;
import org.pimalaya.client.Account;
import org.pimalaya.client.Protocol;
import org.pimalaya.contact.BookEntry;
import org.pimalaya.contact.PimdirContacts;
import org.pimalaya.mail.MailScope;
import org.pimalaya.mail.ComposeFormat;
import org.pimalaya.mail.SenderName;
import org.pimalaya.mail.WindowPicker;
import org.pimalaya.phone.Accounts;
import org.pimalaya.phone.PhoneMirror;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.sync.BackgroundCheck;
import org.pimalaya.sync.SyncCoordinator;
import org.pimalaya.sync.SyncPasses;
import org.pimalaya.sync.SyncStamps;
import org.pimalaya.task.TaskStore;
import org.pimalaya.ui.Avatar;
import org.pimalaya.ui.CardRows;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.Panels;
import org.pimalaya.ui.PillField;
import org.pimalaya.ui.Sections;
import org.pimalaya.ui.SyncPill;

/**
 * The full-screen account settings page, opened over the drawer.
 *
 * <p>One account's {@link Sections} cards under its identity. Every change
 * applies when made, so the page says what the account does. The activity
 * keeps the navigation and calls {@link #open} and {@link #leave}.
 */
public final class AccountSettings {
    private final Host host;
    private final SyncPasses.Parts parts;
    private final PimdirContacts contacts;

    /** The activity's live account list (mutated on the main thread). */
    private final List<AccountEntry> accounts;

    private final SyncCoordinator sync;

    /** The mail list's read executor, the window picker's counts run on. */
    private final Executor mailReads;

    private final SetupShell shell;

    /** The account whose settings screen is open (null outside it). */
    private String settingsEmail;

    /**
     * The list screen the drawer was raised over, restored on the way out.
     *
     * <p>That list is still under the overlay and the drawer: landing the
     * chrome on another would leave the bar describing a screen nobody
     * navigated to.
     */
    private int cameFrom = Panels.MAIL;

    /**
     * Whether another app fills the phone's Contacts app with this address.
     *
     * <p>{@link Accounts#elsewhere}, looked for as the page opens.
     */
    private boolean elsewhere;

    /** The same for the calendars ({@link Accounts#calendarsElsewhere}). */
    private boolean calendarsElsewhere;

    /** Builds the page; {@link #bind} wires it once laid out. */
    /** Opens what an account syncs of a domain, from its row; the activity's. */
    public java.util.function.BiConsumer<PimDomain, String> chooseSync;

    public AccountSettings(
            Host host,
            SyncPasses.Parts parts,
            PimdirContacts contacts,
            List<AccountEntry> accounts,
            SyncCoordinator sync,
            Executor mailReads,
            SetupShell shell) {
        this.host = host;
        this.parts = parts;
        this.contacts = contacts;
        this.accounts = accounts;
        this.sync = sync;
        this.mailReads = mailReads;
        this.shell = shell;
    }

    /** Wires the page's bar, scroll and remove button, once laid out. */
    public void bind() {
        // NOTE: the address moves into the bar once the identity block's
        // own address has scrolled under it, as the lists hand over their
        // large title.
        ScrollView scroll = host.findViewById(R.id.account_scroll);
        scroll.setOnScrollChangeListener(
                (view, x, y, oldX, oldY) -> {
                    View address = host.findViewById(R.id.account_address);
                    View identity = host.findViewById(R.id.account_identity);
                    boolean gone = y >= identity.getTop() + address.getBottom();
                    View title = host.findViewById(R.id.account_title);
                    float alpha = gone ? 1f : 0f;
                    if (title.getAlpha() != alpha) {
                        title.animate().alpha(alpha).setDuration(150);
                    }
                });
        host.findViewById(R.id.account_back).setOnClickListener(view -> leave());
        host.findViewById(R.id.account_delete)
                .setOnClickListener(view -> confirmDeleteAccount(settingsEmail));
    }

    /** Opens one account's settings screen, fading in over the drawer. */
    public void open(String email) {
        settingsEmail = email;
        cameFrom = host.screen;

        ((TextView) host.findViewById(R.id.account_title)).setText(email);
        host.findViewById(R.id.account_title).setAlpha(0f);
        host.findViewById(R.id.account_scroll).scrollTo(0, 0);
        elsewhere = false;
        calendarsElsewhere = false;
        render();
        host.openOverlay(Panels.ACCOUNT);

        // NOTE: one provider query each, off the main thread.
        host.io.execute(
                () -> {
                    boolean found = Accounts.elsewhere(host, email);
                    boolean calendars = Accounts.calendarsElsewhere(host, email);
                    host.postAlive(
                            () -> {
                                if ((found || calendars) && email.equals(settingsEmail)) {
                                    elsewhere = found;
                                    calendarsElsewhere = calendars;
                                    render();
                                }
                            });
                });
    }

    /**
     * Rebuilds the page from what the account holds now, drawn as the setup's
     * settings page: the account's own card, one card of its domains each led
     * by its tile, then where it signs in.
     */
    private void render() {
        String email = settingsEmail;
        AccountEntry account = AccountEntry.find(accounts, email);
        if (account == null) {
            return;
        }
        renderIdentity(account);

        LinearLayout content = host.findViewById(R.id.account_content);
        Sections sections = new Sections(host, content);
        sections.clear();
        label(content, R.string.settings_account_label);
        addAccount(sections, account);
        label(content, R.string.settings_domains_label);
        addDomains(sections, content, account);
        label(content, R.string.settings_signin_label);
        addServers(sections, account);

        boolean contactsOnly =
                !account.covers(PimDomain.MAIL) && !account.covers(PimDomain.CALENDAR);
        ((TextView) host.findViewById(R.id.account_delete_note))
                .setText(
                        contactsOnly
                                ? R.string.delete_account_contacts
                                : R.string.delete_account_all);
    }

    /** A label over the next card, naming what it holds. */
    private void label(LinearLayout content, int text) {
        TextView label = new TextView(host);
        label.setText(text);
        label.setTextColor(host.resolveColor(android.R.attr.textColorSecondary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setAllCaps(true);
        label.setLetterSpacing(0.02f);
        int gutter = host.getResources().getDimensionPixelSize(R.dimen.card_gutter);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(gutter + host.dp(4));
        params.setMarginEnd(gutter + host.dp(4));
        params.topMargin = host.dp(content.getChildCount() == 0 ? 8 : 20);
        content.addView(label, params);
    }

    /** The disc, the address, what it covers and where it stands. */
    private void renderIdentity(AccountEntry account) {
        String email = account.email;
        TextView avatar = host.findViewById(R.id.account_avatar);
        avatar.setText(Avatar.letter(email));
        Avatar.paint(avatar, email);
        ((TextView) host.findViewById(R.id.account_address)).setText(email);

        List<String> covered = new ArrayList<>();
        for (PimDomain domain : PimDomain.values()) {
            if (account.covers(domain)) {
                covered.add(host.getString(domain.label));
            }
        }
        ((TextView) host.findViewById(R.id.account_domains))
                .setText(android.text.TextUtils.join(" · ", covered));

        FrameLayout pill = host.findViewById(R.id.account_pill);
        pill.removeAllViews();
        pill.addView(
                SyncPill.of(
                        host,
                        AccountActivation.enabled(host, email),
                        SyncStamps.at(host, email),
                        sync.bodiesLeft(email)),
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /**
     * The account's own card: whether it is used at all, and how often it
     * syncs while the app is closed ({@link BackgroundCheck}).
     *
     * <p>Off, the account syncs nothing; the lists and their filters leave it
     * out ({@link AccountActivation}).
     */
    private void addAccount(Sections sections, AccountEntry account) {
        String email = account.email;
        List<View> rows = new ArrayList<>();
        Switch toggle = new Switch(host);
        toggle.setChecked(AccountActivation.enabled(host, email));
        toggle.setOnCheckedChangeListener(
                (view, checked) -> {
                    AccountActivation.set(host, email, checked);
                    shell.reload(PimDomain.CONTACTS);
                    shell.filterChanged();
                    render();
                });
        rows.add(
                switchRow(
                        sections,
                        host.getString(R.string.account_enable),
                        host.getString(R.string.account_enable_note),
                        toggle));

        int current = 0;
        for (int index = 0; index < BackgroundCheck.INTERVALS.length; index++) {
            if (BackgroundCheck.INTERVALS[index] == BackgroundCheck.interval(host, email)) {
                current = index;
            }
        }
        int shown = current;
        String[] intervals = host.getResources().getStringArray(R.array.background_intervals);
        rows.add(
                sections.row(
                        host.getString(R.string.background_sync),
                        intervals[shown],
                        chevron(),
                        () ->
                                choose(
                                        R.string.background_sync,
                                        R.string.background_sync_note,
                                        R.array.background_intervals,
                                        shown,
                                        picked -> {
                                            BackgroundCheck.setInterval(
                                                    host, email, BackgroundCheck.INTERVALS[picked]);
                                            render();
                                        })));
        sections.section(0, 0, rows, null, false);
    }

    /**
     * The domains card, as the setup's settings page: a section per domain
     * the account covers, led by its tile, its rows the setup's first.
     */
    private void addDomains(Sections sections, LinearLayout content, AccountEntry account) {
        String email = account.email;
        LinearLayout card = CardRows.card(host);
        if (account.covers(PimDomain.MAIL)) {
            domainSection(
                    card,
                    PimDomain.MAIL.icon,
                    host.getString(PimDomain.MAIL.label),
                    mailRows(sections, account));
            domainSection(
                    card,
                    R.drawable.ic_send,
                    host.getString(R.string.signin_sending),
                    sendingRows(sections, account));
        }
        if (account.covers(PimDomain.CONTACTS)) {
            domainSection(
                    card,
                    PimDomain.CONTACTS.icon,
                    host.getString(PimDomain.CONTACTS.label),
                    contactsRows(sections, email));
        }
        if (account.covers(PimDomain.CALENDAR)) {
            domainSection(
                    card,
                    PimDomain.CALENDAR.icon,
                    host.getString(PimDomain.CALENDAR.label),
                    calendarRows(sections, email));
        }
        if (card.getChildCount() == 0) {
            return;
        }
        int gutter = host.getResources().getDimensionPixelSize(R.dimen.card_gutter);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(gutter);
        params.setMarginEnd(gutter);
        params.topMargin = gutter;
        content.addView(card, params);
    }

    /**
     * A domain's section of the domains card: its tile, picked, and its name,
     * then its rows, under a hairline inset past the tiles.
     */
    private void domainSection(LinearLayout card, int glyph, String name, List<View> rows) {
        if (card.getChildCount() > 0) {
            int inset =
                    host.dp(28) + host.getResources().getDimensionPixelSize(R.dimen.domain_tile);
            card.addView(CardRows.rowDivider(host, inset));
        }
        LinearLayout header = CardRows.tileRow(host, true, glyph, name, null, null);
        header.setForeground(null);
        card.addView(header);
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) {
                View line = new View(host);
                line.setBackgroundColor(host.getColor(R.color.divider_light));
                LinearLayout.LayoutParams lineParams =
                        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
                lineParams.setMarginStart(host.dp(16));
                card.addView(line, lineParams);
            }
            card.addView(rows.get(index));
        }
    }

    /**
     * Mail's rows: the setup's (notifications, subscribed mailboxes, sync
     * period), then the window of mail on the phone and rich writing.
     *
     * <p>The window is since when its mail is on the phone ({@link
     * org.pimalaya.mail.MailWindow}).
     */
    private List<View> mailRows(Sections sections, AccountEntry account) {
        String email = account.email;
        List<View> rows = new ArrayList<>();

        // NOTE: off while the permission is missing, revoked in the system
        // settings included, so turning it on asks again; idle while the
        // account does not sync in the background, which it needs.
        boolean syncs = BackgroundCheck.syncs(host, email);
        Switch notify = new Switch(host);
        notify.setChecked(BackgroundCheck.notifies(host, email) && BackgroundCheck.permitted(host));
        notify.setEnabled(syncs);
        notify.setOnCheckedChangeListener(
                (view, checked) -> {
                    if (!checked) {
                        BackgroundCheck.setNotifies(host, email, false);
                        return;
                    }
                    shell.askNotifications(
                            granted -> {
                                BackgroundCheck.setNotifies(host, email, granted);
                                notify.setChecked(granted);
                            });
                });
        View notifyRow =
                switchRow(
                        sections,
                        host.getString(R.string.background_notify),
                        host.getString(
                                syncs ? R.string.notify_mail_note : R.string.notify_needs_background),
                        notify);
        notifyRow.setEnabled(syncs);
        notifyRow.setAlpha(syncs ? 1f : 0.5f);
        rows.add(notifyRow);

        rows.add(subscribedRow(sections, PimDomain.MAIL, R.string.subscribed_mailboxes, email));

        String[] scopes = host.getResources().getStringArray(R.array.mail_scopes);
        int scope = scopeIndex(parts.mail().monthsOf(email));
        rows.add(
                sections.row(
                        host.getString(R.string.mail_scope_title),
                        scopes[scope],
                        chevron(),
                        () ->
                                choose(
                                        R.string.mail_scope_title,
                                        R.string.mail_scope_message,
                                        R.array.mail_scopes,
                                        scope,
                                        picked -> bound(email, MailScope.MONTHS[picked]))));

        // NOTE: later frees the bodies below, asked first; "Load more" alone
        // takes in older mail.
        String window = parts.mail().windowOf(email);
        rows.add(
                sections.row(
                        host.getString(R.string.account_window_title),
                        window == null
                                ? host.getString(R.string.mail_window_all)
                                : host.getString(
                                        R.string.account_window_since,
                                        WindowPicker.label(host, window)),
                        chevron(),
                        () ->
                                WindowPicker.open(
                                        host,
                                        parts.mail(),
                                        sync,
                                        mailReads,
                                        parts.mail().query(
                                                (owner, collection) -> owner.equals(email),
                                                false,
                                                false,
                                                ""),
                                        window,
                                        List.of(email),
                                        () -> renderIfOpen(email))));

        Switch rich = new Switch(host);
        rich.setChecked(ComposeFormat.rich(host, email));
        rich.setOnCheckedChangeListener(
                (view, checked) -> ComposeFormat.set(host, email, checked));
        rows.add(
                switchRow(
                        sections,
                        host.getString(R.string.compose_rich_default),
                        host.getString(R.string.compose_rich_default_line),
                        rich));
        return rows;
    }

    /** Sending's rows: the name mail goes out under, and where it goes. */
    private List<View> sendingRows(Sections sections, AccountEntry account) {
        String email = account.email;
        List<View> rows = new ArrayList<>();
        String name = SenderName.of(host, email);
        rows.add(
                sections.row(
                        host.getString(R.string.sender_name),
                        name.isEmpty() ? host.getString(R.string.sender_name_none) : name,
                        chevron(),
                        () -> promptSenderName(email)));

        // NOTE: JMAP, Graph and Gmail submit through the account they read
        // from, so there is no server of its own to change.
        Account mail = account.server(PimDomain.MAIL);
        if (mail != null && !mail.info().submitsOverSession()) {
            String current = mail.submitUrl;
            boolean none = current == null || current.isEmpty();
            rows.add(
                    sections.row(
                            host.getString(R.string.account_send),
                            none ? host.getString(R.string.account_send_none) : hostOf(current),
                            chevron(),
                            () -> promptSubmission(none ? null : current)));
        }
        return rows;
    }

    /**
     * A row counting the collections of a domain the account syncs, opening
     * the chooser of what it takes ({@link #chooseSync}).
     */
    private View subscribedRow(Sections sections, PimDomain domain, int title, String email) {
        MergedFilter filter = MergedFilter.of(host, domain);
        int all = 0;
        int taken = 0;
        for (PimdirCollections.Stored collection : sync.passes().collectionsOf(domain)) {
            if (collection.accountEmail.equals(email)) {
                all++;
                taken += filter.ticked(email, collection.id) ? 1 : 0;
            }
        }
        return sections.row(
                host.getString(title),
                taken == all
                        ? host.getString(R.string.subscribed_all)
                        : host.getString(R.string.subscribed_of, taken, all),
                chevron(),
                () -> chooseSync.accept(domain, email));
    }

    /** Where a number of months stands in the period choices. */
    private static int scopeIndex(int months) {
        for (int index = 0; index < MailScope.MONTHS.length; index++) {
            if (MailScope.MONTHS[index] == months) {
                return index;
            }
        }
        return 0;
    }

    /**
     * Bounds the account's mail.
     *
     * <p>A narrower bound frees what falls below it at once, the messages
     * staying on the server; a wider one is listed by the next sync.
     */
    private void bound(String email, int months) {
        host.io.execute(
                () -> {
                    // NOTE: a narrower bound collects, so not while a
                    // background run writes the same rows.
                    sync.underSyncLock(
                            R.string.mail_scope_failed,
                            () -> {
                                int collected = parts.mail().bound(email, months);
                                Log.d(
                                        "pimalaya",
                                        "mail of " + email + " bounded to " + months
                                                + " months, " + collected + " collected");
                            });
                    host.postAlive(
                            () -> {
                                shell.reload(PimDomain.MAIL);
                                renderIfOpen(email);
                            });
                });
        render();
    }

    /** Re-renders after background work, unless the page moved on. */
    /** Draws the page again when it is open on {@code email}. */
    public void refresh(String email) {
        renderIfOpen(email);
    }

    private void renderIfOpen(String email) {
        if (email.equals(settingsEmail)) {
            render();
        }
    }

    /** The account's addressbooks, as the card store lists them. */
    private List<BookEntry> booksOf(String email) {
        List<BookEntry> entries = new ArrayList<>();
        for (BookEntry entry : parts.cards().loadAllAddressbooks()) {
            if (entry.accountEmail.equals(email)) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Contacts' rows, the setup's: the phone's Contacts app, one switch for
     * the account, then which addressbooks are subscribed.
     *
     * <p>The switch reads as off while the contacts permission is missing, and
     * says so when another app fills the phone's Contacts app already.
     */
    private List<View> contactsRows(Sections sections, String email) {
        List<View> rows = new ArrayList<>();
        List<BookEntry> entries = booksOf(email);
        boolean onPhone = false;
        int subscribed = 0;
        for (BookEntry entry : entries) {
            if (entry.subscribed) {
                subscribed++;
                onPhone |= entry.phoneSynced;
            }
        }
        Switch phone = new Switch(host);
        phone.setChecked(onPhone && PhoneMirror.CONTACTS.granted(host));
        phone.setOnCheckedChangeListener((view, checked) -> setBooksOnPhone(email, checked));
        rows.add(
                switchRow(
                        sections,
                        host.getString(R.string.phone_contacts),
                        host.getString(
                                elsewhere ? R.string.phone_elsewhere : R.string.phone_contacts_note),
                        phone));
        rows.add(
                sections.row(
                        host.getString(R.string.subscribed_addressbooks),
                        subscribed == entries.size()
                                ? host.getString(R.string.subscribed_all)
                                : host.getString(
                                        R.string.subscribed_of, subscribed, entries.size()),
                        chevron(),
                        () -> chooseBooks(email)));
        return rows;
    }

    /**
     * Shows the account's subscribed addressbooks in the phone's Contacts
     * app, or takes them off it; on asks the contacts permission first, a
     * refusal leaving them off.
     */
    private void setBooksOnPhone(String email, boolean on) {
        List<BookEntry> subscribed = new ArrayList<>();
        for (BookEntry entry : booksOf(email)) {
            if (entry.subscribed) {
                subscribed.add(entry);
            }
        }
        if (!on) {
            for (BookEntry entry : subscribed) {
                if (entry.phoneSynced) {
                    parts.cards()
                            .setBookState(entry.book.url(), true, entry.remoteSynced, false);
                    sync.hideFromPhone(entry.book.url());
                }
            }
            shell.reload(PimDomain.CONTACTS);
            shell.filterChanged();
            render();
            return;
        }
        shell.askMirrors(
                java.util.EnumSet.of(PhoneMirror.CONTACTS),
                granted -> {
                    if (granted.contains(PhoneMirror.CONTACTS)) {
                        for (BookEntry entry : subscribed) {
                            parts.cards()
                                    .setBookState(
                                            entry.book.url(), true, entry.remoteSynced, true);
                            sync.showOnPhone(entry.book.url());
                        }
                        shell.reload(PimDomain.CONTACTS);
                        shell.filterChanged();
                    }
                    render();
                });
    }

    /**
     * The subscribed addressbooks, a box per book and, under a ticked one,
     * whether it syncs with the server; each change applies as made.
     *
     * <p>A book ticked joins the phone's Contacts app when the account's others
     * are there.
     */
    private void chooseBooks(String email) {
        LinearLayout list = new LinearLayout(host);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, host.dp(4), 0, host.dp(8));
        ScrollView scroll = new ScrollView(host);
        scroll.addView(list);
        Runnable[] fill = new Runnable[1];
        fill[0] =
                () -> {
                    list.removeAllViews();
                    List<BookEntry> entries = booksOf(email);
                    if (entries.isEmpty()) {
                        TextView none = new TextView(host);
                        none.setText(R.string.books_none);
                        none.setTextColor(host.resolveColor(android.R.attr.textColorSecondary));
                        none.setPadding(host.dp(24), host.dp(8), host.dp(24), host.dp(8));
                        list.addView(none);
                        return;
                    }
                    boolean onPhone = false;
                    for (BookEntry entry : entries) {
                        onPhone |= entry.subscribed && entry.phoneSynced;
                    }
                    boolean joins = onPhone && PhoneMirror.CONTACTS.granted(host) && !elsewhere;
                    for (BookEntry entry : entries) {
                        CheckBox box = new CheckBox(host);
                        box.setText(entry.book.name());
                        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                        box.setTextColor(host.resolveColor(android.R.attr.textColorPrimary));
                        box.setChecked(entry.subscribed);
                        box.setMinHeight(host.dp(52));
                        box.setOnCheckedChangeListener(
                                (view, checked) -> {
                                    setBook(entry, checked, checked, checked && joins);
                                    fill[0].run();
                                });
                        LinearLayout.LayoutParams params =
                                new LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT);
                        params.setMarginStart(host.dp(20));
                        params.setMarginEnd(host.dp(24));
                        list.addView(box, params);
                        if (entry.subscribed) {
                            View remote =
                                    bookOption(
                                            R.string.book_remote_sync,
                                            entry.remoteSynced,
                                            checked -> {
                                                writeBook(
                                                        entry.book.url(),
                                                        true,
                                                        checked,
                                                        entry.phoneSynced);
                                                fill[0].run();
                                            });
                            remote.setPadding(
                                    host.dp(52), 0, remote.getPaddingRight(), host.dp(4));
                            list.addView(remote);
                        }
                    }
                };
        fill[0].run();
        new AlertDialog.Builder(host)
                .setTitle(R.string.subscribed_addressbooks)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** One of a book's two options, a checkbox indented under it. */
    private View bookOption(int label, boolean checked, java.util.function.Consumer<Boolean> set) {
        CheckBox box = new CheckBox(host);
        box.setText(label);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        box.setTextColor(host.resolveColor(android.R.attr.textColorPrimary));
        box.setChecked(checked);
        box.setMinHeight(host.dp(44));
        box.setOnCheckedChangeListener((view, value) -> set.accept(value));

        LinearLayout row = new LinearLayout(host);
        row.setPadding(host.dp(28), 0, host.dp(16), 0);
        row.addView(
                box,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /**
     * Writes a book's switches; the contacts root and the phone follow them.
     *
     * <p>On for the phone, the contacts permission is asked first and a refusal
     * leaves the book off it. Off, the phone's copy goes after one last pass.
     */
    private void setBook(BookEntry entry, boolean enabled, boolean remote, boolean local) {
        String url = entry.book.url();
        boolean wasShown = entry.subscribed && entry.phoneSynced;
        if (!local || wasShown && PhoneMirror.CONTACTS.granted(host)) {
            writeBook(url, enabled, remote, local);
            if (wasShown && !local) {
                sync.hideFromPhone(url);
            }
            return;
        }
        shell.askMirrors(
                java.util.EnumSet.of(PhoneMirror.CONTACTS),
                granted -> {
                    boolean phone = granted.contains(PhoneMirror.CONTACTS);
                    writeBook(url, enabled, remote, phone);
                    if (phone) {
                        sync.showOnPhone(url);
                    }
                });
    }

    /** Writes a book's switches as they are; the contacts root follows them. */
    private void writeBook(String url, boolean enabled, boolean remote, boolean local) {
        parts.cards().setBookState(url, enabled, remote, local);
        shell.reload(PimDomain.CONTACTS);
        shell.filterChanged();
        render();
    }

    /**
     * Calendar's rows, the setup's: the phone's calendar, one switch for the
     * account, then which calendars are subscribed, then where tasks go.
     *
     * <p>The switch reads as off while the calendar permission is missing, and
     * says so when another app fills the phone's calendar already.
     */
    private List<View> calendarRows(Sections sections, String email) {
        List<View> rows = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (PimdirCollections.Stored calendar : sync.passes().collectionsOf(PimDomain.CALENDAR)) {
            if (calendar.accountEmail.equals(email)) {
                ids.add(calendar.id);
            }
        }
        Switch phone = new Switch(host);
        phone.setChecked(
                PhoneCalendars.shown(host, email) && PhoneMirror.CALENDAR.granted(host));
        phone.setOnCheckedChangeListener(
                (view, checked) -> setCalendarsOnPhone(email, checked, ids));
        rows.add(
                switchRow(
                        sections,
                        host.getString(R.string.phone_calendar),
                        host.getString(
                                calendarsElsewhere
                                        ? R.string.phone_calendar_elsewhere
                                        : R.string.phone_calendar_note),
                        phone));
        rows.add(subscribedRow(sections, PimDomain.CALENDAR, R.string.subscribed_calendars, email));
        View tasks = tasksRow(sections, email);
        if (tasks != null) {
            rows.add(tasks);
        }
        return rows;
    }

    /**
     * Where the account's tasks go: the calendars that take them, or why none
     * does (cairn/changes/gtd: the point of use, besides the capture sheet and
     * onboarding). Null for an account without calendars yet.
     *
     * <p>Where a capture goes is picked in the capture sheet, which keeps the
     * last one: there is no default to set here
     * (cairn/changes/gtd-by-categories).
     */
    private View tasksRow(Sections sections, String email) {
        List<EventStore.StoredCalendar> calendars = new ArrayList<>();
        List<String> taking = new ArrayList<>();
        for (EventStore.StoredCalendar calendar : parts.events().loadCalendars()) {
            if (calendar.accountEmail.equals(email)) {
                calendars.add(calendar);
                if (TaskStore.takesTasks(calendar)) {
                    taking.add(calendar.name);
                }
            }
        }
        if (calendars.isEmpty()) {
            return null;
        }
        return sections.row(
                host.getString(R.string.tasks_title),
                taking.isEmpty()
                        ? host.getString(noTasksReason(calendars.get(0).url))
                        : host.getString(R.string.account_tasks_in, String.join(", ", taking)),
                null,
                null);
    }

    /** Why an account's calendars take no tasks, by the service they are on. */
    private static int noTasksReason(String url) {
        String base = url == null ? "" : url;
        if (base.startsWith("msgraph:")) {
            return R.string.account_tasks_none_microsoft;
        }
        if (base.startsWith("google:")) {
            return R.string.account_tasks_none_google;
        }
        return R.string.account_tasks_none;
    }

    /**
     * Shows every calendar of the account on the phone, or takes them all off
     * it; on asks the calendar permission first, a refusal leaving them off.
     * The phone's calendars follow either way.
     */
    private void setCalendarsOnPhone(String email, boolean on, List<String> calendars) {
        if (!on) {
            PhoneCalendars.setAccount(host, email, false);
            sync.reconcileCalendars();
            render();
            return;
        }
        shell.askMirrors(
                java.util.EnumSet.of(PhoneMirror.CALENDAR),
                granted -> {
                    if (granted.contains(PhoneMirror.CALENDAR)) {
                        PhoneCalendars.showAll(host, email, calendars);
                        sync.reconcileCalendars();
                    }
                    render();
                });
    }

    /**
     * The servers card, read only: one row per host.
     *
     * <p>Each names the domains it serves, the protocols and how it signs in.
     */
    private void addServers(Sections sections, AccountEntry account) {
        Map<String, Server> servers = new LinkedHashMap<>();
        for (PimDomain domain : PimDomain.values()) {
            Account server = account.server(domain);
            AccountCredential credential = account.credential(domain);
            if (server == null || credential == null) {
                continue;
            }
            servers.computeIfAbsent(server.info().host(), key -> new Server())
                    .add(domain, protocolOf(domain, server), methodOf(credential));
            if (domain == PimDomain.MAIL
                    && server.submitUrl != null
                    && !server.submitUrl.isEmpty()) {
                servers.computeIfAbsent(hostOf(server.submitUrl), key -> new Server())
                        .add(
                                domain,
                                host.getString(R.string.config_smtp),
                                methodOf(credential));
            }
        }

        List<View> rows = new ArrayList<>();
        for (Map.Entry<String, Server> server : servers.entrySet()) {
            Server named = server.getValue();
            List<String> domains = new ArrayList<>();
            for (PimDomain domain : named.domains) {
                domains.add(host.getString(domain.label));
            }
            String line =
                    String.join(", ", domains)
                            + " · " + String.join(", ", named.protocols)
                            + " · " + String.join(", ", named.methods);
            rows.add(sections.row(server.getKey(), line, null, null));
        }
        sections.section(0, 0, rows, null, false);
    }

    /** One server row's pieces, in the order the domains came. */
    private static final class Server {
        final Set<PimDomain> domains = new LinkedHashSet<>();
        final Set<String> protocols = new LinkedHashSet<>();
        final Set<String> methods = new LinkedHashSet<>();

        void add(PimDomain domain, String protocol, String method) {
            domains.add(domain);
            protocols.add(protocol);
            methods.add(method);
        }
    }

    /** The protocol one domain speaks to its server. */
    private String protocolOf(PimDomain domain, Account server) {
        Protocol protocol = server.info().protocol();
        if (protocol == Protocol.GRAPH) {
            return host.getString(R.string.config_msgraph);
        }
        if (protocol == Protocol.JMAP) {
            return host.getString(R.string.config_jmap);
        }
        boolean google = protocol == Protocol.GOOGLE;
        switch (domain) {
            case MAIL:
                return host.getString(google ? R.string.config_gmail : R.string.config_imap);
            case CALENDAR:
                return host.getString(google ? R.string.config_gcal : R.string.config_caldav);
            default:
                return host.getString(
                        google ? R.string.config_google_api : R.string.config_carddav);
        }
    }

    /** How a credential signs in: a renewable grant, a token or a password. */
    private String methodOf(AccountCredential credential) {
        if (credential.renewable()) {
            return host.getString(R.string.server_oauth);
        }
        return host.getString(
                credential.login == null || credential.login.isEmpty()
                        ? R.string.server_token
                        : R.string.config_password);
    }

    /** A row ending in a switch, the row as a whole toggling it. */
    private View switchRow(Sections sections, String title, String line, Switch toggle) {
        LinearLayout row = (LinearLayout) sections.row(title, line, null, toggle::toggle);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(host.dp(12));
        row.addView(toggle, params);
        return row;
    }

    /** The trailing chevron of a row opening a choice. */
    private View chevron() {
        android.widget.ImageView glyph = new android.widget.ImageView(host);
        glyph.setImageResource(R.drawable.ic_chevron_right);
        glyph.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        host.resolveColor(android.R.attr.textColorSecondary)));
        return glyph;
    }

    /**
     * A single-choice dialog of radio rows under a title and a context line.
     *
     * <p>A tap applies and closes; Cancel leaves it as it was.
     */
    private void choose(int title, int message, int choices, int current, IntConsumer picked) {
        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, host.dp(4), 0, 0);

        TextView context = new TextView(host);
        context.setText(message);
        context.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        context.setTextColor(host.resolveColor(android.R.attr.textColorSecondary));
        context.setPadding(host.dp(24), 0, host.dp(24), host.dp(12));
        content.addView(context);

        AlertDialog dialog =
                new AlertDialog.Builder(host)
                        .setTitle(title)
                        .setView(content)
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();

        String[] labels = host.getResources().getStringArray(choices);
        for (int index = 0; index < labels.length; index++) {
            int choice = index;
            RadioButton radio = new RadioButton(host);
            radio.setText(labels[index]);
            radio.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            radio.setTextColor(host.resolveColor(android.R.attr.textColorPrimary));
            radio.setChecked(index == current);
            radio.setMinHeight(host.dp(52));
            radio.setGravity(Gravity.CENTER_VERTICAL);
            radio.setPadding(host.dp(12), 0, 0, 0);
            radio.setOnClickListener(
                    view -> {
                        dialog.dismiss();
                        if (choice != current) {
                            picked.accept(choice);
                        }
                    });
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(host.dp(20));
            params.setMarginEnd(host.dp(24));
            content.addView(radio, params);
        }
        dialog.show();
    }

    /** Asks for the name the account's mail goes out under. */
    private void promptSenderName(String email) {
        PillField field =
                PillField.of(host, R.string.sender_name, SenderName.of(host, email), false, true);
        field.input.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                        | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.input.setTypeface(android.graphics.Typeface.DEFAULT);
        prompt(
                R.string.sender_name,
                R.string.sender_name_message,
                field,
                () -> {
                    SenderName.set(host, email, field.text());
                    render();
                });
    }

    /**
     * Asks for the server this account sends through, emptied for none.
     *
     * <p>A host and a port, 465 when none is typed, implicit TLS either way:
     * the advanced setup's endpoint, since this client has no STARTTLS step.
     */
    private void promptSubmission(String current) {
        String email = settingsEmail;
        PillField field =
                PillField.of(
                        host,
                        R.string.manual_submit_server,
                        current == null ? "" : hostOf(current),
                        false,
                        true);
        prompt(
                R.string.send_mail,
                R.string.manual_submit_message,
                field,
                () -> {
                    String entered = field.text();
                    String url = entered.isEmpty() ? null : Endpoints.submitUrl(entered);
                    if (!entered.isEmpty() && url == null) {
                        host.toast(host.getString(R.string.server_insecure));
                        return;
                    }
                    parts.accounts().submitThrough(email, url);
                    render();
                });
    }

    /** A one-field dialog: title, message, the field, Cancel and Save. */
    private void prompt(int title, int message, PillField field, Runnable save) {
        LinearLayout fields = new LinearLayout(host);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(host.dp(24), 0, host.dp(24), 0);
        fields.addView(field.view);

        new AlertDialog.Builder(host)
                .setTitle(title)
                .setMessage(message)
                .setView(fields)
                .setPositiveButton(R.string.password_submit, (dialog, which) -> save.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The host part of an endpoint, the endpoint itself when it has none. */
    private static String hostOf(String url) {
        try {
            String named = java.net.URI.create(url).getHost();
            return named == null ? url : named;
        } catch (Exception error) {
            return url;
        }
    }

    /** Leaves the settings screen, landing on the still-open drawer. */
    public void leave() {
        settingsEmail = null;
        host.closeOverlay(Panels.ACCOUNT);
        host.screen = cameFrom;
        host.applyChrome(cameFrom);
        // NOTE: the drawer never closed under the overlay, so its rows
        // refresh in place.
        host.reloadHome();
    }

    /**
     * Confirms, then removes the account and everything under it.
     *
     * <p>Leaves its settings screen for the drawer underneath.
     */
    private void confirmDeleteAccount(String email) {
        // NOTE: a running pass would write the account back as it goes.
        if (sync.isSyncing()) {
            host.toast(host.getString(R.string.sync_busy));
            return;
        }
        new AlertDialog.Builder(host)
                .setTitle(R.string.delete_account_confirm)
                .setMessage(
                        ((TextView) host.findViewById(R.id.account_delete_note)).getText())
                .setPositiveButton(
                        R.string.delete_account,
                        (dialog, which) -> {
                            // NOTE: a Contacts-app edit not yet in would go
                            // with the books' accounts, so their phone pass
                            // runs first and a failure removes nothing.
                            List<String> urls = new ArrayList<>();
                            for (BookEntry entry : sync.passes().phoneSyncedBooks()) {
                                if (entry.accountEmail.equals(email)) {
                                    urls.add(entry.book.url());
                                }
                            }
                            sync.lastPhonePass(
                                    urls,
                                    ran -> {
                                        if (!ran) {
                                            host.toast(host.getString(R.string.delete_account_phone_failed));
                                            return;
                                        }
                                        if (sync.isSyncing()) {
                                            host.toast(host.getString(R.string.sync_busy));
                                            return;
                                        }
                                        deleteAccount(email);
                                        if (host.screen == Panels.ACCOUNT) {
                                            leave();
                                        }
                                    });
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteAccount(String email) {
        List<String> urls = new AccountRemoval(host, parts, contacts).remove(email);
        accounts.removeIf(entry -> entry.email.equals(email));
        host.reloadHome();
        shell.reload(PimDomain.MAIL);
        shell.reload(PimDomain.CALENDAR);

        // NOTE: the deleted books' phone accounts go explicitly (their
        // rows are already gone, so a reconcile could not name them);
        // reconciling the remaining phone-synced set sweeps any straggler.
        List<BookEntry> phoneBooks = sync.passes().phoneSyncedBooks();
        host.io.execute(() -> {
            try {
                for (String url : urls) {
                    Accounts.remove(host, url);
                }
                Accounts.reconcile(host, BookEntry.accounts(phoneBooks));
            } catch (Exception error) {
                Log.w("pimalaya", "account purge failed for " + AccountEntry.redacted(email) + ": " + error);
            }
        });
        // NOTE: its calendar account goes the same way, the store having
        // forgotten its calendars.
        sync.reconcileCalendars();
    }
}
