package org.pimalaya.setup;

import android.app.AlertDialog;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pimalaya.R;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.Endpoints;
import org.pimalaya.account.Oauth;
import org.pimalaya.account.PimDomain;
import org.pimalaya.calendar.PhoneCalendars;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.AuthMethod;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.OauthGrant;
import org.pimalaya.client.OauthTokens;
import org.pimalaya.client.ServiceConfig;
import org.pimalaya.client.Transport;
import org.pimalaya.phone.Accounts;
import org.pimalaya.phone.PhoneMirror;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.sync.BackgroundCheck;
import org.pimalaya.sync.SyncCoordinator;
import org.pimalaya.sync.SyncPasses;
import org.pimalaya.ui.CardRows;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.PillField;

/**
 * The connection wizard behind the auth panel, address to first sync.
 *
 * <p>Discovery, domains, sign-in cards, then the book selection persisting the
 * account; {@link OauthFlow} lands tokens in {@link #connect}. Nothing persists
 * before that, so backing out leaves everything untouched.
 */
public final class OnboardingFlow {
    /** The auth flow's steps, inside its own flipper under one bar. */
    public static final int STEP_EMAIL = 0;

    /** What to connect this address for, once discovery has answered. */
    public static final int STEP_DOMAIN = 1;

    /** The configuration page (panel_config). */
    public static final int STEP_CONFIG = 2;
    /** The address book selection, whose commit persists the account. */
    public static final int STEP_BOOKS = 3;

    /** The standard setup's outcome when some servers refused the password. */
    public static final int STEP_RESULT = 4;

    /** The advanced setup's sign-ins, one card per server and login. */
    public static final int STEP_SIGNIN = 5;

    /** The OAuth client a server registers none of. */
    public static final int STEP_OAUTH = 6;

    private final Host host;
    private final SyncPasses.Parts parts;

    /** The activity's live account list (mutated on the main thread). */
    private final List<AccountEntry> accounts;

    private final SyncCoordinator sync;
    private final SetupShell shell;
    private final OauthFlow oauth;

    /** Onboarding state: the entered email and its searched configs. */
    private String pendingEmail;

    private List<ServiceConfig> searchedConfigs = new ArrayList<>();

    /** The setup screen's per-domain sections. */
    private final java.util.Map<PimDomain, DomainSetup> setups =
            new java.util.EnumMap<>(PimDomain.class);

    /**
     * The domains one in-flight browser grant signs in for, with endpoints.
     *
     * <p>Empty outside a grant. Several, as one authorization server covers
     * every domain that chose it, its tokens belonging to all of them.
     */
    private final java.util.Map<PimDomain, String> oauthGroup =
            new java.util.EnumMap<>(PimDomain.class);

    /** The account being built up, one domain's connection at a time. */
    private AccountEntry connectedAccount;

    /** The sign-ins Continue planned, one card each; null outside them. */
    private List<AuthStep> authSteps;

    /** The first card asking for a password, which later ones may reuse. */
    private AuthStep firstPassword;

    /** The card whose browser grant is in flight. */
    private AuthStep pendingStep;

    /** Whether Connect's chain of grants is running, each starting the next. */
    private boolean connecting;

    /** Whether this run shows the sign-in page, which back returns to. */
    private boolean signInPage;

    /**
     * The fixed provider rule the address matched ({@code provider:google}...).
     *
     * <p>Null when none. Kept beside the discovered configs: a provider match
     * says how to sign in for contacts, not whether the address also serves
     * mail or calendars, which only the protocol sweep answers.
     */
    private String matchedProvider;

    /**
     * Whether the advanced setup runs: configurations per domain.
     *
     * <p>Off by default, the standard setup being the path; the links on the
     * address and domain steps turn it on.
     */
    private boolean advanced;

    /** The just-connected account's addressbooks. */
    private List<Addressbook> pendingBooks;

    /** The email whose books step is open. */
    private String connectedEmail;

    /** The books step's subscribe checkboxes. */
    private List<BookChoice> bookChoices = new ArrayList<>();

    /** Whether the one password is being tried against the servers. */
    private boolean verifying;

    /** Both setups' notification and phone-app options, off until permitted. */
    private final SetupSwitches switches = new SetupSwitches();

    /**
     * Whether another app fills the phone's Contacts app with this address.
     *
     * <p>{@link Accounts#elsewhere}; it keeps the books off it.
     */
    private boolean elsewhere;

    /** The same for the calendars ({@link Accounts#calendarsElsewhere}). */
    private boolean calendarsElsewhere;

    /** Builds the wizard; {@link #bind} wires its views once laid out. */
    public OnboardingFlow(
            Host host,
            SyncPasses.Parts parts,
            List<AccountEntry> accounts,
            SyncCoordinator sync,
            SetupShell shell,
            OauthFlow oauth) {
        this.host = host;
        this.parts = parts;
        this.accounts = accounts;
        this.sync = sync;
        this.shell = shell;
        this.oauth = oauth;
    }

