package org.pimalaya;

import android.app.AlertDialog;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.pimalaya.client.Account;
import org.pimalaya.client.PimalayaClient;

/**
 * The full-screen account settings controller behind the drawer: it
 * opens one account's settings screen over the drawer, stages the
 * account's activation and the per-addressbook switches (an advanced
 * fold exposing the per-book sections), commits them to the base on save, and
 * confirms then removes the account and everything under it. It reaches
 * the base, store and io executor through the host, which keeps the
 * overlay navigation and calls in through {@link #open}, {@link #save},
 * {@link #leave} and {@link #confirmDeleteCurrent}.
 */
final class AccountSettings {
    private final MainActivity host;

    /** The account whose settings screen is open (null outside it). */
    private String settingsEmail;

    /** The open settings screen's addressbooks, in store order. */
    private List<BookEntry> settingsBooks = new ArrayList<>();

    /** Staged switches per addressbook URL; the FAB commits them. */
    private final Map<String, BookSettings> bookSettings = new java.util.LinkedHashMap<>();

    /** Whether the settings screen's advanced sections are unfolded. */
    private boolean settingsAdvancedOpen;

    /** The staged account switch ({@link AccountActivation}); the FAB commits it. */
    private boolean accountEnabled;

    /**
     * The list screen the drawer was raised over, restored on the way
     * out. The overlay covers the drawer, which covers a list, and that
     * list is still there underneath: landing the chrome on a different
     * one would leave the bar describing a screen nobody navigated to.
     */
    private int cameFrom = MainActivity.PANEL_MAIL;

    AccountSettings(MainActivity host) {
        this.host = host;
    }

    /** One addressbook's staged switches on the account settings screen. */
    private static final class BookSettings {
        boolean enabled;
        boolean remote;
        boolean local;
    }

    /**
     * Opens one account's settings screen: the staged switches load
     * from the store, the screen fades in over the drawer it came
     * from, which stays open underneath for the return. The activate
     * switch is the account's; Advanced unfolds the per-book sections.
     */
    void open(String email) {
        settingsEmail = email;
        cameFrom = host.screen;
        settingsBooks = new ArrayList<>();
        bookSettings.clear();
        for (BookEntry entry : host.base.loadAllAddressbooks()) {
            if (entry.accountEmail.equals(email)) {
                settingsBooks.add(entry);
                BookSettings staged = new BookSettings();
                staged.enabled = entry.subscribed;
                staged.remote = entry.remoteSynced;
                staged.local = entry.phoneSynced;
                bookSettings.put(entry.book.url, staged);
            }
        }
        settingsAdvancedOpen = false;
        accountEnabled = AccountActivation.enabled(host, email);

        ((TextView) host.findViewById(R.id.account_title)).setText(email);
        renderAccountSettings();
        host.openOverlay(MainActivity.PANEL_ACCOUNT);
    }

    /**
     * Rebuilds the settings screen from the staged switches; every bulk
     * change re-renders, so the simple pair, the advanced sections and
     * the dimming always agree.
     */
    private void renderAccountSettings() {
        LinearLayout content = host.findViewById(R.id.account_content);
        content.removeAllViews();

        LinearLayout.LayoutParams rowParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);

        addSubmission(content, rowParams);
        addMailScope(content);
        addMailOffline(content, rowParams);

        // The account switch: off, the account syncs nothing and the lists
        // and their filters leave it out. The books keep their own switches.
        CheckBox activate = new CheckBox(host);
        activate.setChecked(accountEnabled);
        content.addView(optionRow(R.string.account_enable, activate), rowParams);
        activate.setOnCheckedChangeListener((view, checked) -> accountEnabled = checked);

