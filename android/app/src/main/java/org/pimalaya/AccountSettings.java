package org.pimalaya;

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
import java.util.function.IntConsumer;
import org.pimalaya.client.Account;
import org.pimalaya.client.PimalayaClient;

/**
 * The full-screen account settings controller behind the drawer: one
 * account's page, opened over the drawer, as {@link Sections} cards under
 * the account's identity. Every change applies when it is made, so the
 * page always says what the account does. It reaches the base, store and
 * io executor through the host, which keeps the overlay navigation and
 * calls in through {@link #open} and {@link #leave}.
 */
final class AccountSettings {
    private final MainActivity host;

    /** The account whose settings screen is open (null outside it). */
    private String settingsEmail;

    /**
     * The list screen the drawer was raised over, restored on the way
     * out. The overlay covers the drawer, which covers a list, and that
     * list is still there underneath: landing the chrome on a different
     * one would leave the bar describing a screen nobody navigated to.
     */
    private int cameFrom = MainActivity.PANEL_MAIL;

    /**
     * Whether another app already fills the phone's Contacts app with the
     * open account's address ({@link Accounts#elsewhere}), looked for as the
     * page opens.
     */
    private boolean elsewhere;

    /** The same for the phone's calendars ({@link Accounts#calendarsElsewhere}). */
    private boolean calendarsElsewhere;

    AccountSettings(MainActivity host) {
        this.host = host;
    }