    /** Wires the steps' own buttons and fields, once the views exist. */
    public void bind() {
        EditText email = host.findViewById(R.id.email_input);
        EditText password = host.findViewById(R.id.domain_password);

        for (int id :
                new int[] {
                    R.id.email_continue,
                    R.id.domain_connect,
                    R.id.result_continue,
                    R.id.signin_continue,
                    R.id.oauth_continue,
                    R.id.books_continue
                }) {
            ((TextView) host.findViewById(id)).setTextColor(host.accentContrast());
        }

        host.findViewById(R.id.email_continue)
                .setOnClickListener(
                        view -> {
                            advanced = false;
                            submitEmail();
                        });
        host.findViewById(R.id.email_advanced)
                .setOnClickListener(
                        view -> {
                            advanced = true;
                            if (emailSubmittable()) {
                                submitEmail();
                            } else {
                                email.requestFocus();
                            }
                        });
        email.setOnEditorActionListener(
                (view, action, event) -> {
                    if (emailSubmittable()) {
                        submitEmail();
                    }
                    return true;
                });

        // NOTE: a password input type sets a monospace face; the field
        // keeps the address field's.
        password.setTypeface(android.graphics.Typeface.DEFAULT);
        password.setOnFocusChangeListener(
                (view, focused) -> {
                    if (focused) {
                        revealPassword();
                    }
                });
        password.setOnClickListener(view -> revealPassword());

        host.findViewById(R.id.domain_connect).setOnClickListener(view -> confirmSetup());
        host.findViewById(R.id.signin_continue).setOnClickListener(view -> connectAll());
        host.findViewById(R.id.books_continue).setOnClickListener(view -> confirmBooks());
        host.findViewById(R.id.domain_advanced).setOnClickListener(view -> switchToAdvanced());
        password.setOnEditorActionListener(
                (view, action, event) -> {
                    if (host.findViewById(R.id.domain_connect).isEnabled()) {
                        confirmSetup();
                    }
                    return true;
                });
        password.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        showPasswordError(false);
                        if (!verifying) {
                            resetSetupContinue();
                        }
                    }
                });

        android.widget.ImageButton toggle = host.findViewById(R.id.domain_password_toggle);
        toggle.setOnClickListener(
                view -> {
                    boolean shown =
                            password.getTransformationMethod()
                                    instanceof android.text.method.PasswordTransformationMethod;
                    password.setTransformationMethod(
                            shown
                                    ? null
                                    : android.text.method.PasswordTransformationMethod
                                            .getInstance());
                    password.setSelection(password.getText().length());
                    toggle.setImageResource(shown ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
                    toggle.setContentDescription(
                            host.getString(shown ? R.string.password_hide : R.string.password_show));
                });
    }

    /**
     * Scrolls the domain step to its end, the password above the keyboard.
     *
     * <p>Delayed past the keyboard's resize, which would clip the scroll.
     */
    private void revealPassword() {
        android.widget.ScrollView scroll = host.findViewById(R.id.domain_scroll);
        scroll.postDelayed(
                () -> scroll.smoothScrollTo(0, scroll.getChildAt(0).getHeight()), 300);
    }

    /**
     * Scrolls the page so {@code view} sits at its top, what it opened below.
     *
     * <p>{@code view} is a domain just ticked or a card whose configuration was
     * just picked. Posted past the layout the tick changes, as the password's
     * reveal is.
     */
    private void reveal(View view) {
        view.post(
                () -> {
                    int top = 0;
                    View at = view;
                    while (at.getParent() instanceof View
                            && !(at.getParent() instanceof android.widget.ScrollView)) {
                        top += at.getTop();
                        at = (View) at.getParent();
                    }
                    if (at.getParent() instanceof android.widget.ScrollView) {
                        ((android.widget.ScrollView) at.getParent())
                                .smoothScrollTo(0, top + at.getTop());
                    }
                });
    }

    /** Resets the flow to its first step and shows it. */
    public void open() {
        pendingEmail = null;
        searchedConfigs = new ArrayList<>();
        matchedProvider = null;
        advanced = false;
        verifying = false;
        connecting = false;
        signInPage = false;
        switches.reset();
        elsewhere = false;
        calendarsElsewhere = false;
        authSteps = null;
        pendingStep = null;
        setups.clear();
        oauthGroup.clear();
        connectedAccount = null;
        ((EditText) host.findViewById(R.id.email_input)).setText("");
        ((EditText) host.findViewById(R.id.domain_password)).setText("");
        host.setAuthLoading(R.id.email_continue, R.id.email_progress, false);
        host.setAuthLoading(R.id.domain_connect, R.id.domain_progress, false);
        shell.showAuth(STEP_EMAIL);
    }

    /** Brings a step's own buttons in line with its state, on entering it. */
    public void refreshStep(int step) {
        if (step == STEP_EMAIL) {
            host.setFabEnabled(R.id.email_continue, emailSubmittable());
        } else if (step != STEP_OAUTH) {
            resetConfigContinue();
        }
    }

    /** Whether the email field holds a submittable address or URI. */
    public boolean emailSubmittable() {
        String address =
                ((EditText) host.findViewById(R.id.email_input)).getText().toString().trim();
        return !address.isEmpty() && !address.contains(" ");
    }

    /**
     * The email step's continue: discovery, always.
     *
     * <p>Asking for "an email, a server or a URI" made the user do the app's
     * job. Nothing found offers manual entry on the next step, as a fallback;
     * the advanced setup is a link beside it.
     */
    private void submitEmail() {
        host.hideKeyboard();

        pendingEmail =
                ((EditText) host.findViewById(R.id.email_input)).getText().toString().trim();
        search();
    }

    /** True inside a standard (one-tap) account setup. */
    private boolean simpleSetup() {
        return !advanced;
    }

    /**
     * Searches the address's service configs, then proposes them.
     *
     * <p>The MX probe decides first: mail at Google or Microsoft skips the
     * protocol sweep (PACC, CardDAV, JMAP), noise there. A failure stays on the
     * email panel; nothing found falls back to manual rows.
     */
    private void search() {
        host.setAuthLoading(R.id.email_continue, R.id.email_progress, true);

        host.io.execute(
                () -> {
                    // NOTE: a null resolver falls back to the bridge's
                    // DNS-over-HTTPS default, which works on mobile
                    // networks that block outbound DNS over TCP.
                    String provider = null;
                    if (!emailLogin().isEmpty()) {
                        try {
                            List<ServiceConfig> hits;
                            try (Transport transport = new Transport()) {
                                hits =
                                        parts.clients()
                                                .account()
                                                .searchProvider(transport, pendingEmail, null);
                            }
                            provider = hits.isEmpty() ? null : hits.get(0).source();
                        } catch (Exception error) {
                            Log.w("pimalaya", "provider probe failed", error);
                        }
                    }

                    // NOTE: the protocol sweep runs even when a provider rule
                    // matched, which the contacts-only flow used to skip. A
                    // rule says how to sign in for contacts at Google or
                    // Microsoft; only the sweep can say whether the same
                    // address also publishes mail or calendars, and that is
                    // the whole question the next step asks.
                    List<ServiceConfig> configs = new ArrayList<>();
                    Exception failure = null;
                    try {
                        configs = parts.clients().account().searchAll(pendingEmail, null);
                    } catch (Exception error) {
                        // NOTE: individual failing mechanisms are skipped
                        // inside the search; this is the whole search
                        // dying.
                        Log.w("pimalaya", "config search failed", error);
                        failure = error;
                    }

                    List<ServiceConfig> found = configs;
                    String matched = provider;
                    Exception searchFailure = failure;
                    host.postAlive(
                            () -> {
                                host.setAuthLoading(
                                        R.id.email_continue, R.id.email_progress, false);
                                if (searchFailure != null && found.isEmpty() && matched == null) {
                                    host.showError(searchFailure, R.string.discover_failed);
                                    return;
                                }
                                searchedConfigs = found;
                                matchedProvider = matched;
                                setups.clear();
                                showSetup();
                            });
                });
    }

    /** One domain's section of the setup screen. */
    private static final class DomainSetup {
        final PimDomain domain;

        DomainSetup(PimDomain domain) {
            this.domain = domain;
        }

        final List<SetupOption> options = new ArrayList<>();
        final List<android.widget.RadioButton> buttons = new ArrayList<>();

        /** The card's rows under its header, hidden while it is off. */
        View body;

        /**
         * Where this domain sends, for mail and for nothing else.
         *
         * <p>Every other domain leaves these empty: contacts and calendars
         * have nothing to submit, and a mail account reading over JMAP
         * submits through the session it already has.
         */
        final List<SubmitOption> submitOptions = new ArrayList<>();

        final List<android.widget.RadioButton> submitButtons = new ArrayList<>();

        /** Each sending row with its hairline, hidden with its option. */
        final List<View> submitRows = new ArrayList<>();

        /** Everything drawn under the sending heading, hidden with it. */
        final List<View> submitViews = new ArrayList<>();

        SubmitOption submitSelected;

        /**
         * Whether this domain is being connected at all.
         *
         * <p>Off to begin with: an address that offers three domains is not a
         * request for three, and starting them all on makes the screen a list
         * of things to switch off rather than a choice to make.
         */
        boolean enabled;

        SetupOption selected;

        /** Where this domain lives; null until the sequence runs. */
        String baseUrl;

        /**
         * What signing in produced; null until the sequence runs.
         *
         * <p>Shared with the other domains of the same step when a
         * browser grant covered several: one consent is one credential,
         * and this is where that begins.
         */
        AccountCredential credential;

        /** Whether this domain is on and has something to connect to. */
        boolean ready() {
            return enabled && selected != null;
        }
    }

    /**
     * One sign-in the selection owes: a sign-in card, or a browser hop.
     *
     * <p>Planned, not run per domain: domains behind one authorization server
     * are one hop, domains on one server under one login one password.
     */
    private static final class AuthStep {
        final List<PimDomain> domains = new ArrayList<>();
        final SetupOption option;

        /** The card's fields, null for the ones its method does not ask. */
        PillField server;

        PillField login;
        PillField secret;

        /** Where mail sends, when mail's sending was entered manually. */
        PillField submit;

        /** "Same login and password as", from the second password card on. */
        CheckBox same;

        /** The card's outcome line: signed in, or what went wrong. */
        TextView status;

        /** The browser card's button and its own-client link. */
        View browser;

        View ownClient;

        /** Whether this card's domains hold their credential. */
        boolean signed;

        AuthStep(SetupOption option) {
            this.option = option;
        }

        /** Whether this card signs in through a browser. */
        boolean oauth() {
            return option.isOauth();
        }

        /** Whether this card asks for a login and a password. */
        boolean password() {
            return option.method == null || option.method.type() == AuthMethod.Type.PASSWORD;
        }

        /** Whether the "same as" box is ticked, its fields reused. */
        boolean reuses() {
            return same != null && same.isChecked();
        }
    }

    /**
     * Fills the setup screen with every domain this address offers.
     *
     * <p>Standard: ticked rows of what it can connect, each its best option
     * ({@link SetupPlan#standardOption}). Advanced: a switch per domain over
     * its configurations, one or none, keeping the standard screen's picks.
     */
    private void showSetup() {
        ((TextView) host.findViewById(R.id.domain_email)).setText(pendingEmail);

        Map<PimDomain, DomainSetup> previous = new java.util.EnumMap<>(PimDomain.class);
        previous.putAll(setups);

        LinearLayout container = host.findViewById(R.id.domain_container);
        container.removeAllViews();
        setups.clear();

        AccountEntry existing = AccountEntry.find(accounts, pendingEmail);
        boolean discovered = false;
        boolean connectable = false;

        for (PimDomain domain : PimDomain.values()) {
            DomainSetup setup = new DomainSetup(domain);
            setup.options.addAll(optionsFor(domain));
            discovered |= !setup.options.isEmpty();
            if (simpleSetup()) {
                setup.selected = SetupPlan.standardOption(setup.domain, setup.options, pendingEmail);
                // NOTE: unticked to begin with, as the advanced setup is: each
                // ticked domain opens its own options to read, and all three
                // at once is a wall of them.
                DomainSetup before = previous.get(domain);
                setup.enabled = setup.selected != null && before != null && before.enabled;
            } else {
                setup.options.add(manualOption());
                DomainSetup before = previous.get(domain);
                if (before != null && before.enabled && before.selected != null) {
                    setup.enabled = true;
                    setup.selected = sameOption(setup, before.selected);
                }
            }
            if (domain == PimDomain.MAIL) {
                setup.submitOptions.addAll(submissionOptions());
                // NOTE: the best endpoint found, switched on. Connecting mail
                // is asking for mail, and an account that reads and cannot
                // answer is not what anybody meant; saying so is what the
                // row for none is there for.
                setup.submitSelected = SetupPlan.firstOffered(setup.selected, setup.submitOptions);
                if (!simpleSetup()) {
                    // NOTE: offered only beside reading over the same API,
                    // which renderSection works out as the reading choice
                    // moves.
                    if (discovered("msgraph")) {
                        setup.submitOptions.add(
                                new SubmitOption(
                                        host.getString(R.string.config_msgraph),
                                        "graph.microsoft.com",
                                        AccountClient.msgraphBase(pendingEmail),
                                        false,
                                        null,
                                        "msgraph",
                                        null));
                    }
                    if (discovered("gmail")) {
                        setup.submitOptions.add(
                                new SubmitOption(
                                        host.getString(R.string.config_gmail),
                                        "gmail.googleapis.com",
                                        AccountClient.googleBase(pendingEmail),
                                        false,
                                        null,
                                        "gmail",
                                        null));
                    }
                    SubmitOption none =
                            new SubmitOption(
                                    host.getString(R.string.send_mail_none), null, null, false);
                    setup.submitOptions.add(none);
                    setup.submitOptions.add(
                            new SubmitOption(
                                    host.getString(R.string.domain_manual), null, null, true));
                    if (setup.submitSelected == null || setup.submitSelected.through != null) {
                        // NOTE: the row for none, so the group opens on an
                        // answer: not sending is a choice, and the only one
                        // this address offers.
                        setup.submitSelected = none;
                    }
                }
            }
            connectable |= connectable(setup);
            setups.put(domain, setup);
            if (!simpleSetup()) {
                container.addView(sectionOf(setup, existing != null && existing.covers(domain)));
            }
        }

        if (simpleSetup() && !connectable) {
            offerAdvanced();
            return;
        }

        TextView heading = host.findViewById(R.id.domain_heading);
        TextView message = host.findViewById(R.id.domain_message);
        if (simpleSetup()) {
            heading.setText(R.string.domain_title_simple);
            message.setVisibility(View.GONE);
            container.addView(domainCard(existing));
        } else {
            heading.setText(R.string.domain_title);
            message.setText(discovered ? R.string.domain_message : R.string.domain_none);
            message.setVisibility(View.VISIBLE);
        }

        showPasswordError(false);
        shell.showAuth(STEP_DOMAIN);
        resetSetupContinue();
    }

    /** The option of a fresh setup reading the same as one picked before. */
    private SetupOption sameOption(DomainSetup setup, SetupOption picked) {
        String label = optionLabel(picked);
        for (SetupOption option : setup.options) {
            if (optionLabel(option).equals(label)) {
                return option;
            }
        }
        return null;
    }

    /** Leaves the standard setup for the advanced one, keeping the ticks. */
    private void switchToAdvanced() {
        advanced = true;
        host.hideKeyboard();
        showSetup();
    }

    /** The standard setup's card: a row per domain, glyph, name and tick. */
    private View domainCard(AccountEntry existing) {
        LinearLayout card = CardRows.card(host);

        for (DomainSetup setup : setups.values()) {
            if (!connectable(setup)) {
                continue;
            }
            if (card.getChildCount() > 0) {
                card.addView(CardRows.rowDivider(host));
            }
            CheckBox tick = new CheckBox(host);
            tick.setChecked(setup.enabled);
            tick.setClickable(false);
            tick.setFocusable(false);

            String status =
                    existing != null && existing.covers(setup.domain)
                            ? host.getString(R.string.domain_connected)
                            : null;
            LinearLayout row = domainRow(setup.domain, status, false, tick);
            // NOTE: the domain's option under it, shown while it is ticked.
            View option =
                    setup.domain == PimDomain.MAIL
                            ? notifyRow()
                            : phoneRow(PhoneMirror.of(setup.domain));
            row.setOnClickListener(
                    view -> {
                        if (verifying) {
                            return;
                        }
                        setup.enabled = !setup.enabled;
                        tick.setChecked(setup.enabled);
                        option.setVisibility(setup.enabled ? View.VISIBLE : View.GONE);
                        resetSetupContinue();
                        if (setup.enabled) {
                            reveal(view);
                        }
                    });
            card.addView(row);
            option.setVisibility(setup.enabled ? View.VISIBLE : View.GONE);
            card.addView(option);
        }
        return card;
    }

    /**
     * The tick mirroring the books or calendars on the phone.
     *
     * <p>Turned on, it asks the mirror's permissions, and a refusal turns it
     * back off.
     */
    private View phoneRow(PhoneMirror mirror) {
        CheckBox toggle = new CheckBox(host);
        toggle.setChecked(switches.mirrors.contains(mirror));
        toggle.setOnCheckedChangeListener(
                (view, checked) -> {
                    if (!checked) {
                        switches.mirror(mirror, false, Set.of());
                        return;
                    }
                    shell.askMirrors(
                            java.util.EnumSet.of(mirror),
                            granted -> toggle.setChecked(switches.mirror(mirror, true, granted)));
                });
        boolean contacts = mirror == PhoneMirror.CONTACTS;
        return CardRows.tickRow(
                host,
                contacts ? R.string.phone_contacts : R.string.phone_calendar,
                contacts ? R.string.phone_contacts_note : R.string.phone_calendar_note,
                toggle);
    }

    /**
     * The tick notifying the account's new mail.
     *
     * <p>Turned on, it asks the notifications permission; a refusal turns it
     * back off.
     */
    private View notifyRow() {
        CheckBox toggle = new CheckBox(host);
        toggle.setChecked(switches.notifies);
        toggle.setOnCheckedChangeListener(
                (view, checked) -> {
                    if (!checked) {
                        switches.notify(false, false);
                        return;
                    }
                    shell.askNotifications(
                            granted -> toggle.setChecked(switches.notify(true, granted)));
                });
        return CardRows.tickRow(
                host, R.string.background_notify, R.string.notify_mail_note, toggle);
    }

    /** One domain's row: glyph, name over a status line, trailing view. */
    private LinearLayout domainRow(PimDomain domain, String status, boolean error, View trailing) {
        return domainRow(domain, status, error, trailing, false);
    }

    /** The same row, its name in bold when it heads a card. */
    private LinearLayout domainRow(
            PimDomain domain, String status, boolean error, View trailing, boolean bold) {
        return CardRows.domainRow(
                host, domain.icon, host.getString(domain.label), status, error, trailing, bold);
    }

    /** Whether this domain can be switched on at all, in the running mode. */
    private boolean connectable(DomainSetup setup) {
        return simpleSetup() ? setup.selected != null : !setup.options.isEmpty();
    }

    /**
     * Sends a standard setup that has nothing to offer to the advanced one.
     *
     * <p>Every service wanting a browser grant or a token leaves three switches
     * that cannot turn on, which reads as an app that cannot connect this
     * provider.
     */
    private void offerAdvanced() {
        new AlertDialog.Builder(host)
                .setTitle(R.string.setup_advanced_link)
                .setMessage(R.string.setup_simple_unavailable)
                .setCancelable(false)
                .setPositiveButton(
                        R.string.setup_advanced,
                        (dialog, which) -> {
                            advanced = true;
                            showSetup();
                        })
                // NOTE: the flow is still on the address step, which is where
                // dismissing leaves it: another address is the other answer.
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * One domain's card in the advanced setup: header with switch, options.
     *
     * <p>While on, a radio row per configuration, manual entry last, then
     * sending rows. Off unless the standard screen ticked it: three domains
     * offered is not a request for three.
     */
    private View sectionOf(DomainSetup setup, boolean alreadyConnected) {
        LinearLayout card = CardRows.card(host);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = host.ui.dp(12);
        card.setLayoutParams(params);

        android.widget.Switch toggle = new android.widget.Switch(host);
        toggle.setChecked(setup.enabled);
        toggle.setOnCheckedChangeListener(
                (view, checked) -> {
                    setup.enabled = checked;
                    renderSection(setup);
                    resetSetupContinue();
                    if (checked) {
                        reveal(card);
                    }
                });
        LinearLayout header =
                domainRow(
                        setup.domain,
                        alreadyConnected ? host.getString(R.string.domain_connected) : null,
                        false,
                        toggle,
                        true);
        header.setOnClickListener(view -> toggle.toggle());
        card.addView(header);

        LinearLayout body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        setup.body = body;
        card.addView(body);

        for (SetupOption option : setup.options) {
            String detail =
                    option.isManual()
                            ? host.getString(R.string.domain_manual_detail)
                            : optionDetail(option.detail, option.method.type());
            android.widget.RadioButton button =
                    optionRow(
                            body,
                            option.label,
                            detail,
                            () -> {
                                setup.selected = option;
                                renderSection(setup);
                                resetSetupContinue();
                                reveal(card);
                            });
            setup.buttons.add(button);
        }

        if (setup.domain == PimDomain.MAIL) {
            addSubmission(body, setup);
            body.addView(notifyRow());
        } else {
            // NOTE: for every book ticked on the books page, which only
            // picks them, and every calendar the first sync lists.
            body.addView(phoneRow(PhoneMirror.of(setup.domain)));
        }

        renderSection(setup);
        return card;
    }

    /** A row's second line: the server, then how it signs in. */
    private String optionDetail(String server, AuthMethod.Type auth) {
        String method = auth == null ? null : authName(auth);
        if (server == null) {
            return method;
        }
        return method == null ? server : server + " · " + method;
    }

    /**
     * One radio row: radio, then protocol over server and sign-in.
     *
     * <p>Not a RadioGroup, so a group can start with nothing picked. Answers
     * the radio, which the caller checks.
     */
    private android.widget.RadioButton optionRow(
            LinearLayout into, String primary, String secondary, Runnable onPick) {
        LinearLayout item = new LinearLayout(host);
        item.setOrientation(LinearLayout.VERTICAL);
        item.addView(CardRows.rowDivider(host));

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(host.ui.dp(56));
        row.setPadding(host.ui.dp(14), host.ui.dp(8), host.ui.dp(16), host.ui.dp(8));
        TypedValue ripple = new TypedValue();
        host.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setForeground(host.getDrawable(ripple.resourceId));

        android.widget.RadioButton radio = new android.widget.RadioButton(host);
        radio.setClickable(false);
        radio.setFocusable(false);
        LinearLayout.LayoutParams radioParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        radioParams.setMarginEnd(host.ui.dp(8));
        row.addView(radio, radioParams);

        LinearLayout text = new LinearLayout(host);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView first = new TextView(host);
        first.setText(primary);
        first.setTextSize(16);
        first.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
        text.addView(first);
        if (secondary != null) {
            TextView second = new TextView(host);
            second.setText(secondary);
            second.setTextSize(13);
            second.setSingleLine(true);
            second.setEllipsize(android.text.TextUtils.TruncateAt.END);
            second.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
            text.addView(second);
        }
        row.addView(
                text,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(view -> onPick.run());

        item.addView(row);
        into.addView(item);
        radio.setTag(item);
        return radio;
    }

    /**
     * Draws where mail is sent from, in the advanced setup.
     *
     * <p>SMTP, what was discovered, not sending, manual entry. The standard
     * setup draws nothing: it takes the best endpoint
     * ({@link SetupPlan#firstOffered}) or none, settings adding one later.
     */
    private void addSubmission(LinearLayout body, DomainSetup setup) {
        TextView heading = new TextView(host, null, 0, R.style.SectionHeader);
        heading.setText(R.string.send_mail_advanced);
        heading.setPadding(host.ui.dp(60), host.ui.dp(16), host.ui.dp(16), host.ui.dp(6));
        setup.submitViews.add(heading);
        body.addView(heading);

        for (SubmitOption option : setup.submitOptions) {
            String detail =
                    option.detail == null || option.manual
                            ? null
                            : optionDetail(option.detail, option.auth);
            android.widget.RadioButton button =
                    optionRow(
                            body,
                            option.label,
                            detail,
                            () -> {
                                setup.submitSelected = option;
                                renderSection(setup);
                            });
            setup.submitButtons.add(button);
            setup.submitRows.add((View) button.getTag());
        }
    }

    /** A submission row's label: the protocol, and the server it names. */
    private String submitLabel(SubmitOption option) {
        String auth = option.auth == null ? "" : " · " + authName(option.auth);
        if (option.detail != null) {
            return option.label + " (" + option.detail + ")" + auth;
        }
        return option.url == null ? option.label : option.label + " (" + SetupPlan.hostOf(option.url) + ")";
    }

    /** Shows one card's rows while its switch is on, and checks the picks. */
    private void renderSection(DomainSetup setup) {
        // NOTE: gone, not dimmed. A switched-off domain has nothing to say
        // and a greyed list of protocols under it is noise the reader has to
        // skip past on the way to the domains they do want.
        setup.body.setVisibility(setup.enabled ? View.VISIBLE : View.GONE);
        for (int index = 0; index < setup.buttons.size(); index++) {
            setup.buttons.get(index).setChecked(setup.options.get(index) == setup.selected);
        }

        // NOTE: a pick the reading choice no longer allows falls back to the
        // first row it does, which is never empty: not sending is allowed
        // beside every reader.
        if (!setup.submitButtons.isEmpty()
                && (setup.submitSelected == null || !SetupPlan.offers(setup.selected, setup.submitSelected))) {
            for (SubmitOption option : setup.submitOptions) {
                if (SetupPlan.offers(setup.selected, option)) {
                    setup.submitSelected = option;
                    break;
                }
            }
        }

        for (View view : setup.submitViews) {
            view.setVisibility(sends(setup) ? View.VISIBLE : View.GONE);
        }
        for (int index = 0; index < setup.submitButtons.size(); index++) {
            SubmitOption option = setup.submitOptions.get(index);
            setup.submitButtons.get(index).setChecked(option == setup.submitSelected);
            setup.submitRows
                    .get(index)
                    .setVisibility(sends(setup) && SetupPlan.offers(setup.selected, option) ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Whether this section has a submission question to ask at all.
     *
     * <p>Only once mail is read over IMAP or Graph, the rows following that
     * choice. Not when off, nor over JMAP: RFC 8621 submits through the session
     * it reads from, so another server would be a second account.
     */
    private static boolean sends(DomainSetup setup) {
        return setup.enabled && setup.selected != null && setup.selected.asksSubmission();
    }

    /**
     * Brings the domain step's buttons in line with the plan.
     *
     * <p>Continue once a domain is on, each knows what to connect to, and any
     * shared password is typed. Nothing signs in yet: only the whole plan shows
     * two domains behind one server cost one hop.
     */
    private void resetSetupContinue() {
        host.setAuthLoading(R.id.domain_connect, R.id.domain_progress, false);

        boolean password = !sharedPassword().isEmpty();
        boolean browser = false;
        for (DomainSetup setup : setups.values()) {
            browser |= setup.ready() && setup.selected.isOauth();
        }

        boolean simple = simpleSetup();
        host.findViewById(R.id.domain_password_block)
                .setVisibility(simple && password ? View.VISIBLE : View.GONE);
        host.findViewById(R.id.domain_browser_note)
                .setVisibility(simple && !password && browser ? View.VISIBLE : View.GONE);

        Button connect = host.findViewById(R.id.domain_connect);
        if (!simple) {
            connect.setText(R.string.email_submit);
        } else if (!password && browser) {
            connect.setText(R.string.browser_continue);
        } else {
            connect.setText(R.string.password_submit);
        }

        TextView link = host.findViewById(R.id.domain_advanced);
        link.setVisibility(simple ? View.VISIBLE : View.GONE);
        link.setText(password ? R.string.setup_advanced_different : R.string.setup_advanced_link);

        boolean typed =
                ((EditText) host.findViewById(R.id.domain_password)).getText().length() > 0;
        host.setFabEnabled(R.id.domain_connect, setupReady() && (!simple || !password || typed));
    }

    private boolean setupReady() {
        boolean any = false;
        for (DomainSetup setup : setups.values()) {
            if (!setup.enabled) {
                continue;
            }
            if (setup.selected == null) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** Puts the running step's main button on its loader. */
    public void busy() {
        switch (shell.authStep()) {
            case STEP_SIGNIN:
                host.setAuthLoading(R.id.signin_continue, R.id.signin_progress, true);
                break;
            case STEP_OAUTH:
                host.setAuthLoading(R.id.oauth_continue, R.id.oauth_progress, true);
                break;
            case STEP_BOOKS:
                host.setAuthLoading(R.id.books_continue, R.id.books_progress, true);
                break;
            default:
                host.setAuthLoading(R.id.domain_connect, R.id.domain_progress, true);
                break;
        }
    }

    /** The running step's main button back to idle, unless a check runs. */
    public void resetConfigContinue() {
        if (verifying) {
            return;
        }
        switch (shell.authStep()) {
            case STEP_SIGNIN:
                host.setAuthLoading(R.id.signin_continue, R.id.signin_progress, false);
                refreshSignIn();
                break;
            case STEP_OAUTH:
                host.setAuthLoading(R.id.oauth_continue, R.id.oauth_progress, false);
                break;
            case STEP_BOOKS:
                host.setAuthLoading(R.id.books_continue, R.id.books_progress, false);
                updateBooksContinue();
                break;
            default:
                resetSetupContinue();
                break;
        }
    }

    /**
     * The ticked domains the standard setup's one password signs in to.
     *
     * <p>Each whose sign-in is a password under a known login. One with no
     * login (a bare domain typed) keeps its own prompt in the sequence.
     */
    private List<DomainSetup> sharedPassword() {
        List<DomainSetup> shared = new ArrayList<>();
        if (!simpleSetup()) {
            return shared;
        }
        for (DomainSetup setup : setups.values()) {
            if (setup.ready()
                    && setup.selected.method != null
                    && setup.selected.method.type() == AuthMethod.Type.PASSWORD
                    && setup.selected.login != null
                    && !setup.selected.login.isEmpty()) {
                shared.add(setup);
            }
        }
        return shared;
    }

    /** Shows the wrong-password line in place of the field's note, or not. */
    private void showPasswordError(boolean wrong) {
        TextView note = host.findViewById(R.id.domain_password_note);
        note.setText(wrong ? R.string.password_wrong : R.string.password_shared);
        note.setTextColor(
                host.ui.resolveColor(
                        wrong ? android.R.attr.colorError : android.R.attr.textColorSecondary));
    }

    /**
     * Everything discovery found for this domain, per config and method.
     *
     * <p>Manual entry is not among them: the advanced setup adds it, so an
     * empty list means the address offers this domain nothing.
     */
    private List<SetupOption> optionsFor(PimDomain domain) {
        List<SetupOption> options =
                SetupPlan.discoveredOptions(
                        domain,
                        new SetupPlan.Discovery(searchedConfigs, pendingEmail, emailLogin()),
                        this::protocolName);

        // NOTE: the provider sign-ins are contacts sign-ins (CardDAV, the
        // People API, Graph). No protocol signal reveals where contacts live at
        // Google or Microsoft, so the sign-in itself is the real test.
        if (domain == PimDomain.CONTACTS && matchedProvider != null) {
            addProviderOptions(options);
        }

        // NOTE: two rows reading the same are one choice offered twice, as
        // when a server takes OAuth through more than one grant. The first
        // kept is the best ranked.
        Map<String, SetupOption> distinct = new LinkedHashMap<>();
        for (SetupOption option : options) {
            distinct.putIfAbsent(optionLabel(option), option);
        }

        return new ArrayList<>(distinct.values());
    }

    /** The option that asks for a server instead of proposing one. */
    private SetupOption manualOption() {
        return new SetupOption(
                null, host.getString(R.string.domain_manual), null, null, null, null, null);
    }

    /**
     * The matched provider's contacts sign-ins, as options.
     *
     * <p>Hand-written: neither Google nor Microsoft publishes a CardDAV SRV
     * record or well-known, so the endpoints and scopes are the app's own
     * knowledge.
     */
    private void addProviderOptions(List<SetupOption> options) {
        if ("provider:google".equals(matchedProvider)) {
            // NOTE: the provider rule names Google's CardDAV itself now, so
            // the hand-written one would be the same row twice.
            if (!providerFound("carddav")) {
                options.add(
                        providerOption(
                                R.string.config_carddav,
                                AccountClient.googleCarddavBase(pendingEmail),
                                AuthMethod.codeGrant(
                                        Oauth.GOOGLE_AUTH_ENDPOINT,
                                        Oauth.GOOGLE_TOKEN_ENDPOINT,
                                        Oauth.GOOGLE_SCOPE)));
            }
            options.add(
                    providerOption(
                            R.string.config_google_api,
                            AccountClient.googleBase(pendingEmail),
                            AuthMethod.codeGrant(
                                    Oauth.GOOGLE_AUTH_ENDPOINT,
                                    Oauth.GOOGLE_TOKEN_ENDPOINT,
                                    Oauth.GOOGLE_PEOPLE_SCOPE)));
            return;
        }
        options.add(
                providerOption(
                        R.string.config_msgraph,
                        AccountClient.msgraphBase(pendingEmail),
                        AuthMethod.codeGrant(
                                Oauth.MICROSOFT_AUTH_ENDPOINT,
                                Oauth.MICROSOFT_TOKEN_ENDPOINT,
                                Oauth.MICROSOFT_SCOPE)));
    }

    /** Whether discovery named this service, whatever found it. */
    private boolean discovered(String service) {
        for (ServiceConfig config : searchedConfigs) {
            if (service.equals(config.service())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a provider rule named this service. */
    private boolean providerFound(String service) {
        for (ServiceConfig config : searchedConfigs) {
            if (service.equals(config.service()) && SetupPlan.fromProvider(config)) {
                return true;
            }
        }
        return false;
    }

    private SetupOption providerOption(int label, String baseUrl, AuthMethod method) {
        return new SetupOption(
                null,
                host.getString(label),
                SetupPlan.hostOf(baseUrl),
                baseUrl,
                // NOTE: Google and Microsoft authorize by scope, not by RFC
                // 8707 resource, and sending one earns an invalid_target from
                // them.
                null,
                method,
                emailLogin());
    }

    /** The user-facing name of a searched service kind. */
    private String protocolName(String service) {
        switch (service) {
            case "carddav":
                return host.getString(R.string.config_carddav);
            case "caldav":
                return host.getString(R.string.config_caldav);
            case "imap":
                return host.getString(R.string.config_imap);
            case "jmap":
                return host.getString(R.string.config_jmap);
            case "msgraph":
            case "msgraphCalendar":
                return host.getString(R.string.config_msgraph);
            case "gmail":
                return host.getString(R.string.config_gmail);
            case "gcal":
                return host.getString(R.string.config_gcal);
            default:
                return service;
        }
    }

    /** An option's label: the protocol, its server and how it signs in. */
    private String optionLabel(SetupOption option) {
        if (option.method == null) {
            return option.label;
        }
        String detail = option.detail == null ? "" : " (" + option.detail + ")";
        return option.label + detail + " · " + authName(option.method.type());
    }

    /** The user-facing name of an authentication method. */
    private String authName(AuthMethod.Type type) {
        switch (type) {
            case PASSWORD:
                return host.getString(R.string.config_password);
            case BEARER:
                return host.getString(R.string.config_token);
            default:
                return host.getString(R.string.config_oauth2);
        }
    }

    /**
     * Works out the sign-ins the selection needs, in order, one card each.
     *
     * <p>Grants pool by authorization server and audience
     * ({@link SetupPlan#audienceOf}), passwords and tokens by server and login
     * (one JMAP session, one password). Manual entry is never pooled.
     */
    private List<AuthStep> planAuthSteps() {
        Map<PimDomain, SetupOption> picked = new java.util.EnumMap<>(PimDomain.class);
        for (DomainSetup setup : setups.values()) {
            // NOTE: a domain the shared password already signed in to has
            // nothing left to ask.
            if (!setup.ready() || setup.credential != null) {
                continue;
            }
            picked.put(setup.domain, setup.selected);
        }

        List<AuthStep> steps = new ArrayList<>();
        for (List<PimDomain> domains : SetupPlan.signIns(picked)) {
            AuthStep step = new AuthStep(picked.get(domains.get(0)));
            step.domains.addAll(domains);
            steps.add(step);
        }
        return steps;
    }

    /**
     * Abandons the sign-ins and returns to the selection.
     *
     * <p>What this run signed in is dropped too: half a sequence is not half an
     * account, and keeping it would persist fewer domains than the screen
     * shows.
     */
    public void abortAuthSteps() {
        authSteps = null;
        pendingStep = null;
        connecting = false;
        signInPage = false;
        verifying = false;
        oauthGroup.clear();
        for (DomainSetup setup : setups.values()) {
            setup.credential = null;
        }
        shell.showAuth(STEP_DOMAIN);
        resetSetupContinue();
    }

    /**
     * A grant failed or was refused: the chain stops where it is.
     *
     * <p>What signed in keeps its credential, and the step's button comes back.
     */
    public void grantAborted() {
        connecting = false;
        resetConfigContinue();
    }

    /**
     * Back from the OAuth client page or the books, to the sign-in page.
     *
     * <p>Nothing lost; a standard setup that never showed one returns to the
     * domains.
     */
    public void backToSignIn() {
        if (!signInPage) {
            abortAuthSteps();
            return;
        }
        connecting = false;
        shell.showAuth(STEP_SIGNIN);
        resetConfigContinue();
    }

    /**
     * Plans the sign-ins the domains still owe and runs them.
     *
     * <p>On the sign-in page when one asks for something to type, or in the
     * advanced setup; straight to the browser otherwise, one grant after the
     * other.
     */
    private void runSignIns() {
        authSteps = planAuthSteps();
        boolean fields = false;
        for (AuthStep step : authSteps) {
            fields |= !step.oauth();
        }
        if (advanced || fields) {
            showSignIn();
        } else {
            nextGrant();
        }
    }

    /**
     * The sign-in page: a card per planned sign-in, domains and server on top.
     */
    private void showSignIn() {
        signInPage = true;
        ((TextView) host.findViewById(R.id.signin_line)).setText(pendingEmail);
        ((TextView) host.findViewById(R.id.signin_message)).setText(R.string.signin_message);

        LinearLayout container = host.findViewById(R.id.signin_container);
        container.removeAllViews();
        firstPassword = null;
        for (AuthStep step : authSteps) {
            container.addView(signInCard(step));
        }

        shell.showAuth(STEP_SIGNIN);
        resetConfigContinue();
    }

    /** One sign-in's card. */
    private View signInCard(AuthStep step) {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_group);
        card.setPadding(host.ui.dp(14), host.ui.dp(14), host.ui.dp(14), host.ui.dp(16));
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = host.ui.dp(12);
        card.setLayoutParams(params);

        LinearLayout header =
                CardRows.domainRow(
                        host,
                        step.domains.get(0).icon,
                        domainNames(step),
                        signInDetail(step),
                        false,
                        null,
                        true);
        header.setPadding(0, 0, 0, host.ui.dp(4));
        header.setMinimumHeight(0);
        header.setForeground(null);
        card.addView(header);
        View sentTo = sentTo(step);
        if (sentTo != null) {
            card.addView(sentTo);
        }

        PimDomain first = step.domains.get(0);
        if (step.oauth()) {
            Button browser = new Button(host);
            browser.setText(R.string.signin_browser);
            browser.setAllCaps(false);
            browser.setTextSize(15);
            browser.setTypeface(browser.getTypeface(), android.graphics.Typeface.BOLD);
            browser.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
            browser.setBackgroundResource(R.drawable.button_on_card);
            browser.setStateListAnimator(null);
            LinearLayout.LayoutParams browserParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, host.ui.dp(48));
            browserParams.topMargin = host.ui.dp(10);
            card.addView(browser, browserParams);
            browser.setOnClickListener(
                    view -> {
                        connecting = false;
                        startGrant(step, false);
                    });
            step.browser = browser;

            Button own = new Button(host, null, 0, R.style.TextLink);
            own.setText(R.string.signin_own_client);
            own.setTextSize(14);
            card.addView(
                    own,
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, host.ui.dp(40)));
            own.setOnClickListener(
                    view -> {
                        connecting = false;
                        startGrant(step, true);
                    });
            step.ownClient = own;
        } else if (step.password()) {
            if (firstPassword != null) {
                AuthStep reused = firstPassword;
                CheckBox same = new CheckBox(host);
                same.setText(host.getString(R.string.signin_same, domainNames(reused)));
                same.setTextSize(15);
                same.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
                same.setMinHeight(host.ui.dp(44));
                same.setOnCheckedChangeListener((view, checked) -> refreshSignIn());
                card.addView(same);
                step.same = same;
            } else {
                firstPassword = step;
            }
            if (step.option.isManual()) {
                step.server =
                        PillField.of(
                                host,
                                first == PimDomain.MAIL
                                        ? R.string.manual_mail_server
                                        : R.string.manual_dav_url,
                                "",
                                false,
                                true);
                card.addView(step.server.view);
            }
            String login = step.option.login != null ? step.option.login : emailLogin();
            step.login = PillField.of(host, R.string.custom_login, login, false, true);
            step.secret = PillField.of(host, R.string.password_hint, "", true, true);
            card.addView(step.login.view);
            card.addView(step.secret.view);
        } else {
            step.secret = PillField.of(host, R.string.hint_token, "", true, true);
            card.addView(step.secret.view);
        }

        DomainSetup mail = setups.get(PimDomain.MAIL);
        if (step.domains.contains(PimDomain.MAIL)
                && sends(mail)
                && mail.submitSelected != null
                && mail.submitSelected.manual) {
            step.submit =
                    PillField.of(host, R.string.manual_submit_server, "", false, true);
            card.addView(step.submit.view);
        }

        for (PillField field : new PillField[] {step.server, step.login, step.secret, step.submit}) {
            if (field != null) {
                field.input.addTextChangedListener(
                        new android.text.TextWatcher() {
                            @Override
                            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                            @Override
                            public void onTextChanged(CharSequence s, int a, int b, int c) {}

                            @Override
                            public void afterTextChanged(android.text.Editable s) {
                                // NOTE: an edit after a refusal is a new try.
                                step.status.setVisibility(View.GONE);
                                refreshSignIn();
                            }
                        });
            }
        }

        step.status = new TextView(host);
        step.status.setTextSize(13);
        step.status.setPadding(host.ui.dp(16), host.ui.dp(8), host.ui.dp(16), 0);
        step.status.setVisibility(View.GONE);
        card.addView(step.status);
        return card;
    }

    /**
     * Where a card's password or token goes, shown before it does.
     *
     * <p>Each endpoint's scheme and host, flagged outside the address's domain,
     * where a forged configuration would point. Null for a browser card, whose
     * secret the app never holds, and a server typed by hand.
     */
    private View sentTo(AuthStep step) {
        if (step.oauth() || step.option.isManual()) {
            return null;
        }
        List<String> urls = new ArrayList<>();
        for (PimDomain domain : step.domains) {
            urls.add(setups.get(domain).selected.baseUrl);
        }
        DomainSetup mail = setups.get(PimDomain.MAIL);
        if (step.domains.contains(PimDomain.MAIL)
                && sends(mail)
                && mail.submitSelected != null
                && !mail.submitSelected.manual
                && mail.submitSelected.url != null) {
            urls.add(mail.submitSelected.url);
        }

        String email = pendingEmail == null ? "" : pendingEmail;
        String domain = email.substring(email.lastIndexOf('@') + 1);
        java.util.Set<String> shown = new java.util.LinkedHashSet<>();
        boolean flagged = false;
        for (String url : urls) {
            String destination = Endpoints.destination(url);
            if (destination == null) {
                continue;
            }
            if (Endpoints.otherDomain(url, email)) {
                flagged = true;
                destination = host.getString(R.string.signin_other_domain, destination, domain);
            }
            shown.add(destination);
        }
        if (shown.isEmpty()) {
            return null;
        }

        TextView line = new TextView(host);
        line.setText(host.getString(R.string.signin_sent_to, String.join(", ", shown)));
        line.setTextSize(13);
        line.setTextColor(
                host.ui.resolveColor(
                        flagged ? android.R.attr.colorError : android.R.attr.textColorSecondary));
        line.setPadding(host.ui.dp(16), 0, host.ui.dp(16), host.ui.dp(4));
        return line;
    }

    /** A card's domains, by name: "Contacts, Calendar". */
    private String domainNames(AuthStep step) {
        List<String> names = new ArrayList<>();
        for (PimDomain domain : step.domains) {
            names.add(host.getString(domain.label));
        }
        return String.join(", ", names);
    }

    /**
     * A card's second line: protocol and server (or by hand), and the sender.
     */
    private String signInDetail(AuthStep step) {
        SetupOption option = step.option;
        if (option.isManual()) {
            return host.getString(R.string.signin_manual);
        }
        String detail = option.detail == null ? option.label : option.label + " · " + option.detail;
        DomainSetup mail = setups.get(PimDomain.MAIL);
        if (step.domains.contains(PimDomain.MAIL)
                && sends(mail)
                && mail.submitSelected != null
                && !mail.submitSelected.none()
                && !mail.submitSelected.manual
                && mail.submitSelected.through == null) {
            return host.getString(R.string.signin_sends, detail, mail.submitSelected.detail);
        }
        return detail;
    }

    /**
     * Brings the cards in line with their state, and Connect with them.
     *
     * <p>Connect is available once every card left has what it asks for; a
     * browser card, whose grant Connect runs, does not hold it back.
     */
    private void refreshSignIn() {
        if (authSteps == null) {
            return;
        }
        boolean ready = true;
        for (AuthStep step : authSteps) {
            boolean open = !step.signed;
            if (step.browser != null) {
                step.browser.setVisibility(open ? View.VISIBLE : View.GONE);
                step.ownClient.setVisibility(open ? View.VISIBLE : View.GONE);
            }
            if (step.same != null) {
                step.same.setVisibility(open ? View.VISIBLE : View.GONE);
            }
            boolean typed = open && !step.reuses();
            for (PillField field : new PillField[] {step.login, step.secret}) {
                if (field != null) {
                    field.view.setVisibility(typed ? View.VISIBLE : View.GONE);
                }
            }
            for (PillField field : new PillField[] {step.server, step.submit}) {
                if (field != null) {
                    field.view.setVisibility(open ? View.VISIBLE : View.GONE);
                }
            }
            if (step.signed) {
                status(step, host.getString(R.string.signin_signed), false);
            }
            if (open && !step.oauth()) {
                ready &= filled(step.server) && filled(step.submit);
                if (!step.reuses()) {
                    ready &= filled(step.secret) && (step.login == null || filled(step.login));
                }
            }
        }
        host.setFabEnabled(R.id.signin_continue, ready);
    }

    private static boolean filled(PillField field) {
        return field == null || !field.text().isEmpty();
    }

    /** Sets a card's outcome line, in the error colour or the accent. */
    private void status(AuthStep step, String text, boolean error) {
        step.status.setText(text);
        step.status.setTextColor(
                host.ui.resolveColor(error ? android.R.attr.colorError : android.R.attr.colorAccent));
        step.status.setVisibility(View.VISIBLE);
    }

    /** One server's check: a card's domain, signed in the way it will sync. */
    private static final class Probe {
        final AuthStep step;
        final PimDomain domain;
        final String baseUrl;
        final String login;
        final String secret;
        java.util.concurrent.Future<Exception> result;

        Probe(AuthStep step, PimDomain domain, String baseUrl, String login, String secret) {
            this.step = step;
            this.domain = domain;
            this.baseUrl = baseUrl;
            this.login = login;
            this.secret = secret;
        }
    }

    /**
     * A card's server that cannot be signed in to encrypted: said, not sent.
     *
     * <p>Plain {@code imap://} or {@code http://}, say.
     */
    private void insecure(AuthStep step) {
        status(step, host.getString(R.string.server_insecure), true);
        resetConfigContinue();
    }

    /**
     * Connect on the sign-in page: tries every card left, side by side.
     *
     * <p>Each reports on its card; once all have, the browser cards left run,
     * then the commit.
     */
    private void connectAll() {
        host.hideKeyboard();

        DomainSetup mail = setups.get(PimDomain.MAIL);
        List<Probe> probes = new ArrayList<>();
        for (AuthStep step : authSteps) {
            if (step.submit != null) {
                mail.submitSelected.url = Endpoints.submitUrl(step.submit.text());
                if (mail.submitSelected.url == null) {
                    insecure(step);
                    return;
                }
            }
            if (step.signed || step.oauth()) {
                continue;
            }
            AuthStep source = step.reuses() ? firstPassword : step;
            String login = step.password() ? source.login.text() : "";
            String secret = source.secret.input.getText().toString();
            for (PimDomain domain : step.domains) {
                String baseUrl =
                        step.option.isManual()
                                ? Endpoints.manualUrl(domain, step.server.text())
                                : setups.get(domain).selected.baseUrl;
                if (baseUrl == null) {
                    insecure(step);
                    return;
                }
                probes.add(new Probe(step, domain, baseUrl, login, secret));
            }
        }
        if (probes.isEmpty()) {
            nextGrant();
            return;
        }

        verifying = true;
        busy();
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(probes.size() + 1);
        for (Probe probe : probes) {
            // NOTE: a token signs in to IMAP with SASL XOAUTH2, which names
            // its user in the URL, as the stored account will.
            String url =
                    probe.domain == PimDomain.MAIL && probe.login.isEmpty()
                            ? Endpoints.asUser(probe.baseUrl, pendingEmail)
                            : probe.baseUrl;
            Account account = new Account(url, probe.login, probe.secret);
            probe.result = pool.submit(() -> probe(probe.domain, account));
        }
        pool.execute(
                () -> {
                    Map<Probe, Exception> failures = new java.util.HashMap<>();
                    for (Probe probe : probes) {
                        try {
                            Exception failure = probe.result.get();
                            if (failure != null) {
                                failures.put(probe, failure);
                            }
                        } catch (Exception error) {
                            failures.put(probe, error);
                        }
                    }
                    // NOTE: not postAlive: the step it leads to may persist the
                    // account (finishOnboarding), which a destroyed activity
                    // must not drop.
                    host.main.post(() -> signedIn(probes, failures));
                    pool.shutdown();
                });
    }

    /** The cards' outcome, back on the main thread. */
    private void signedIn(List<Probe> probes, Map<Probe, Exception> failures) {
        if (!verifying) {
            return;
        }
        verifying = false;

        Map<AuthStep, Exception> refused = new java.util.HashMap<>();
        Set<PimDomain> unserved = java.util.EnumSet.noneOf(PimDomain.class);
        for (Probe probe : probes) {
            Exception failure = failures.get(probe);
            if (failure instanceof NotServed) {
                unserved.add(probe.domain);
            } else if (failure != null) {
                refused.putIfAbsent(probe.step, failure);
            }
        }
        // NOTE: a card whose every domain the server does not serve signed in
        // to nothing, which is its refusal; one serving some connects those.
        for (Probe probe : probes) {
            if (unserved.containsAll(probe.step.domains)) {
                refused.putIfAbsent(probe.step, failures.get(probe));
            }
        }
        for (Probe probe : probes) {
            if (refused.containsKey(probe.step)) {
                continue;
            }
            probe.step.signed = true;
            DomainSetup setup = setups.get(probe.domain);
            if (unserved.contains(probe.domain)) {
                setup.enabled = false;
                continue;
            }
            setup.baseUrl = probe.baseUrl;
            setup.credential = AccountCredential.password(probe.login, probe.secret);
        }
        for (Map.Entry<AuthStep, Exception> entry : refused.entrySet()) {
            Exception failure = entry.getValue();
            String text;
            if (failure instanceof NotServed) {
                text = failure.getMessage();
            } else if (!wrongPassword(failure)) {
                text = host.getString(R.string.connect_failed);
            } else if (entry.getKey().password()) {
                text = host.getString(R.string.result_refused);
            } else {
                text = host.getString(R.string.token_refused);
            }
            status(entry.getKey(), text, true);
        }

        if (!unserved.isEmpty()) {
            host.toast(notServed(unserved));
        }
        if (!refused.isEmpty()) {
            resetConfigContinue();
            return;
        }
        refreshSignIn();
        nextGrant();
    }

    /**
     * Runs the next browser card not signed in, or commits once none is left.
     *
     * <p>A grant comes back through {@link #connect}, which calls this again.
     */
    private void nextGrant() {
        connecting = true;
        for (AuthStep step : authSteps) {
            if (!step.signed && step.oauth()) {
                startGrant(step, false);
                return;
            }
        }
        connecting = false;
        commitConnections();
    }

    /**
     * Runs one browser grant for every domain of the card, scopes merged.
     *
     * <p>Through the app's registration at Google and Microsoft, dynamic
     * registration at an issuer, or the user's own client.
     */
    private void startGrant(AuthStep step, boolean own) {
        SetupOption option = step.option;
        pendingStep = step;
        oauthGroup.clear();

        java.util.Set<String> scopes = new java.util.LinkedHashSet<>();
        List<String> endpoints = new ArrayList<>();
        for (PimDomain domain : step.domains) {
            DomainSetup setup = setups.get(domain);
            oauthGroup.put(domain, setup.selected.baseUrl);
            endpoints.add(setup.selected.baseUrl);
            SetupPlan.addScopes(scopes, setup.selected.method.scope());
            // NOTE: submission signs in with mail's credential, so the grant
            // that reads mail has to cover sending it too.
            if (domain == PimDomain.MAIL && sends(setup) && setup.submitSelected != null) {
                SetupPlan.addScopes(scopes, setup.submitSelected.scope);
                endpoints.add(setup.submitSelected.url);
            }
        }

        String scope = scopes.isEmpty() ? null : String.join(" ", scopes);
        String heading =
                domainNames(step) + " · " + (option.detail != null ? option.detail : SetupPlan.hostOf(option.baseUrl));
        // NOTE: Google and Microsoft register no client dynamically, so their
        // grants run with the app's own registration, and only for their own
        // hosts: any other falls to a generic grant (Oauth.shipped).
        Oauth.Shipped shipped =
                own ? null : Oauth.shipped(option.method.authorizationEndpoint(), endpoints);
        if (shipped == Oauth.Shipped.GOOGLE) {
            // NOTE: a domain added onto an account Google already granted
            // asks for the union, so the one new grant replaces the old one
            // on every domain rather than living beside it.
            AccountEntry existing = existingAccount(pendingEmail);
            if (existing != null) {
                scope = Oauth.union(existing.grantedScope(Oauth.GOOGLE_CLIENT_ID), scope);
            }
            oauth.startGoogleOauth(
                    pendingEmail, Oauth.fixedScope(shipped, scope), option.baseUrl);
            return;
        }
        if (shipped == Oauth.Shipped.MICROSOFT) {
            oauth.startMicrosoftOauth(
                    pendingEmail, Oauth.fixedScope(shipped, scope), option.baseUrl);
            return;
        }
        if (option.method.type() == AuthMethod.Type.OAUTH_ISSUER) {
            // NOTE: the resource, never the base URL. A JMAP account's base is
            // the internal jmap:// marker and a mailbox's is imaps://, and an
            // authorization server asked to issue a token for either answers
            // invalid_target: neither is a URI it protects.
            oauth.startIssuerOauth(
                    new OauthFlow.Target(pendingEmail, option.baseUrl, option.resource),
                    option.method.issuer(),
                    grantedDomains(),
                    new OauthFlow.Prompt(heading, own));
            return;
        }
        oauth.promptOauthClient(
                new OauthFlow.Target(pendingEmail, option.baseUrl, option.resource),
                new OauthFlow.AuthServer(
                        option.method.authorizationEndpoint(), option.method.tokenEndpoint()),
                scope,
                new OauthFlow.Prompt(heading, own));
    }

    /** The account already stored for this address, or null. */
    private AccountEntry existingAccount(String email) {
        for (AccountEntry entry : accounts) {
            if (entry.email.equals(email)) {
                return entry;
            }
        }
        return null;
    }

    /** The domains the in-flight grant covers, for the scopes to ask for. */
    private String grantedDomains() {
        List<String> ids = new ArrayList<>();
        for (PimDomain domain : oauthGroup.keySet()) {
            ids.add(domain.id);
        }
        return String.join(" ", ids);
    }

    /** The entered email as a login prefill; empty for a bare domain or URL. */
    private String emailLogin() {
        return pendingEmail != null && pendingEmail.contains("@") ? pendingEmail : "";
    }

    /**
     * Lands a redeemed browser grant on every domain of its card.
     *
     * <p>Then the next grant, or back to the sign-in page; nothing persists
     * before all is in. Refresh material and granted scopes are kept, for later
     * refreshes and union grants.
     */
    public void connect(String email, OauthTokens tokens, OauthGrant grant) {
        connectedEmail = email;

        // NOTE: a browser grant covers every domain that chose the same
        // authorization server, so one credential lands on all of them, each
        // against its own endpoint. That sharing is the point: the grant is
        // one consent, and a provider that rotates its refresh token retires
        // every copy but the one it just issued.
        AccountCredential credential =
                tokens.refreshToken() == null
                        ? AccountCredential.password("", tokens.accessToken())
                        : AccountCredential.oauth(tokens, grant);

        List<PimDomain> overJmap = new ArrayList<>();
        Account jmap = null;
        for (java.util.Map.Entry<PimDomain, String> granted : oauthGroup.entrySet()) {
            DomainSetup setup = setups.get(granted.getKey());
            if (setup == null) {
                continue;
            }
            setup.baseUrl = granted.getValue();
            setup.credential = credential;
            Account server = new Account(setup.baseUrl, "", tokens.accessToken());
            if (AccountClient.isJmap(server)) {
                overJmap.add(setup.domain);
                jmap = server;
            }
        }
        oauthGroup.clear();
        if (pendingStep != null) {
            pendingStep.signed = true;
            pendingStep = null;
        }

        if (signInPage && shell.authStep() == STEP_OAUTH) {
            shell.showAuth(STEP_SIGNIN);
        }
        if (jmap == null) {
            granted();
            return;
        }

        // NOTE: a grant probes nothing, so the JMAP session is read here,
        // for the domains it serves no account for to be left out alone.
        Account session = jmap;
        host.io.execute(
                () -> {
                    Set<PimDomain> served = null;
                    try (Transport transport = new Transport()) {
                        served =
                                PimDomain.servedByJmap(
                                        parts.clients().contact().jmapCapabilities(transport, session));
                    } catch (Exception error) {
                        // NOTE: unread rather than refused: the grant stands,
                        // and the first sync says what the session cannot do.
                        Log.w("pimalaya", "jmap session unread after the grant", error);
                    }
                    Set<PimDomain> read = served;
                    host.postAlive(
                            () -> {
                                List<PimDomain> left = new ArrayList<>();
                                for (PimDomain domain : overJmap) {
                                    if (read != null && !read.contains(domain)) {
                                        DomainSetup setup = setups.get(domain);
                                        setup.credential = null;
                                        setup.enabled = false;
                                        left.add(domain);
                                    }
                                }
                                if (!left.isEmpty()) {
                                    host.toast(notServed(left));
                                }
                                granted();
                            });
                });
    }

    /** Carries on once a grant landed: the next one, or the plan's continue. */
    private void granted() {
        if (connecting) {
            nextGrant();
        } else {
            resetConfigContinue();
        }
    }

    /**
     * Continue from the setup screen: what signed in becomes one account.
     *
     * <p>The contacts domain adds its address book selection.
     */
    private void confirmSetup() {
        host.hideKeyboard();
        connectedEmail = pendingEmail;
        for (DomainSetup setup : setups.values()) {
            setup.credential = null;
        }
        List<DomainSetup> shared = sharedPassword();
        if (shared.isEmpty()) {
            runSignIns();
        } else {
            verifyPassword(shared);
        }
    }

    /**
     * Tries the standard setup's one password on every domain, side by side.
     *
     * <p>All accepting moves on to what else the plan owes (a browser grant);
     * all refusing says so on the field and saves nothing; a mix goes to the
     * result step.
     */
    private void verifyPassword(List<DomainSetup> shared) {
        String password =
                ((EditText) host.findViewById(R.id.domain_password)).getText().toString();
        verifying = true;
        showPasswordError(false);
        host.setAuthLoading(R.id.domain_connect, R.id.domain_progress, true);

        // NOTE: a pool of its own, one thread per domain and one waiting on
        // them: the shared io executor runs one task at a time.
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(shared.size() + 1);
        Map<PimDomain, java.util.concurrent.Future<Exception>> probes =
                new java.util.EnumMap<>(PimDomain.class);
        for (DomainSetup setup : shared) {
            Account account = new Account(setup.selected.baseUrl, setup.selected.login, password);
            probes.put(setup.domain, pool.submit(() -> probe(setup.domain, account)));
        }

        pool.execute(
                () -> {
                    Map<PimDomain, Exception> refused = new java.util.EnumMap<>(PimDomain.class);
                    for (Map.Entry<PimDomain, java.util.concurrent.Future<Exception>> probe :
                            probes.entrySet()) {
                        Exception failure;
                        try {
                            failure = probe.getValue().get();
                        } catch (Exception error) {
                            failure = error;
                        }
                        if (failure != null) {
                            refused.put(probe.getKey(), failure);
                        }
                    }
                    // NOTE: not postAlive: the step it leads to may persist the
                    // account (finishOnboarding), which a destroyed activity
                    // must not drop.
                    host.main.post(() -> verified(shared, password, refused));
                    pool.shutdown();
                });
    }

    /** The one password's outcome, back on the main thread. */
    private void verified(
            List<DomainSetup> shared, String password, Map<PimDomain, Exception> refused) {
        if (!verifying) {
            return;
        }
        verifying = false;

        for (DomainSetup setup : shared) {
            if (!refused.containsKey(setup.domain)) {
                setup.baseUrl = setup.selected.baseUrl;
                setup.credential = AccountCredential.password(setup.selected.login, password);
            }
        }

        if (refused.isEmpty()) {
            runSignIns();
            return;
        }

        if (refused.size() == shared.size()) {
            resetSetupContinue();
            for (Exception failure : refused.values()) {
                if (!wrongPassword(failure)) {
                    host.showError(failure, R.string.connect_failed);
                    return;
                }
            }
            showPasswordError(true);
            return;
        }

        showResult(shared, refused);
    }

    /**
     * Signs in to one domain as its first sync will; answers the error or null.
     *
     * <p>The mail session opens and authenticates, books and calendars list.
     * Over JMAP the session is read first: a domain it serves no account for is
     * {@link NotServed}, whatever the other domains answer.
     */
    private Exception probe(PimDomain domain, Account account) {
        try {
            if (AccountClient.isJmap(account)) {
                Set<String> capabilities;
                try (Transport transport = new Transport()) {
                    capabilities = parts.clients().contact().jmapCapabilities(transport, account);
                }
                if (!PimDomain.servedByJmap(capabilities).contains(domain)) {
                    return new NotServed(notServed(List.of(domain)));
                }
            }
            switch (domain) {
                case MAIL:
                    try (org.pimalaya.client.MailSession session =
                            MailClient.openMail(account)) {
                        return null;
                    }
                case CONTACTS:
                    try (Transport transport = new Transport()) {
                        parts.clients().contact().listAddressbooks(transport, account);
                    }
                    return null;
                default:
                    try (Transport transport = new Transport()) {
                        parts.clients().calendar().listCalendars(transport, account);
                    }
                    return null;
            }
        } catch (Exception error) {
            Log.w("pimalaya", "password check failed for " + domain.id, error);
            return error;
        }
    }

    /** A domain the JMAP session serves no account for, in the user's words. */
    private static final class NotServed extends Exception {
        NotServed(String message) {
            super(message);
        }
    }

    /** The line saying which domains this server does not offer. */
    private String notServed(java.util.Collection<PimDomain> domains) {
        List<String> names = new ArrayList<>();
        for (PimDomain domain : domains) {
            names.add(host.getString(domain.label).toLowerCase(java.util.Locale.getDefault()));
        }
        return host.getString(R.string.domains_not_served, String.join(", ", names));
    }

    /** Whether a failure is the server refusing the credential. */
    private static boolean wrongPassword(Exception failure) {
        if (!(failure instanceof org.pimalaya.client.PimalayaException)) {
            return false;
        }
        Integer status = ((org.pimalaya.client.PimalayaException) failure).status;
        return status != null && (status == 401 || status == 403);
    }

    /**
     * The result step: the one password reached some domains, not others.
     *
     * <p>A row per domain tried, connected or not, and the two ways on.
     */
    private void showResult(List<DomainSetup> shared, Map<PimDomain, Exception> refused) {
        ((TextView) host.findViewById(R.id.result_email)).setText(pendingEmail);
        ((TextView) host.findViewById(R.id.result_title)).setText(R.string.result_title);

        List<String> names = new ArrayList<>();
        for (PimDomain domain : refused.keySet()) {
            names.add(host.getString(domain.label).toLowerCase(java.util.Locale.getDefault()));
        }
        String named = String.join(", ", names);
        ((TextView) host.findViewById(R.id.result_message))
                .setText(host.getString(R.string.result_message, named));
        host.findViewById(R.id.result_note).setVisibility(View.VISIBLE);

        LinearLayout card = resultCard();
        for (DomainSetup setup : shared) {
            Exception failure = refused.get(setup.domain);
            String status;
            if (failure == null) {
                status = host.getString(R.string.result_connected);
            } else if (failure instanceof NotServed) {
                status = host.getString(R.string.result_not_served);
            } else if (wrongPassword(failure)) {
                status = host.getString(R.string.result_refused);
            } else {
                status = host.getString(R.string.connect_failed);
            }
            addResult(card, setup.domain, status, failure != null);
        }

        Button onward = host.findViewById(R.id.result_continue);
        onward.setText(host.getString(R.string.result_continue, named));
        onward.setOnClickListener(
                view -> {
                    for (PimDomain domain : refused.keySet()) {
                        setups.get(domain).enabled = false;
                    }
                    // NOTE: back on the domain step, the refused domains
                    // unticked, while the rest of the plan runs.
                    shell.showAuth(STEP_DOMAIN);
                    LinearLayout domains = host.findViewById(R.id.domain_container);
                    domains.removeAllViews();
                    domains.addView(domainCard(AccountEntry.find(accounts, pendingEmail)));
                    host.setAuthLoading(R.id.domain_connect, R.id.domain_progress, true);
                    runSignIns();
                });

        Button advancedLink = host.findViewById(R.id.result_advanced);
        advancedLink.setVisibility(View.VISIBLE);
        advancedLink.setText(host.getString(R.string.result_advanced, named));
        advancedLink.setOnClickListener(
                view -> {
                    for (DomainSetup setup : setups.values()) {
                        setup.credential = null;
                    }
                    switchToAdvanced();
                });

        shell.showAuth(STEP_RESULT);
    }

    /**
     * The result once signed in, when another app has the address.
     *
     * <p>Another app fills the phone's Contacts or Calendar: a row per domain,
     * those staying off the phone said, Continue unmirrored. Also the advanced
     * calendars', which have no page.
     */
    private void showElsewhere(String email) {
        ((TextView) host.findViewById(R.id.result_email)).setText(pendingEmail);
        ((TextView) host.findViewById(R.id.result_title)).setText(R.string.result_elsewhere_title);
        ((TextView) host.findViewById(R.id.result_message))
                .setText(
                        calendarsElsewhere
                                ? R.string.phone_apps_elsewhere_note
                                : R.string.phone_elsewhere_note);
        host.findViewById(R.id.result_note).setVisibility(View.GONE);

        LinearLayout card = resultCard();
        for (DomainSetup setup : setups.values()) {
            if (setup.credential == null) {
                continue;
            }
            int status = R.string.result_connected;
            if (setup.domain == PimDomain.CONTACTS && elsewhere) {
                status = R.string.phone_elsewhere;
            } else if (setup.domain == PimDomain.CALENDAR && calendarsElsewhere) {
                status = R.string.phone_calendar_elsewhere;
            }
            addResult(card, setup.domain, host.getString(status), false);
        }

        Button onward = host.findViewById(R.id.result_continue);
        onward.setText(R.string.email_submit);
        onward.setOnClickListener(view -> connected(email));
        host.findViewById(R.id.result_advanced).setVisibility(View.GONE);

        shell.showAuth(STEP_RESULT);
    }

    /**
     * Onward once signed in and what the phone already shows is said.
     *
     * <p>The books page in the advanced setup, every book in the standard one,
     * straight to the first sync without contacts.
     */
    private void connected(String email) {
        if (pendingBooks == null) {
            finishOnboarding();
        } else if (!simpleSetup()) {
            openBooksSelection(email, pendingBooks);
        } else {
            confirmAllBooks(email);
        }
    }

    /** The result step's card, emptied of an earlier result. */
    private LinearLayout resultCard() {
        LinearLayout container = host.findViewById(R.id.result_container);
        container.removeAllViews();
        LinearLayout card = CardRows.card(host);
        container.addView(card);
        return card;
    }

    /** One domain's row on the result step: connected with a check, or not. */
    private void addResult(LinearLayout card, PimDomain domain, String status, boolean failed) {
        android.widget.ImageView mark = new android.widget.ImageView(host);
        mark.setImageResource(failed ? R.drawable.ic_error : R.drawable.ic_check);
        mark.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        host.ui.resolveColor(
                                failed ? android.R.attr.colorError : android.R.attr.colorAccent)));
        mark.setPadding(host.ui.dp(12), 0, host.ui.dp(12), 0);
        if (card.getChildCount() > 0) {
            card.addView(CardRows.rowDivider(host));
        }
        card.addView(domainRow(domain, status, failed, mark));
    }

    /**
     * Turns what the sequence signed in to into one account.
     *
     * <p>Adds the contacts domain's address book selection when it is among
     * them.
     */
    private void commitConnections() {
        authSteps = null;

        List<AccountCommit.Connection> connections = new ArrayList<>();
        for (DomainSetup setup : setups.values()) {
            if (setup.credential == null) {
                continue;
            }
            String submitUrl =
                    setup.domain == PimDomain.MAIL && sends(setup) && setup.submitSelected != null
                            ? setup.submitSelected.url
                            : null;
            connections.add(
                    new AccountCommit.Connection(
                            setup.domain, setup.baseUrl, submitUrl, setup.credential));
        }
        connectedAccount = AccountCommit.assemble(connectedEmail, connections);
        if (connectedAccount == null) {
            return;
        }

        Account contacts = connectedAccount.server(PimDomain.CONTACTS);
        boolean calendars =
                switches.mirrors.contains(PhoneMirror.CALENDAR)
                        && connectedAccount.server(PimDomain.CALENDAR) != null;
        pendingBooks = null;
        if (contacts == null && !calendars) {
            finishOnboarding();
            return;
        }

        busy();
        String email = connectedEmail;
        boolean phone = switches.mirrors.contains(PhoneMirror.CONTACTS);
        host.io.execute(
                () -> {
                    try {
                        List<Addressbook> fetched = null;
                        if (contacts != null) {
                            try (Transport transport = new Transport()) {
                                fetched =
                                        parts.clients().contact().listAddressbooks(transport, contacts);
                            }
                        }
                        // NOTE: one provider query each, the permissions
                        // granted as the switches turned on; what they find
                        // is said rather than guessed at beforehand.
                        boolean found =
                                phone && contacts != null && Accounts.elsewhere(host, email);
                        boolean calendarsFound =
                                calendars && Accounts.calendarsElsewhere(host, email);
                        List<Addressbook> books = fetched;
                        // NOTE: not postAlive: the step it leads to may persist
                        // the account (finishOnboarding), which a destroyed
                        // activity must not drop.
                        host.main.post(
                                () -> {
                                    resetConfigContinue();
                                    pendingBooks = books;
                                    elsewhere = found;
                                    calendarsElsewhere = calendarsFound;
                                    // NOTE: the advanced setup says it of
                                    // the contacts on its books page.
                                    if (calendarsFound || (found && simpleSetup())) {
                                        showElsewhere(email);
                                    } else {
                                        connected(email);
                                    }
                                });
                    } catch (Exception error) {
                        Log.w("pimalaya", "connect failed", error);
                        host.postAlive(
                                () -> {
                                    resetConfigContinue();
                                    host.showError(error, R.string.connect_failed);
                                });
                    }
                });
    }

    /**
     * The addressbooks step: one card, a ticked checkbox row per book.
     *
     * <p>The phone switch of the page before applies to every book ticked;
     * settings change it per book later. A note says when another app fills the
     * phone's Contacts app, the books staying off it.
     */
    private void openBooksSelection(String email, List<Addressbook> books) {
        connectedEmail = email;
        bookChoices = new ArrayList<>();

        ((TextView) host.findViewById(R.id.books_line))
                .setText(host.getString(R.string.books_signed, email));
        ((TextView) host.findViewById(R.id.books_message)).setText(R.string.books_description);
        host.findViewById(R.id.books_note).setVisibility(elsewhere ? View.VISIBLE : View.GONE);

        LinearLayout container = host.findViewById(R.id.books_container);
        container.removeAllViews();

        if (books.isEmpty()) {
            TextView empty = new TextView(host);
            empty.setText(R.string.books_none);
            empty.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
            container.addView(empty);
        } else {
            LinearLayout card = CardRows.card(host);
            container.addView(card);

            for (Addressbook book : books) {
                if (card.getChildCount() > 0) {
                    View divider = CardRows.rowDivider(host);
                    ((LinearLayout.LayoutParams) divider.getLayoutParams())
                            .setMarginStart(host.ui.dp(20));
                    card.addView(divider);
                }
                CheckBox subscribe = new CheckBox(host);
                subscribe.setChecked(true);
                subscribe.setClickable(false);
                subscribe.setFocusable(false);

                LinearLayout row = new LinearLayout(host);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(host.ui.dp(60));
                row.setPadding(host.ui.dp(20), 0, host.ui.dp(8), 0);
                TypedValue ripple = new TypedValue();
                host.getTheme()
                        .resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
                row.setForeground(host.getDrawable(ripple.resourceId));

                TextView name = new TextView(host);
                name.setText(book.name());
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                name.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
                row.addView(
                        name,
                        new LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(subscribe);
                row.setOnClickListener(
                        view -> {
                            subscribe.toggle();
                            updateBooksContinue();
                        });
                card.addView(row);

                bookChoices.add(new BookChoice(book.url(), subscribe));
            }
        }

        shell.showAuth(STEP_BOOKS);
        resetConfigContinue();
    }

    /**
     * Finish is enabled while an addressbook is on, or the account has none.
     */
    private void updateBooksContinue() {
        host.setFabEnabled(R.id.books_continue, bookChoices.isEmpty() || booksAnyChecked());
    }

    private boolean booksAnyChecked() {
        for (BookChoice choice : bookChoices) {
            if (choice.subscribe.isChecked()) {
                return true;
            }
        }
        return false;
    }

    /** The books step's Continue: commits the checked selection. */
    private void confirmBooks() {
        java.util.Set<String> subscribed = new java.util.HashSet<>();
        for (BookChoice choice : bookChoices) {
            if (choice.subscribe.isChecked()) {
                subscribed.add(choice.url);
            }
        }
        commitBooks(subscribed);
    }

    /** The standard setup's commit: every addressbook, then the first sync. */
    private void confirmAllBooks(String email) {
        connectedEmail = email;
        java.util.Set<String> subscribed = new java.util.HashSet<>();
        for (Addressbook book : pendingBooks) {
            subscribed.add(book.url());
        }
        commitBooks(subscribed);
    }

    /**
     * The flow's real commit, for the books step and the standard setup.
     *
     * <p>Persists the account and its books, subscribes the given ones (on the
     * phone when switched and no other app has the address), creates their
     * Android accounts, then runs the first sync, which projects them.
     */
    private void commitBooks(java.util.Set<String> subscribed) {
        boolean phone = switches.mirrors.contains(PhoneMirror.CONTACTS) && !elsewhere;
        AccountCommit.books(
                parts.cards(),
                new PimdirCollections(parts.pimdir(), host),
                new AccountCommit.BookChoice(connectedEmail, pendingBooks, subscribed, phone));
        sync.reconcilePhone();

        finishOnboarding();
    }

    /**
     * Persists what the run connected as one account, then first-syncs it.
     *
     * <p>The sync fills every domain before the app shows any. One save, not
     * one per domain: the account is the unit, and two entries would race to
     * replace each other.
     */
    private void finishOnboarding() {
        if (connectedAccount == null) {
            return;
        }

        AccountEntry connected = connectedAccount;
        connectedAccount = null;

        // NOTE: the calendar switch is kept for the account, its calendars
        // being listed by the first sync, which shows them on the phone.
        if (connected.server(PimDomain.CALENDAR) != null) {
            PhoneCalendars.setAccount(
                    host,
                    connected.email,
                    switches.mirrors.contains(PhoneMirror.CALENDAR) && !calendarsElsewhere);
        }

        AccountEntry stored = AccountCommit.persist(parts.accounts(), connected);
        accounts.removeIf(entry -> entry.email.equals(stored.email));
        accounts.add(stored);

        // NOTE: written only when on, so setting an address up again leaves
        // its choice alone; after the save, as it may schedule the job.
        if (switches.notifies && connected.server(PimDomain.MAIL) != null) {
            BackgroundCheck.setNotifies(host, stored.email, true);
        }

        busy();
        sync.syncConnected(stored);
    }

    /**
     * Everywhere this address submits mail, from that discovery, best first.
     *
     * <p>Implicit TLS ({@code smtps://}), then STARTTLS ({@code smtp://}),
     * upgraded before authenticating. A plain endpoint is logged, not offered:
     * nothing is submitted in the clear.
     */
    private List<SubmitOption> submissionOptions() {
        List<SubmitOption> implicit = new ArrayList<>();
        List<SubmitOption> upgraded = new ArrayList<>();

        for (ServiceConfig config : searchedConfigs) {
            String url = Endpoints.submissionUrl(config);
            if (url == null) {
                continue;
            }
            boolean tls = url.startsWith("smtps:");
            String authority = config.host() + ":" + config.port();

            List<AuthMethod> methods = new ArrayList<>(config.auth());
            java.util.Collections.sort(
                    methods,
                    (left, right) -> Integer.compare(SetupPlan.authRank(left.type()), SetupPlan.authRank(right.type())));
            if (methods.isEmpty()) {
                methods.add(null);
            }
            for (AuthMethod method : methods) {
                (tls ? implicit : upgraded)
                        .add(
                                new SubmitOption(
                                        host.getString(R.string.config_smtp),
                                        authority,
                                        url,
                                        false,
                                        method == null ? null : method.scope(),
                                        null,
                                        method == null ? null : method.type()));
            }
        }
        implicit.addAll(upgraded);

        // NOTE: the reading rows' rule: two rows reading the same are one
        // choice offered twice, as when two mechanisms found one server or
        // it takes OAuth through two grants. The first kept is the best
        // ranked.
        // A row naming no method is the same server again when another
        // mechanism found it with one, as IMAP never offers such a row at all.
        Set<String> signed = new HashSet<>();
        for (SubmitOption option : implicit) {
            if (option.auth != null) {
                signed.add(option.url);
            }
        }
        Map<String, SubmitOption> distinct = new LinkedHashMap<>();
        for (SubmitOption option : implicit) {
            if (option.auth == null && signed.contains(option.url)) {
                continue;
            }
            distinct.putIfAbsent(submitLabel(option), option);
        }
        return new ArrayList<>(distinct.values());
    }

    /** One addressbook's onboarding subscribe checkbox. */
    private static final class BookChoice {
        final String url;
        final CheckBox subscribe;

        BookChoice(String url, CheckBox subscribe) {
            this.url = url;
            this.subscribe = subscribe;
        }
    }
}