        // A 1dp separator between the account switch and the advanced fold.
        View line = new View(host);
        line.setBackgroundColor(host.getColor(R.color.surface));
        LinearLayout.LayoutParams lineParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dp(1));
        lineParams.setMargins(0, host.dp(12), 0, host.dp(12));
        content.addView(line, lineParams);

        // The per-addressbook sections below only matter on multi-book
        // accounts or for spoke-level tuning, so they hide behind a fold.
        CheckBox advanced = new CheckBox(host);
        advanced.setChecked(settingsAdvancedOpen);
        content.addView(optionRow(R.string.account_advanced, advanced), rowParams);
        advanced.setOnCheckedChangeListener(
                (view, checked) -> {
                    settingsAdvancedOpen = checked;
                    renderAccountSettings();
                });

        if (!settingsAdvancedOpen) {
            return;
        }

        for (BookEntry entry : settingsBooks) {
            BookSettings staged = bookSettings.get(entry.book.url);

            TextView header = new TextView(host);
            header.setText(entry.book.name);
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            header.setTextColor(host.resolveColor(android.R.attr.textColorPrimary));
            header.setSingleLine(true);
            header.setEllipsize(android.text.TextUtils.TruncateAt.END);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setMinimumHeight(host.dp(48));
            header.setPadding(host.dp(16), 0, host.dp(16), 0);
            content.addView(header, rowParams);

            LinearLayout rows = new LinearLayout(host);
            rows.setOrientation(LinearLayout.VERTICAL);
            rows.setPadding(host.dp(12), 0, 0, 0);
            content.addView(rows, rowParams);

            CheckBox enable = new CheckBox(host);
            enable.setChecked(staged.enabled);
            rows.addView(optionRow(R.string.book_enable, enable), rowParams);
            enable.setOnCheckedChangeListener(
                    (view, checked) -> {
                        staged.enabled = checked;
                        staged.remote = checked;
                        staged.local = checked;
                        renderAccountSettings();
                    });

            // The spoke switches need the book on, so their rows dim
            // with it.
            CheckBox remote = new CheckBox(host);
            remote.setChecked(staged.remote);
            remote.setEnabled(staged.enabled);
            View remoteRow = optionRow(R.string.book_remote_sync, remote);
            remoteRow.setAlpha(staged.enabled ? 1f : 0.5f);
            rows.addView(remoteRow, rowParams);
            remote.setOnCheckedChangeListener((view, checked) -> staged.remote = checked);

            CheckBox local = new CheckBox(host);
            local.setChecked(staged.local);
            local.setEnabled(staged.enabled);
            View localRow = optionRow(R.string.book_local_sync, local);
            localRow.setAlpha(staged.enabled ? 1f : 0.5f);
            rows.addView(localRow, rowParams);
            local.setOnCheckedChangeListener((view, checked) -> staged.local = checked);
        }
    }

    /**
     * Wires a spinner's user picks, swallowing the selection callback
     * Android fires on layout for the initial value.
     */
    private void onPicked(Spinner spinner, java.util.function.IntConsumer picked) {
        spinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    private boolean initial = true;

                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        if (initial) {
                            initial = false;
                            return;
                        }
                        picked.accept(position);
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
    }

    /** Commits the staged switches and returns to the drawer. */
    void save() {
        AccountActivation.set(host, settingsEmail, accountEnabled);
        for (Map.Entry<String, BookSettings> staged : bookSettings.entrySet()) {
            BookSettings state = staged.getValue();
            host.base.setBookState(staged.getKey(), state.enabled, state.remote, state.local);
        }

        // The subscription switches move what the contacts root shows.
        host.reloadContacts();
        host.filterChanged();
        leave();
    }

    /**
     * Where this account sends, and a way to change it.
     *
     * <p>The one setting on this screen an account can be missing
     * outright: a mail account connected before submission was asked
     * about, or one whose address published nothing to send through, has
     * no sender and no way to become one. Without this the repair is
     * deleting the account and hoping discovery goes differently.
     *
     * <p>Committed when the dialog closes rather than staged for the
     * FAB, which commits the addressbook switches: this is one endpoint,
     * it is entered and it is done, and holding it back would make the
     * screen say something the account does not.
     */
    private void addSubmission(LinearLayout content, LinearLayout.LayoutParams rowParams) {
        AccountEntry account = host.accountFor(settingsEmail);
        if (account == null || !account.covers(PimDomain.MAIL)) {
            return;
        }
        // NOTE: Graph and Gmail submit through the account they read from,
        // so there is no server of its own to change.
        Account mail = account.server(PimDomain.MAIL);
        if (PimalayaClient.isGraph(mail) || PimalayaClient.isGoogle(mail)) {
            return;
        }

        String current = account.server(PimDomain.MAIL).submitUrl;
        String value =
                current == null || current.isEmpty()
                        ? host.getString(R.string.account_send_none)
                        : hostOf(current);

        TextView row = new TextView(host);
        row.setText(host.getString(R.string.account_send, value));
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.setTextColor(host.resolveColor(android.R.attr.textColorPrimary));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(host.dp(48));
        row.setPadding(host.dp(16), 0, host.dp(16), 0);
        row.setOnClickListener(view -> promptSubmission(current));
        content.addView(row, rowParams);

        View line = new View(host);
        line.setBackgroundColor(host.getColor(R.color.surface));
        LinearLayout.LayoutParams lineParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dp(1));
        lineParams.setMargins(0, host.dp(12), 0, host.dp(12));
        content.addView(line, lineParams);
    }

    /**
     * How much of this account's mail syncs: all of it, or the last N
     * months.
     *
     * <p>Committed when picked rather than staged for the FAB, as the
     * sending server is. A narrower bound frees what falls below it at
     * once, the messages staying on the server; a wider one is listed by
     * the next sync.
     */
    private void addMailScope(LinearLayout content) {
        AccountEntry account = host.accountFor(settingsEmail);
        if (account == null || !account.covers(PimDomain.MAIL)) {
            return;
        }
        String email = settingsEmail;

        Spinner scope = new Spinner(host);
        android.widget.ArrayAdapter<CharSequence> choices =
                android.widget.ArrayAdapter.createFromResource(
                        host, R.array.mail_scopes, R.layout.spinner_form_item);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        scope.setAdapter(choices);
        scope.setPadding(0, 0, scope.getPaddingRight(), 0);
        scope.setMinimumHeight(host.dp(48));

        int months = host.mail.monthsOf(email);
        int shown = 0;
        for (int index = 0; index < MailScope.MONTHS.length; index++) {
            if (MailScope.MONTHS[index] == months) {
                shown = index;
            }
        }
        scope.setSelection(shown);
        // NOTE: a whole mailbox is all of it, which the offline setting says.
        boolean whole =
                MailOffline.policy(host, host.mail.accountIdOf(email))
                        == MailOffline.Policy.WHOLE;
        scope.setEnabled(!whole);
        scope.setAlpha(whole ? 0.5f : 1f);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        // NOTE: the framework caret renders further in than the checkbox
        // glyph, so the spinner stops 8dp short of the rows' end padding
        // to line the caret up with the boxes.
        params.setMarginStart(host.dp(16));
        params.setMarginEnd(host.dp(4));
        content.addView(scope, params);
        onPicked(
                scope,
                position -> {
                    int picked = MailScope.MONTHS[position];
                    host.io.execute(
                            () -> {
                                try {
                                    int collected = host.mail.bound(email, picked);
                                    Log.d(
                                            "pimalaya",
                                            "mail of " + email + " bounded to " + picked
                                                    + " months, " + collected + " collected");
                                } catch (Exception error) {
                                    Log.w("pimalaya", "mail bound failed for " + email, error);
                                }
                                host.postAlive(host.mailList::reload);
                            });
                });

        View line = new View(host);
        line.setBackgroundColor(host.getColor(R.color.surface));
        LinearLayout.LayoutParams lineParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dp(1));
        lineParams.setMargins(0, host.dp(12), 0, host.dp(12));
        content.addView(line, lineParams);
    }

    /**
     * Which bodies this account downloads before they are opened, and
     * whether on a metered network too ({@link MailOffline}).
     *
     * <p>Committed when picked, as the bound is. Whole mailbox sets the
     * bound to all mail, which the scope above then shows and holds; the
     * body step runs after each pass and fill step while the app is open,
     * and a change here starts or replans it.
     */
    private void addMailOffline(LinearLayout content, LinearLayout.LayoutParams rowParams) {
        AccountEntry account = host.accountFor(settingsEmail);
        if (account == null || !account.covers(PimDomain.MAIL)) {
            return;
        }
        String email = settingsEmail;
        String accountId = host.mail.accountIdOf(email);

        Spinner policy = new Spinner(host);
        android.widget.ArrayAdapter<CharSequence> choices =
                android.widget.ArrayAdapter.createFromResource(
                        host, R.array.mail_offline_policies, R.layout.spinner_form_item);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        policy.setAdapter(choices);
        policy.setPadding(0, 0, policy.getPaddingRight(), 0);
        policy.setMinimumHeight(host.dp(48));
        policy.setSelection(MailOffline.policy(host, accountId).ordinal());

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(host.dp(16));
        params.setMarginEnd(host.dp(4));
        content.addView(policy, params);
        onPicked(
                policy,
                position -> {
                    MailOffline.Policy picked = MailOffline.Policy.values()[position];
                    MailOffline.setPolicy(host, accountId, picked);
                    if (picked != MailOffline.Policy.WHOLE) {
                        renderAccountSettings();
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
                                            renderAccountSettings();
                                            host.mailList.reload();
                                            host.fillMail();
                                        });
                            });
                });

        CheckBox metered = new CheckBox(host);
        metered.setChecked(MailOffline.metered(host, accountId));
        content.addView(optionRow(R.string.mail_offline_metered, metered), rowParams);
        metered.setOnCheckedChangeListener(
                (view, checked) -> {
                    MailOffline.setMetered(host, accountId, checked);
                    host.fillMail();
                });

        View line = new View(host);
        line.setBackgroundColor(host.getColor(R.color.surface));
        LinearLayout.LayoutParams lineParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dp(1));
        lineParams.setMargins(0, host.dp(12), 0, host.dp(12));
        content.addView(line, lineParams);
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
        android.widget.EditText field =
                host.ui.field(R.string.manual_submit_server, current == null ? "" : hostOf(current));

        LinearLayout fields = new LinearLayout(host);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(host.dp(24), host.dp(8), host.dp(24), 0);
        fields.addView(field);

        new AlertDialog.Builder(host)
                .setTitle(R.string.send_mail)
                .setMessage(R.string.manual_submit_message)
                .setView(fields)
                .setPositiveButton(
                        R.string.password_submit,
                        (dialog, which) -> {
                            String entered = field.getText().toString().trim();
                            host.store.submitThrough(
                                    email, entered.isEmpty() ? null : submitUrl(entered));
                            renderAccountSettings();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** What was typed, as the endpoint a submission opens. */
    private static String submitUrl(String entered) {
        return PimalayaClient.submitUrl(entered);
    }

    /** The host part of an endpoint, the endpoint itself when it has none. */
    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
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
     * One settings row: the label on the left, the checkbox at the end,
     * vertically centred on a shared height; tapping anywhere on the row
     * toggles the box (while it is enabled). Pads itself like the edit
     * form's rows (the container stays unpadded for the separators);
     * the 12dp end centres the checkbox glyph under the bar's 48dp
     * buttons (4dp bar padding + 24 = 28dp centreline, the glyph
     * sitting 16dp in from the widget's end).
     */
    private View optionRow(int label, CheckBox box) {
        TextView text = new TextView(host);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setTextColor(host.resolveColor(android.R.attr.textColorPrimary));
        text.setLayoutParams(
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(host.dp(48));
        row.setPadding(host.dp(16), 0, host.dp(12), 0);
        row.addView(text);
        row.addView(box);
        row.setOnClickListener(
                view -> {
                    if (box.isEnabled()) {
                        box.toggle();
                    }
                });
        return row;
    }

    /** Confirms deletion of the account whose settings screen is open. */
    void confirmDeleteCurrent() {
        confirmDeleteAccount(settingsEmail);
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
                .setTitle(R.string.delete_account)
                .setMessage(R.string.delete_account_confirm)
                .setPositiveButton(
                        android.R.string.ok,
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
        // NOTE: before its collections go, which is what names them.
        host.forgetViews(email);

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
    }
}