    /** Wires the page's bar, scroll and remove button, once laid out. */
    void bind() {
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
    void open(String email) {
        settingsEmail = email;
        cameFrom = host.screen;

        ((TextView) host.findViewById(R.id.account_title)).setText(email);
        host.findViewById(R.id.account_title).setAlpha(0f);
        host.findViewById(R.id.account_scroll).scrollTo(0, 0);
        elsewhere = false;
        calendarsElsewhere = false;
        render();
        host.openOverlay(MainActivity.PANEL_ACCOUNT);

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

    /** Rebuilds the page from what the account holds now. */
    private void render() {
        String email = settingsEmail;
        AccountEntry account = host.accountFor(email);
        if (account == null) {
            return;
        }
        renderIdentity(account);

        Sections sections = new Sections(host, host.findViewById(R.id.account_content));
        sections.clear();
        addActivation(sections, email);
        if (account.covers(PimDomain.MAIL)) {
            addMail(sections, account);
        }
        addBackground(sections, account);
        addBooks(sections, email);
        if (account.covers(PimDomain.CALENDAR)) {
            addCalendars(sections, email);
        }
        addServers(sections, account);

        boolean contactsOnly =
                !account.covers(PimDomain.MAIL) && !account.covers(PimDomain.CALENDAR);
        ((TextView) host.findViewById(R.id.account_delete_note))
                .setText(
                        contactsOnly
                                ? R.string.delete_account_contacts
                                : R.string.delete_account_all);
    }

    /** The disc, the address, what it covers and where it stands. */
    private void renderIdentity(AccountEntry account) {
        String email = account.email;
        TextView avatar = host.findViewById(R.id.account_avatar);
        avatar.setText(Avatar.letter(email));
        avatar.setBackground(Avatar.circle(host, email));
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
                host.syncPill(
                        AccountActivation.enabled(host, email),
                        SyncStamps.at(host, email),
                        host.bodiesLeft(email)),
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /**
     * The account switch: off, the account syncs nothing and the lists and
     * their filters leave it out ({@link AccountActivation}).
     */
    private void addActivation(Sections sections, String email) {
        Switch toggle = new Switch(host);
        toggle.setChecked(AccountActivation.enabled(host, email));
        toggle.setOnCheckedChangeListener(
                (view, checked) -> {
                    AccountActivation.set(host, email, checked);
                    host.reloadContacts();
                    host.filterChanged();
                    render();
                });
        sections.section(
                0,
                0,
                List.of(
                        switchRow(
                                sections,
                                host.getString(R.string.account_enable),
                                host.getString(R.string.account_enable_note),
                                toggle)),
                null,
                false);
    }

    /**
     * The mail card: the name mail goes out under, how far back it syncs,
     * which bodies download ahead and on which networks, and where it
     * sends through.
     */
    private void addMail(Sections sections, AccountEntry account) {
        String email = account.email;
        String accountId = host.mail.accountIdOf(email);
        List<View> rows = new ArrayList<>();

        String name = SenderName.of(host, email);
        rows.add(
                sections.row(
                        host.getString(R.string.sender_name),
                        name.isEmpty() ? host.getString(R.string.sender_name_none) : name,
                        chevron(),
                        () -> promptSenderName(email)));

        // NOTE: a whole mailbox is all of it, which the download setting
        // says, so the period holds at all mail and dims.
        MailOffline.Policy policy = MailOffline.policy(host, accountId);
        boolean whole = policy == MailOffline.Policy.WHOLE;
        String[] scopes = host.getResources().getStringArray(R.array.mail_scopes);
        int scope = whole ? 0 : scopeIndex(host.mail.monthsOf(email));
        View period =
                sections.row(
                        host.getString(R.string.mail_scope_title),
                        scopes[scope],
                        chevron(),
                        whole
                                ? null
                                : () ->
                                        choose(
                                                R.string.mail_scope_title,
                                                R.string.mail_scope_message,
                                                R.array.mail_scopes,
                                                scope,
                                                picked -> bound(email, MailScope.MONTHS[picked])));
        period.setAlpha(whole ? 0.5f : 1f);
        rows.add(period);

        String[] policies = host.getResources().getStringArray(R.array.mail_offline_policies);
        rows.add(
                sections.row(
                        host.getString(R.string.mail_offline_title),
                        policies[policy.ordinal()],
                        chevron(),
                        () ->
                                choose(
                                        R.string.mail_offline_title,
                                        R.string.mail_offline_message,
                                        R.array.mail_offline_policies,
                                        policy.ordinal(),
                                        picked ->
                                                setPolicy(
                                                        email,
                                                        accountId,
                                                        MailOffline.Policy.values()[picked]))));

        Switch metered = new Switch(host);
        metered.setChecked(MailOffline.metered(host, accountId));
        metered.setOnCheckedChangeListener(
                (view, checked) -> {
                    MailOffline.setMetered(host, accountId, checked);
                    host.fillMail();
                });
        rows.add(
                switchRow(
                        sections,
                        host.getString(R.string.mail_offline_metered),
                        host.getString(R.string.mail_offline_metered_note),
                        metered));

        // NOTE: JMAP, Graph and Gmail submit through the account they read
        // from, so there is no server of its own to change.
        Account mail = account.server(PimDomain.MAIL);
        if (mail != null && !PimalayaClient.submitsOverSession(mail)) {
            String current = mail.submitUrl;
            boolean none = current == null || current.isEmpty();
            rows.add(
                    sections.row(
                            host.getString(R.string.account_send),
                            none ? host.getString(R.string.account_send_none) : hostOf(current),
                            chevron(),
                            () -> promptSubmission(none ? null : current)));
        }

        sections.section(PimDomain.MAIL.label, R.drawable.ic_section_mail, rows, null, false);
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
     * Bounds the account's mail: a narrower bound frees what falls below it
     * at once, the messages staying on the server; a wider one is listed by
     * the next sync.
     */
    private void bound(String email, int months) {
        host.io.execute(
                () -> {
                    try {
                        int collected = host.mail.bound(email, months);
                        Log.d(
                                "pimalaya",
                                "mail of " + email + " bounded to " + months + " months, "
                                        + collected + " collected");
                    } catch (Exception error) {
                        Log.w("pimalaya", "mail bound failed for " + email, error);
                    }
                    host.postAlive(
                            () -> {
                                host.mailList.reload();
                                renderIfOpen(email);
                            });
                });
        render();
    }

    /**
     * Sets which bodies download ahead ({@link MailOffline}). Whole mailbox
     * sets the bound to all mail, and the body step starts or replans.
     */
    private void setPolicy(String email, String accountId, MailOffline.Policy picked) {
        MailOffline.setPolicy(host, accountId, picked);
        if (picked != MailOffline.Policy.WHOLE) {
            render();
            host.fillMail();
            return;
        }
        host.io.execute(
                () -> {
                    try {
                        host.mail.bound(email, 0);
                    } catch (Exception error) {
                        Log.w("pimalaya", "mail bound failed for " + email, error);
                    }
                    host.postAlive(
                            () -> {
                                renderIfOpen(email);
                                host.mailList.reload();
                                host.fillMail();
                            });
                });
        render();
    }

    /** Re-renders after background work, unless the page moved on. */
    private void renderIfOpen(String email) {
        if (email.equals(settingsEmail)) {
            render();
        }
    }

    /**
     * The background card ({@link BackgroundCheck}): how often the account
     * syncs while the app is closed, and whether its new mail notifies,
     * which needs the first.
     */
    private void addBackground(Sections sections, AccountEntry account) {
        String email = account.email;
        boolean syncs = BackgroundCheck.syncs(host, email);
        List<View> rows = new ArrayList<>();

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

        if (account.covers(PimDomain.MAIL)) {
            Switch notify = new Switch(host);
            notify.setChecked(BackgroundCheck.notifies(host, email));
            notify.setEnabled(syncs);
            notify.setOnCheckedChangeListener(
                    (view, checked) -> {
                        BackgroundCheck.setNotifies(host, email, checked);
                        if (checked) {
                            host.requestNotifications();
                        }
                    });
            View row =
                    switchRow(sections, host.getString(R.string.background_notify), null, notify);
            row.setEnabled(syncs);
            row.setAlpha(syncs ? 1f : 0.5f);
            rows.add(row);
        }

        sections.section(R.string.account_background, R.drawable.ic_sync, rows, null, false);
    }

    /**
     * The addressbooks card: a switch per book, and under a book that is on
     * whether it syncs with the server and shows in the phone's contacts,
     * led by a line when another app already fills the phone's Contacts app
     * with the address. Turning a book on shows it on the phone too, unless
     * another app does already.
     */
    private void addBooks(Sections sections, String email) {
        List<View> rows = new ArrayList<>();
        if (elsewhere) {
            rows.add(note(R.string.phone_elsewhere));
        }
        for (BookEntry entry : host.base.loadAllAddressbooks()) {
            if (!entry.accountEmail.equals(email)) {
                continue;
            }
            String url = entry.book.url;

            LinearLayout item = new LinearLayout(host);
            item.setOrientation(LinearLayout.VERTICAL);

            Switch enable = new Switch(host);
            enable.setChecked(entry.subscribed);
            enable.setOnCheckedChangeListener(
                    (view, checked) -> setBook(entry, checked, checked, checked && !elsewhere));
            item.addView(
                    switchRow(
                            sections,
                            entry.book.name,
                            entry.subscribed ? null : host.getString(R.string.book_off),
                            enable));

            if (entry.subscribed) {
                item.addView(
                        bookOption(
                                R.string.book_remote_sync,
                                entry.remoteSynced,
                                checked -> setBook(entry, true, checked, entry.phoneSynced)));
                View local =
                        bookOption(
                                R.string.book_local_sync,
                                entry.phoneSynced,
                                checked -> setBook(entry, true, entry.remoteSynced, checked));
                local.setPadding(
                        local.getPaddingLeft(), 0, local.getPaddingRight(), host.dp(6));
                item.addView(local);
            }
            rows.add(item);
        }
        sections.section(R.string.account_books, R.drawable.ic_addressbooks, rows, null, false);
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
     * Turned on for the phone, the contacts permission is asked first, and
     * refused leaves the book off it; the projection follows on the strip.
     * Turned off, the phone's copy goes after one last pass.
     */
    private void setBook(BookEntry entry, boolean enabled, boolean remote, boolean local) {
        String url = entry.book.url;
        boolean shown = entry.subscribed && entry.phoneSynced;
        if (!local || shown) {
            writeBook(url, enabled, remote, local);
            if (shown && !local) {
                host.hideFromPhone(url);
            }
            return;
        }
        host.askMirrors(
                java.util.EnumSet.of(PhoneMirror.CONTACTS),
                granted -> {
                    boolean phone = granted.contains(PhoneMirror.CONTACTS);
                    writeBook(url, enabled, remote, phone);
                    if (phone) {
                        host.showOnPhone(url);
                    }
                });
    }

    /** Writes a book's switches as they are; the contacts root follows them. */
    private void writeBook(String url, boolean enabled, boolean remote, boolean local) {
        host.base.setBookState(url, enabled, remote, local);
        host.reloadContacts();
        host.filterChanged();
        render();
    }

    /**
     * The calendars card: a row per calendar, its filter state under its
     * name and whether it shows in the phone's calendar under that, led by a
     * line when another app already fills the phone's calendar with the
     * address. The switch still shows it there then.
     */
    private void addCalendars(Sections sections, String email) {
        List<View> rows = new ArrayList<>();
        if (calendarsElsewhere) {
            rows.add(note(R.string.phone_calendar_elsewhere));
        }
        List<String> ids = new ArrayList<>();
        List<PimdirCollections.Stored> calendars = new ArrayList<>();
        for (PimdirCollections.Stored calendar : host.collectionsOf(PimDomain.CALENDAR)) {
            if (calendar.accountEmail.equals(email)) {
                ids.add(calendar.id);
                calendars.add(calendar);
            }
        }
        MergedFilter filter = host.filterOf(PimDomain.CALENDAR);
        for (PimdirCollections.Stored calendar : calendars) {
            LinearLayout item = new LinearLayout(host);
            item.setOrientation(LinearLayout.VERTICAL);
            item.addView(
                    sections.row(
                            calendar.name,
                            host.getString(
                                    filter.ticked(email, calendar.id)
                                            ? R.string.calendar_filter_shown
                                            : R.string.calendar_filter_hidden),
                            null,
                            null));
            View phone =
                    bookOption(
                            R.string.calendar_phone,
                            PhoneCalendars.shown(host, email, calendar.id),
                            checked -> setCalendar(email, calendar.id, checked, ids));
            phone.setPadding(phone.getPaddingLeft(), 0, phone.getPaddingRight(), host.dp(6));
            item.addView(phone);
            rows.add(item);
        }
        sections.section(R.string.calendar_title, R.drawable.ic_domain_calendar, rows, null, false);
    }

    /**
     * A calendar's phone switch. Turned on, the calendar permission is asked
     * first, and refused leaves the calendar off the phone; turned off, its
     * row goes after one last pass. The phone's calendars follow either way.
     */
    private void setCalendar(String email, String calendar, boolean on, List<String> siblings) {
        if (!on) {
            PhoneCalendars.set(host, email, calendar, false, siblings);
            host.reconcileCalendars();
            return;
        }
        host.askMirrors(
                java.util.EnumSet.of(PhoneMirror.CALENDAR),
                granted -> {
                    if (granted.contains(PhoneMirror.CALENDAR)) {
                        PhoneCalendars.set(host, email, calendar, true, siblings);
                        host.reconcileCalendars();
                    }
                    render();
                });
    }

    /** A line leading a card, saying another app already shows the address. */
    private TextView note(int text) {
        TextView note = new TextView(host);
        note.setText(text);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setTextColor(host.resolveColor(android.R.attr.textColorSecondary));
        note.setPadding(host.dp(16), host.dp(12), host.dp(16), host.dp(12));
        return note;
    }

    /**
     * The servers card, read only: one row per host, naming the domains it
     * serves, over which protocols and how it signs in.
     */
    private void addServers(Sections sections, AccountEntry account) {
        Map<String, Server> servers = new LinkedHashMap<>();
        for (PimDomain domain : PimDomain.values()) {
            Account server = account.server(domain);
            AccountCredential credential = account.credential(domain);
            if (server == null || credential == null) {
                continue;
            }
            servers.computeIfAbsent(serverHost(server.baseUrl), key -> new Server())
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
        sections.section(R.string.account_servers, R.drawable.ic_section_work, rows, null, false);
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

    /** The host a base URL reaches, the providers' APIs by their name. */
    private static String serverHost(String url) {
        if (url.startsWith("msgraph://")) {
            return "graph.microsoft.com";
        }
        if (url.startsWith("google://")) {
            return "googleapis.com";
        }
        if (url.startsWith("jmap://")) {
            return hostOf("https://" + url.substring("jmap://".length()));
        }
        return hostOf(url);
    }

    /** The protocol one domain speaks to its server. */
    private String protocolOf(PimDomain domain, Account server) {
        if (PimalayaClient.isGraph(server)) {
            return host.getString(R.string.config_msgraph);
        }
        if (PimalayaClient.isJmap(server)) {
            return host.getString(R.string.config_jmap);
        }
        boolean google = PimalayaClient.isGoogle(server);
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
     * A single-choice dialog: its title, a line of context and the choices
     * as radio rows. A tap applies and closes; Cancel leaves it as it was.
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
     * Asks for the server this account sends through, emptied to send
     * through none.
     *
     * <p>A host and a port, port 465 when none is typed, implicit TLS
     * either way: the same endpoint the advanced setup takes, for the
     * same reason, which is that this client has no STARTTLS step.
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
                    host.store.submitThrough(
                            email, entered.isEmpty() ? null : PimalayaClient.submitUrl(entered));
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
    void leave() {
        settingsEmail = null;
        host.closeOverlay(MainActivity.PANEL_ACCOUNT);
        host.screen = cameFrom;
        host.applyChrome(cameFrom);
        // NOTE: the drawer never closed under the overlay, so its rows
        // refresh in place.
        host.reloadHome();
    }

    /**
     * Confirms, then removes the account and everything under it,
     * leaving its settings screen for the drawer underneath.
     */
    private void confirmDeleteAccount(String email) {
        // NOTE: a running pass would write the account back as it goes.
        if (host.isSyncing()) {
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
                            deleteAccount(email);
                            if (host.screen == MainActivity.PANEL_ACCOUNT) {
                                leave();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteAccount(String email) {
        // NOTE: remember the books' URLs to remove the phone accounts
        // that mirrored them.
        List<String> urls = new ArrayList<>();
        for (BookEntry entry : host.base.loadAllAddressbooks()) {
            if (entry.accountEmail.equals(email)) {
                urls.add(entry.book.url);
            }
        }
        List<String> calendars = new ArrayList<>();
        for (PimdirCollections.Stored calendar : host.collectionsOf(PimDomain.CALENDAR)) {
            if (calendar.accountEmail.equals(email)) {
                calendars.add(calendar.id);
            }
        }
        // NOTE: before its collections go, which is what names them.
        host.forgetViews(email);
        PhoneCalendars.forget(host, email, calendars);

        // NOTE: the account and every domain it covered. The screen shows the
        // contacts side, but the user is deleting the account they see, and
        // leaving a mail or calendar connection behind under the same address
        // would be an invisible leftover.
        host.store.remove(email);
        // NOTE: the contacts outlive the account: they move into the on-device
        // book, cleared of every sync marker, and the account's collections go
        // with their bindings. The switches and the link exceptions are the
        // app's own state and are dropped separately.
        host.contacts.detachToLocal(urls, LocalBook.URL);
        host.base.forgetAccount(email);
        // NOTE: mail and events do not outlive it: they are the server's,
        // and left behind they would stay listed under an account the
        // filter no longer offers to hide.
        host.mail.forget(email);
        FirstSync.forget(host, email);
        SenderName.set(host, email, "");
        BackgroundCheck.forget(host, email);
        host.events.forget(email);
        host.accounts.removeIf(entry -> entry.email.equals(email));
        host.reloadHome();
        host.mailList.reload();
        host.calendarList.reload();

        // NOTE: the deleted books' phone accounts go explicitly (their
        // rows are already gone, so a reconcile could not name them);
        // reconciling the remaining phone-synced set sweeps any straggler.
        List<BookEntry> phoneBooks = host.phoneSyncedBooks();
        host.io.execute(() -> {
            try {
                for (String url : urls) {
                    Accounts.remove(host, url);
                }
                Accounts.reconcile(host, phoneBooks);
            } catch (Exception error) {
                Log.w("pimalaya", "account purge failed for " + email + ": " + error);
            }
        });
        // NOTE: its calendar account goes the same way, the store having
        // forgotten its calendars.
        host.reconcileCalendars();
    }
}
