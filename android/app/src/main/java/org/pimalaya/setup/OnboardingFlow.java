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
import android.widget.Switch;
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

    /** The cards asking for a password, in order, which later ones reuse. */
    private final List<AuthStep> passwordCards = new ArrayList<>();

    /** The card whose browser grant is in flight. */
    private AuthStep pendingStep;

    /** The password or token cards being signed in, their buttons turning. */
    private final Set<AuthStep> checking = new java.util.HashSet<>();

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

    /**
     * Whether the books page shows the mailboxes, mail's step before the
     * addressbooks.
     */
    private boolean pickingMailboxes;

    /** The mailboxes ticked on mail's step, by name; null until chosen. */
    private Set<String> chosenMailboxes;

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
        host.findViewById(R.id.books_continue)
                .setOnClickListener(
                        view -> {
                            if (pickingMailboxes) {
                                confirmMailboxes();
                            } else {
                                confirmBooks();
                            }
                        });
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
        chosenMailboxes = null;
        pickingMailboxes = false;
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

        /**
         * What mail's sending card signed in with; null until it did, and
         * for every other domain.
         */
        AccountCredential submitCredential;

        /** Whether this domain is on and has something to connect to. */
        boolean ready() {
            return enabled && selected != null;
        }
    }

    /**
     * One sign-in the selection owes: a sign-in card, or a browser hop.
     *
     * <p>Planned, not run per domain: domains behind one authorization server
     * are one hop, a password or a token one server, mail's sending a card of
     * its own when it signs in with one.
     */
    private static final class AuthStep {
        final List<PimDomain> domains = new ArrayList<>();
        final SetupOption option;

        /**
         * Whether this card signs mail's sending in, at the submission server,
         * rather than its domains at theirs.
         */
        final boolean sending;

        /** The card's fields, null for the ones its method does not ask. */
        PillField login;

        PillField secret;

        /**
         * The custom settings form, null on a card of discovered settings:
         * the server (a host, or a URL for contacts and calendars), its port,
         * STARTTLS for sending, and whether the secret is an API token.
         */
        PillField server;

        PillField port;
        Switch starttls;
        Switch token;

        /** The endpoint the custom form starts from, or null for empty. */
        String prefill;

        /**
         * "Same login and password as", from the second password card on,
         * and the card whose login and password it reuses while on.
         */
        Switch same;

        TextView sameLabel;
        AuthStep linked;

        /** The card's outcome line: signed in, or what went wrong. */
        TextView status;

        /** The browser card's button and its own-client link. */
        CardAction browser;

        View ownClient;

        /** A password or token card's own sign-in button. */
        CardAction verify;

        /** The header's glyph, a check once signed in. */
        android.widget.ImageView glyph;

        /** The link signing the card in again, in place of its status. */
        View again;

        /** The card itself, on the sign-in page. */
        View card;

        /** The header's names of the domains it covers. */
        TextView names;

        /** Whether this card's domains hold their credential. */
        boolean signed;

        AuthStep(SetupOption option) {
            this(option, false);
        }

        AuthStep(SetupOption option, boolean sending) {
            this.option = option;
            this.sending = sending;
        }

        /** Whether this card signs in through a browser. */
        boolean oauth() {
            return option.isOauth();
        }

        /** Whether this card asks for a login and a password. */
        boolean password() {
            if (token != null) {
                return !token.isChecked();
            }
            return option.method == null || option.method.type() == AuthMethod.Type.PASSWORD;
        }

        /** Whether this card holds the custom settings form. */
        boolean custom() {
            return server != null;
        }

        /** Whether the "same as" switch is on, the linked card's fields reused. */
        boolean reuses() {
            return same != null && same.isChecked() && linked != null;
        }

        /** The card whose login and password this one signs in with. */
        AuthStep source() {
            AuthStep step = this;
            // NOTE: a card links only to one before it, so the walk ends.
            while (step.reuses()) {
                step = step.linked;
            }
            return step;
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
            // NOTE: a tick and nothing else: how each domain signs in is the
            // sign-in page's, its options its own page's.
            LinearLayout row = domainRow(setup.domain, status, false, tick);
            row.setOnClickListener(
                    view -> {
                        if (verifying) {
                            return;
                        }
                        setup.enabled = !setup.enabled;
                        tick.setChecked(setup.enabled);
                        resetSetupContinue();
                    });
            card.addView(row);
        }
        return card;
    }

    /**
     * How an option connects, in the words of someone who never set a server:
     * whose account, or how one signs in and at which server.
     */
    private String sentence(SetupOption option) {
        String provider = SetupPlan.provider(option, pendingEmail);
        if (provider != null) {
            return host.getString(R.string.setup_via_account, provider);
        }
        String server = SetupPlan.serverName(option);
        switch (option.method.type()) {
            case PASSWORD:
                return host.getString(R.string.setup_via_password, server);
            case BEARER:
                return host.getString(R.string.setup_via_token, server);
            default:
                return host.getString(R.string.setup_via_browser, server);
        }
    }

    /**
     * Whether a calendar option holds tasks: CalDAV alone in this first
     * draft, as the Tasks tab writes VTODOs (cairn/changes/gtd).
     *
     * <p>Google, Microsoft and JMAP calendars are told by their base, a
     * marker scheme rather than https.
     */
    private static boolean takesTasks(SetupOption option) {
        String base = option == null || option.baseUrl == null ? "" : option.baseUrl;
        return base.startsWith("https://") || base.startsWith("http://");
    }

    /**
     * The calendar's option with the line saying its tasks are not
     * supported, the one place onboarding says it.
     */
    private View withTasksNote(View option) {
        LinearLayout both = new LinearLayout(host);
        both.setOrientation(LinearLayout.VERTICAL);
        both.addView(option);
        TextView note = new TextView(host);
        note.setText(R.string.onboarding_no_tasks);
        note.setTextSize(13);
        note.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
        note.setPadding(host.ui.dp(60), 0, host.ui.dp(16), host.ui.dp(12));
        both.addView(note);
        return both;
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

        // NOTE: no password here any more: every sign-in is the sign-in
        // page's, Continue leading to it.
        host.findViewById(R.id.domain_password_block).setVisibility(View.GONE);
        host.findViewById(R.id.domain_browser_note).setVisibility(View.GONE);
        ((Button) host.findViewById(R.id.domain_connect)).setText(R.string.email_submit);
        host.setFabEnabled(R.id.domain_connect, setupReady());
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
                // NOTE: a card's own grant turns on that card's button,
                // Continue staying as it was.
                if (pendingStep != null) {
                    refreshSignIn();
                } else {
                    host.setAuthLoading(R.id.signin_continue, R.id.signin_progress, true);
                }
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
            AuthStep sending = sendingStep(step);
            if (sending != null) {
                steps.add(sending);
            }
        }
        return steps;
    }

    /**
     * Mail's sending card, right after the card reading mail, or null.
     *
     * <p>Only beside mail read with a password or a token: SMTP is a second
     * server, which may take another login. A browser grant covers sending
     * (its scopes merged, {@link #startGrant}), and JMAP, Gmail and Graph
     * submit through the session they read with.
     */
    private AuthStep sendingStep(AuthStep reading) {
        DomainSetup mail = setups.get(PimDomain.MAIL);
        if (!reading.domains.contains(PimDomain.MAIL)
                || reading.oauth()
                || !sends(mail)
                || mail.submitSelected == null
                || mail.submitSelected.none()
                || mail.submitSelected.through != null
                || mail.submitCredential != null) {
            return null;
        }
        AuthStep sending = new AuthStep(reading.option, true);
        sending.domains.add(PimDomain.MAIL);
        return sending;
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
            setup.submitCredential = null;
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
        pendingStep = null;
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
        pendingStep = null;
        shell.showAuth(STEP_SIGNIN);
        resetConfigContinue();
    }

    /**
     * Plans the sign-ins the domains still owe and shows them on the sign-in
     * page, browser grants included, one card each.
     */
    private void runSignIns() {
        authSteps = planAuthSteps();
        showSignIn();
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
        passwordCards.clear();
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
                        glyphOf(step),
                        domainNames(step),
                        signInDetail(step),
                        false,
                        tuneButton(step),
                        true);
        header.setPadding(0, 0, 0, host.ui.dp(4));
        header.setMinimumHeight(0);
        header.setForeground(null);
        step.glyph = (android.widget.ImageView) header.getChildAt(0);
        step.names = (TextView) ((LinearLayout) header.getChildAt(1)).getChildAt(0);
        card.addView(header);

        if (step.oauth()) {
            CardAction browser = cardAction(R.string.signin_browser);
            card.addView(browser.view);
            browser.button.setOnClickListener(
                    view -> {
                        connecting = false;
                        startGrant(step, false);
                    });
            step.browser = browser;

            Button own = new Button(host, null, 0, R.style.TextLink);
            own.setText(R.string.signin_own_client);
            own.setTextSize(14);
            // NOTE: room above and below, so it does not read as part of the
            // browser button.
            LinearLayout.LayoutParams ownParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, host.ui.dp(44));
            ownParams.topMargin = host.ui.dp(10);
            ownParams.bottomMargin = host.ui.dp(4);
            card.addView(own, ownParams);
            own.setOnClickListener(
                    view -> {
                        connecting = false;
                        startGrant(step, true);
                    });
            step.ownClient = own;
        } else if (step.password()) {
            if (!passwordCards.isEmpty()) {
                card.addView(sameRow(step));
            }
            passwordCards.add(step);
            if (customCard(step)) {
                addCustomForm(card, step, true);
            }
            String login = step.option.login != null ? step.option.login : emailLogin();
            step.login = PillField.of(host, R.string.custom_login, login, false, true);
            step.secret = PillField.of(host, R.string.password_hint, "", true, true);
            card.addView(step.login.view);
            card.addView(step.secret.view);
        } else {
            if (customCard(step)) {
                addCustomForm(card, step, false);
            }
            step.secret = PillField.of(host, R.string.hint_token, "", true, true);
            card.addView(step.secret.view);
        }

        for (PillField field : new PillField[] {step.server, step.port, step.login, step.secret}) {
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

        // NOTE: a password or token card signs in on its own, as a browser
        // card does, so Connect waits on every card being signed in.
        if (!step.oauth()) {
            step.verify = cardAction(R.string.signin_check);
            step.verify.button.setOnClickListener(view -> verify(step));
            card.addView(step.verify.view);
        }

        step.status = new TextView(host);
        step.status.setTextSize(13);
        step.status.setPadding(host.ui.dp(16), host.ui.dp(8), host.ui.dp(16), 0);
        step.status.setVisibility(View.GONE);
        card.addView(step.status);

        // NOTE: a button as the sign-in it replaces, so the folded card still
        // reads as one that acts.
        CardAction again = cardAction(R.string.signin_again);
        again.view.setVisibility(View.GONE);
        again.button.setOnClickListener(view -> signInAgain(step));
        card.addView(again.view);
        step.again = again.view;
        step.card = card;
        return card;
    }

    /** A card's header glyph: its first domain's, or sending's. */
    private static int glyphOf(AuthStep step) {
        return step.sending ? R.drawable.ic_send : step.domains.get(0).icon;
    }

    /**
     * The switch linking a password card to an earlier one, off to begin with.
     *
     * <p>Linked to the first password card; with several before it, its label
     * picks which. Available once that card is signed in: turning it on signs
     * this card in with that card's login and password, against its own
     * server, and turning it off undoes it. A switch, not a tick: it turns a
     * setting of the card.
     */
    private View sameRow(AuthStep step) {
        List<AuthStep> earlier = new ArrayList<>(passwordCards);
        step.linked = earlier.get(0);

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(host.ui.dp(48));
        row.setPadding(host.ui.dp(16), 0, host.ui.dp(4), 0);

        TextView label = new TextView(host);
        label.setTextSize(15);
        label.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMarginEnd(host.ui.dp(12));
        row.addView(label, labelParams);

        Switch same = new Switch(host);
        same.setOnCheckedChangeListener(
                (view, checked) -> {
                    step.status.setVisibility(View.GONE);
                    if (checked && !step.signed && !verifying) {
                        verify(step);
                        return;
                    }
                    if (!checked && step.signed) {
                        unsign(step);
                    }
                    refreshSignIn();
                });
        row.addView(same);
        step.same = same;
        step.sameLabel = label;

        if (earlier.size() > 1) {
            label.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    0, 0, R.drawable.ic_expand_more, 0);
            label.setCompoundDrawableTintList(
                    android.content.res.ColorStateList.valueOf(
                            host.ui.resolveColor(android.R.attr.textColorSecondary)));
            label.setOnClickListener(view -> pickLinked(step, earlier, label));
        }
        nameLinked(step);
        return row;
    }

    private void nameLinked(AuthStep step) {
        step.sameLabel.setText(host.getString(R.string.signin_same, domainNames(step.linked)));
    }

    /**
     * Picks which earlier password card a card reuses, among those signed in,
     * and signs it in with that one.
     */
    private void pickLinked(AuthStep step, List<AuthStep> earlier, View anchor) {
        if (verifying || connecting || step.signed) {
            return;
        }
        android.widget.PopupMenu menu = new android.widget.PopupMenu(host, anchor);
        for (int index = 0; index < earlier.size(); index++) {
            menu.getMenu()
                    .add(0, index, index, domainNames(earlier.get(index)))
                    .setEnabled(earlier.get(index).signed);
        }
        menu.setOnMenuItemClickListener(
                item -> {
                    step.linked = earlier.get(item.getItemId());
                    nameLinked(step);
                    if (step.same.isChecked()) {
                        verify(step);
                    } else {
                        step.same.setChecked(true);
                    }
                    return true;
                });
        menu.show();
    }

    /** Whether a card is drawn as the custom settings form. */
    private boolean customCard(AuthStep step) {
        return step.sending
                ? setups.get(PimDomain.MAIL).submitSelected.manual
                : step.option.isManual();
    }

    /** The card's tune button, opening how it signs in ({@link #showTuneMenu}). */
    private View tuneButton(AuthStep step) {
        android.widget.ImageButton tune = new android.widget.ImageButton(host);
        tune.setImageResource(R.drawable.ic_tune);
        tune.setBackgroundResource(R.drawable.ripple_circle);
        tune.setContentDescription(host.getString(R.string.signin_tune));
        int size = host.getResources().getDimensionPixelSize(R.dimen.app_bar_button);
        tune.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        tune.setOnClickListener(view -> showTuneMenu(step, view));
        return tune;
    }

    /** One row of the tune menu and what picking it does. */
    private record TuneItem(String title, Runnable action) {}

    /**
     * The tune menu: the other ways discovery found for the card's domains,
     * then custom settings, or back to the discovered ones from the form.
     *
     * <p>Picking a way for one domain moves it out of a card covering several
     * ({@link #moveDomain}); the sending card lists the other submission
     * servers and not sending ({@link #changeSending}).
     */
    private void showTuneMenu(AuthStep step, View anchor) {
        if (verifying || connecting) {
            return;
        }
        List<TuneItem> items = step.sending ? sendingItems(step) : readingItems(step);
        android.widget.PopupMenu menu = new android.widget.PopupMenu(host, anchor);
        for (int index = 0; index < items.size(); index++) {
            menu.getMenu().add(0, index, index, items.get(index).title());
        }
        menu.setOnMenuItemClickListener(
                item -> {
                    items.get(item.getItemId()).action().run();
                    return true;
                });
        menu.show();
    }

    private List<TuneItem> readingItems(AuthStep step) {
        List<TuneItem> items = new ArrayList<>();
        boolean several = step.domains.size() > 1;
        for (PimDomain domain : new ArrayList<>(step.domains)) {
            DomainSetup setup = setups.get(domain);
            String prefix = several ? host.getString(domain.label) + ": " : "";
            for (SetupOption option : setup.options) {
                if (option == setup.selected || option.isManual() || option.method == null) {
                    continue;
                }
                items.add(
                        new TuneItem(
                                prefix + sentence(option) + " · " + option.label,
                                () -> moveDomain(domain, option, null)));
            }
            if (step.custom()) {
                SetupOption discovered =
                        SetupPlan.standardOption(domain, setup.options, pendingEmail);
                if (discovered != null) {
                    items.add(
                            new TuneItem(
                                    host.getString(R.string.signin_discovered),
                                    () -> moveDomain(domain, discovered, null)));
                }
            } else {
                String prefill = prefillOf(domain);
                items.add(
                        new TuneItem(
                                several
                                        ? host.getString(
                                                R.string.signin_custom_for,
                                                host.getString(domain.label))
                                        : host.getString(R.string.signin_custom),
                                () -> moveDomain(domain, manualOption(), prefill)));
            }
        }
        return items;
    }

    private List<TuneItem> sendingItems(AuthStep step) {
        DomainSetup mail = setups.get(PimDomain.MAIL);
        List<TuneItem> items = new ArrayList<>();
        SubmitOption discovered = null;
        for (SubmitOption option : mail.submitOptions) {
            if (option.none()
                    || option.manual
                    || option.through != null
                    || !SetupPlan.offers(mail.selected, option)) {
                continue;
            }
            if (discovered == null) {
                discovered = option;
            }
            if (option == mail.submitSelected) {
                continue;
            }
            items.add(
                    new TuneItem(
                            option.detail == null ? option.label : option.label + " · " + option.detail,
                            () -> changeSending(option, null)));
        }
        if (step.custom()) {
            SubmitOption back = discovered;
            if (back != null) {
                items.add(
                        new TuneItem(
                                host.getString(R.string.signin_discovered),
                                () -> changeSending(back, null)));
            }
        } else {
            String prefill = mail.submitSelected.url;
            items.add(
                    new TuneItem(
                            host.getString(R.string.signin_custom),
                            () ->
                                    changeSending(
                                            new SubmitOption(
                                                    host.getString(R.string.domain_manual),
                                                    null,
                                                    null,
                                                    true),
                                            prefill)));
        }
        items.add(
                new TuneItem(
                        host.getString(R.string.send_mail_none),
                        () ->
                                changeSending(
                                        new SubmitOption(
                                                host.getString(R.string.send_mail_none),
                                                null,
                                                null,
                                                false),
                                        null)));
        return items;
    }

    /**
     * The endpoint a domain's custom form starts from: its current option's
     * when it speaks the form's protocol (IMAP, CardDAV, CalDAV), else the
     * first such option discovery found, else none.
     */
    private String prefillOf(PimDomain domain) {
        DomainSetup setup = setups.get(domain);
        String wanted =
                domain == PimDomain.MAIL
                        ? "imap"
                        : domain == PimDomain.CONTACTS ? "carddav" : "caldav";
        if (setup.selected != null && wanted.equals(setup.selected.service)) {
            return setup.selected.baseUrl;
        }
        for (SetupOption option : setup.options) {
            if (wanted.equals(option.service)) {
                return option.baseUrl;
            }
        }
        return null;
    }

    /**
     * Takes a domain out of the cards it is on: its card goes when it covered
     * nothing else, and mail's sending card with mail. Answers where a card
     * of its own goes in the page, the place its old card held.
     */
    private int detach(PimDomain domain) {
        int at = -1;
        for (AuthStep step : new ArrayList<>(authSteps)) {
            boolean sending = step.sending && domain == PimDomain.MAIL;
            if (!sending && (step.sending || !step.domains.contains(domain))) {
                continue;
            }
            int index = authSteps.indexOf(step);
            if (!sending && at < 0) {
                at = index;
            }
            step.domains.remove(domain);
            if (sending || step.domains.isEmpty()) {
                removeCard(step);
            } else {
                // NOTE: what signed the card in covered the domain moved out
                // too; the others keep it.
                step.names.setText(domainNames(step));
                if (at == index) {
                    at = index + 1;
                }
            }
        }
        DomainSetup setup = setups.get(domain);
        setup.credential = null;
        if (domain == PimDomain.MAIL) {
            setup.submitCredential = null;
        }
        return at < 0 ? authSteps.size() : Math.min(at, authSteps.size());
    }

    /** Takes one card off the page, the cards linked to it unlinked. */
    private void removeCard(AuthStep step) {
        LinearLayout container = host.findViewById(R.id.signin_container);
        authSteps.remove(step);
        passwordCards.remove(step);
        container.removeView(step.card);
        for (AuthStep other : authSteps) {
            if (other.linked != step) {
                continue;
            }
            if (other.same.isChecked()) {
                other.same.setChecked(false);
            }
            int earlier = passwordCards.indexOf(other);
            if (earlier > 0) {
                other.linked = passwordCards.get(0);
                nameLinked(other);
            } else {
                // NOTE: the first password card now, with nothing to reuse.
                ((View) other.same.getParent()).setVisibility(View.GONE);
                other.same = null;
                other.linked = null;
            }
        }
    }

    /**
     * Signs a domain in another way: out of its card, onto an unsigned card
     * of the same sign-in or a card of its own where its old one stood, the
     * other cards and what was typed in them kept.
     *
     * <p>Mail brings its sending card along, where it sends picked again for
     * the new way to read.
     */
    private void moveDomain(PimDomain domain, SetupOption option, String prefill) {
        LinearLayout container = host.findViewById(R.id.signin_container);
        int at = detach(domain);
        DomainSetup setup = setups.get(domain);
        setup.selected = option;

        AuthStep step = null;
        if (!option.isManual()) {
            for (AuthStep other : authSteps) {
                if (!other.sending
                        && !other.signed
                        && !other.custom()
                        && SetupPlan.sameSignIn(other.option, option)) {
                    other.domains.add(domain);
                    other.names.setText(domainNames(other));
                    step = other;
                    break;
                }
            }
        }
        if (step == null) {
            step = new AuthStep(option);
            step.domains.add(domain);
            step.prefill = prefill;
            authSteps.add(at, step);
            container.addView(signInCard(step), at);
            at++;
        } else {
            at = authSteps.indexOf(step) + 1;
        }

        if (domain == PimDomain.MAIL) {
            if (setup.submitSelected == null
                    || setup.submitSelected.manual
                    || !SetupPlan.offers(option, setup.submitSelected)) {
                setup.submitSelected = SetupPlan.firstOffered(option, setup.submitOptions);
            }
            AuthStep sending = sendingStep(step);
            if (sending != null) {
                authSteps.add(at, sending);
                container.addView(signInCard(sending), at);
            }
        }
        refreshSignIn();
    }

    /**
     * Sends another way: another submission server, one typed in the custom
     * form, or none; the sending card drawn again, or gone.
     */
    private void changeSending(SubmitOption choice, String prefill) {
        DomainSetup mail = setups.get(PimDomain.MAIL);
        LinearLayout container = host.findViewById(R.id.signin_container);
        int at = -1;
        AuthStep reading = null;
        for (AuthStep step : new ArrayList<>(authSteps)) {
            if (step.sending) {
                at = authSteps.indexOf(step);
                removeCard(step);
            } else if (step.domains.contains(PimDomain.MAIL)) {
                reading = step;
            }
        }
        mail.submitSelected = choice;
        mail.submitCredential = null;
        if (reading != null) {
            AuthStep sending = sendingStep(reading);
            if (sending != null) {
                sending.prefill = prefill;
                int index = at < 0 ? authSteps.indexOf(reading) + 1 : at;
                authSteps.add(index, sending);
                container.addView(signInCard(sending), index);
            }
        }
        refreshSignIn();
    }

    /**
     * The custom settings form: the server and its port for mail and sending,
     * STARTTLS for sending, a URL for contacts and calendars, and whether the
     * secret is an API token, offered beside a login only; prefilled from
     * {@link AuthStep#prefill}.
     */
    private void addCustomForm(LinearLayout card, AuthStep step, boolean tokenOffered) {
        java.net.URI prefill = null;
        try {
            prefill = step.prefill == null ? null : java.net.URI.create(step.prefill);
        } catch (IllegalArgumentException unreadable) {
            // NOTE: an endpoint that reads as no URI starts the form empty.
        }
        boolean dav = !step.sending && step.domains.get(0) != PimDomain.MAIL;
        if (dav) {
            step.server =
                    PillField.of(
                            host,
                            R.string.manual_dav_url,
                            step.prefill == null ? "" : step.prefill,
                            false,
                            true);
            card.addView(step.server.view);
        } else {
            String hostName = prefill == null || prefill.getHost() == null ? "" : prefill.getHost();
            String port = prefill == null || prefill.getPort() < 0 ? "" : String.valueOf(prefill.getPort());
            step.server = PillField.of(host, R.string.custom_server, hostName, false, true);
            step.port = PillField.of(host, R.string.custom_port, port, false, true);
            step.port.input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            card.addView(step.server.view);
            card.addView(step.port.view);
        }
        if (step.sending) {
            step.starttls = new Switch(host);
            step.starttls.setChecked(prefill != null && "smtp".equals(prefill.getScheme()));
            card.addView(switchLine(R.string.custom_starttls, step.starttls));
        }
        if (!tokenOffered) {
            return;
        }
        step.token = new Switch(host);
        step.token.setOnCheckedChangeListener(
                (view, checked) -> {
                    step.secret.input.setHint(checked ? R.string.hint_token : R.string.password_hint);
                    step.status.setVisibility(View.GONE);
                    refreshSignIn();
                });
        card.addView(switchLine(R.string.custom_token, step.token));
    }

    /** A line of a card: a label and its switch. */
    private View switchLine(int label, Switch toggle) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(host.ui.dp(48));
        row.setPadding(host.ui.dp(16), host.ui.dp(6), host.ui.dp(4), 0);
        TextView text = new TextView(host);
        text.setText(label);
        text.setTextSize(15);
        text.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginEnd(host.ui.dp(12));
        row.addView(text, params);
        row.addView(toggle);
        return row;
    }

    /** Where a custom reading form signs in, or null when it is not encrypted. */
    private static String customUrl(AuthStep card, PimDomain domain) {
        if (card.port == null) {
            return Endpoints.manualUrl(domain, card.server.text());
        }
        String port = card.port.text();
        return Endpoints.manualUrl(domain, card.server.text() + (port.isEmpty() ? "" : ":" + port));
    }

    /**
     * Where a custom sending form submits: implicit TLS on 465 or STARTTLS on
     * 587 when no port is typed; null when it reads as no server.
     */
    private static String customSubmitUrl(AuthStep card) {
        boolean starttls = card.starttls.isChecked();
        String port = card.port.text();
        if (port.isEmpty()) {
            port = starttls ? "587" : "465";
        }
        return Endpoints.submitUrl(
                (starttls ? "smtp://" : "smtps://") + card.server.text() + ":" + port);
    }

    /** A card's own action, full width, on the card's tone. */
    private CardAction cardAction(int text) {
        Button button = new Button(host);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(button.getTypeface(), android.graphics.Typeface.BOLD);
        button.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
        button.setBackgroundResource(R.drawable.button_on_card);
        button.setStateListAnimator(null);

        // NOTE: the loader at the button's end, as the pages' Continue
        // carries one, in the label's colour.
        android.widget.ProgressBar progress = new android.widget.ProgressBar(host);
        progress.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(
                        host.ui.resolveColor(android.R.attr.textColorPrimary)));
        progress.setElevation(host.ui.dp(4));
        progress.setVisibility(View.GONE);

        android.widget.FrameLayout frame = new android.widget.FrameLayout(host);
        frame.addView(
                button,
                new android.widget.FrameLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, host.ui.dp(48)));
        android.widget.FrameLayout.LayoutParams progressParams =
                new android.widget.FrameLayout.LayoutParams(
                        host.ui.dp(24),
                        host.ui.dp(24),
                        android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        progressParams.setMarginEnd(host.ui.dp(16));
        frame.addView(progress, progressParams);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = host.ui.dp(10);
        frame.setLayoutParams(params);
        return new CardAction(frame, button, progress);
    }

    /** A card's own button with its loader, as {@link #cardAction} builds it. */
    private static final class CardAction {
        final View view;
        final Button button;
        final android.widget.ProgressBar progress;

        CardAction(View view, Button button, android.widget.ProgressBar progress) {
            this.view = view;
            this.button = button;
            this.progress = progress;
        }

        /** Dims the button as when disabled, its loader turning, or not. */
        void loading(boolean on) {
            enable(!on);
            progress.setVisibility(on ? View.VISIBLE : View.GONE);
        }

        void enable(boolean on) {
            button.setEnabled(on);
            button.setAlpha(on ? 1f : 0.4f);
        }
    }

    /**
     * Undoes a card's sign-in, its domains' credentials dropped, so it can be
     * signed in again.
     */
    private void signInAgain(AuthStep step) {
        if (verifying || connecting) {
            return;
        }
        unsign(step);
        step.status.setVisibility(View.GONE);
        // NOTE: a linked card signed in again takes its own fields: with the
        // switch on it would have nothing to sign in with.
        if (step.reuses()) {
            step.same.setChecked(false);
        }
        refreshSignIn();
    }

    /** Drops what a card signed in with. */
    private void unsign(AuthStep step) {
        step.signed = false;
        if (step.sending) {
            setups.get(PimDomain.MAIL).submitCredential = null;
        } else {
            for (PimDomain domain : step.domains) {
                setups.get(domain).credential = null;
            }
        }
    }

    /** A card's domains, by name: "Contacts, Calendar", or mail's sending. */
    private String domainNames(AuthStep step) {
        if (step.sending) {
            return host.getString(R.string.signin_sending);
        }
        List<String> names = new ArrayList<>();
        for (PimDomain domain : step.domains) {
            names.add(host.getString(domain.label));
        }
        return String.join(", ", names);
    }

    /**
     * A card's second line: protocol and server (or by hand); a browser card
     * reading mail adds where it sends, its grant covering it.
     */
    private String signInDetail(AuthStep step) {
        DomainSetup mail = setups.get(PimDomain.MAIL);
        if (step.sending) {
            SubmitOption submit = mail.submitSelected;
            if (submit.manual) {
                return host.getString(R.string.signin_manual);
            }
            return submit.detail == null ? submit.label : submit.label + " · " + submit.detail;
        }
        SetupOption option = step.option;
        if (option.isManual()) {
            return host.getString(R.string.signin_manual);
        }
        String detail = option.detail == null ? option.label : option.label + " · " + option.detail;
        if (step.oauth()
                && step.domains.contains(PimDomain.MAIL)
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
     * <p>A signed card folds to its header, a check for its glyph, and the
     * link signing it in again. A card's own sign-in is available once it
     * has what it asks for; Connect once every card is signed in.
     */
    private void refreshSignIn() {
        if (authSteps == null) {
            return;
        }
        boolean ready = true;
        for (AuthStep step : authSteps) {
            boolean open = !step.signed;
            ready &= step.signed;
            if (step.browser != null) {
                step.browser.view.setVisibility(open ? View.VISIBLE : View.GONE);
                step.browser.loading(step == pendingStep);
                step.ownClient.setVisibility(open ? View.VISIBLE : View.GONE);
            }
            if (step.same != null) {
                ((View) step.same.getParent()).setVisibility(open ? View.VISIBLE : View.GONE);
                // NOTE: there is nothing to reuse before that card signed in.
                step.same.setEnabled(step.linked.signed && !verifying && !connecting);
            }
            boolean typed = open && !step.reuses();
            if (step.secret != null) {
                step.secret.view.setVisibility(typed ? View.VISIBLE : View.GONE);
            }
            // NOTE: a token names no login.
            boolean login = step.login != null && step.password();
            if (step.login != null) {
                step.login.view.setVisibility(typed && login ? View.VISIBLE : View.GONE);
            }
            if (step.custom()) {
                for (View field :
                        new View[] {
                            step.server.view,
                            step.port == null ? null : step.port.view,
                            step.starttls == null ? null : (View) step.starttls.getParent(),
                            step.token == null ? null : (View) step.token.getParent()
                        }) {
                    if (field != null) {
                        field.setVisibility(open ? View.VISIBLE : View.GONE);
                    }
                }
            }
            if (step.verify != null) {
                boolean filled =
                        filled(step.server)
                                && filled(step.secret)
                                && (!login || filled(step.login));
                // NOTE: a linked card signs in as its switch turns on, the
                // button turning meanwhile, so it shows only while checking.
                boolean shown = open && (!step.reuses() || checking.contains(step));
                step.verify.view.setVisibility(shown ? View.VISIBLE : View.GONE);
                if (checking.contains(step)) {
                    step.verify.loading(true);
                } else {
                    step.verify.loading(false);
                    step.verify.enable(filled && !verifying);
                }
            }
            step.glyph.setImageResource(step.signed ? R.drawable.ic_check : glyphOf(step));
            step.glyph.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            host.ui.resolveColor(
                                    step.signed
                                            ? android.R.attr.colorAccent
                                            : android.R.attr.textColorPrimary)));
            if (step.signed) {
                step.status.setVisibility(View.GONE);
            }
            step.again.setVisibility(step.signed ? View.VISIBLE : View.GONE);
        }
        host.setFabEnabled(R.id.signin_continue, ready && !verifying);
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
     * Connect on the sign-in page, every card signed in by then: the commit
     * ({@link #nextGrant} finds no grant left).
     */
    private void connectAll() {
        host.hideKeyboard();

        nextGrant();
    }

    /**
     * A password or token card's own sign-in: each of its domains tried the
     * way it will sync, side by side, the outcome said on the card.
     *
     * <p>A linked card signs in with the login and password of the card it
     * reuses, against its own server, and only itself: each card is the
     * user's own step.
     */
    private void verify(AuthStep step) {
        host.hideKeyboard();

        List<AuthStep> cards = List.of(step);

        List<Probe> probes = new ArrayList<>();
        for (AuthStep card : cards) {
            AuthStep source = card.source();
            String login = card.password() ? source.login.text() : "";
            String secret = source.secret.input.getText().toString();
            if (card.sending) {
                SubmitOption submit = setups.get(PimDomain.MAIL).submitSelected;
                String url = card.custom() ? customSubmitUrl(card) : submit.url;
                if (url == null) {
                    insecure(card);
                    return;
                }
                submit.url = url;
                probes.add(new Probe(card, PimDomain.MAIL, url, login, secret));
                continue;
            }
            for (PimDomain domain : card.domains) {
                String baseUrl =
                        card.custom()
                                ? customUrl(card, domain)
                                : setups.get(domain).selected.baseUrl;
                if (baseUrl == null) {
                    insecure(card);
                    return;
                }
                probes.add(new Probe(card, domain, baseUrl, login, secret));
            }
        }

        verifying = true;
        for (AuthStep card : cards) {
            card.status.setVisibility(View.GONE);
        }
        checking.addAll(cards);
        refreshSignIn();
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(probes.size() + 1);
        for (Probe probe : probes) {
            // NOTE: a token signs in to IMAP and SMTP with SASL XOAUTH2, which
            // names its user in the URL, as the stored account will.
            String url =
                    probe.domain == PimDomain.MAIL && probe.login.isEmpty()
                            ? Endpoints.asUser(probe.baseUrl, pendingEmail)
                            : probe.baseUrl;
            if (probe.step.sending) {
                Account account = new Account(url, probe.login, probe.secret, url);
                probe.result = pool.submit(() -> probeSending(account));
            } else {
                Account account = new Account(url, probe.login, probe.secret);
                probe.result = pool.submit(() -> probe(probe.domain, account));
            }
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
        checking.clear();

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
            if (!probe.step.sending && unserved.containsAll(probe.step.domains)) {
                refused.putIfAbsent(probe.step, failures.get(probe));
            }
        }
        for (Probe probe : probes) {
            if (refused.containsKey(probe.step)) {
                continue;
            }
            probe.step.signed = true;
            DomainSetup setup = setups.get(probe.domain);
            if (probe.step.sending) {
                setup.submitCredential = AccountCredential.password(probe.login, probe.secret);
                continue;
            }
            if (unserved.contains(probe.domain)) {
                setup.enabled = false;
                continue;
            }
            setup.baseUrl = probe.baseUrl;
            setup.credential = AccountCredential.password(probe.login, probe.secret);
        }
        for (Map.Entry<AuthStep, Exception> entry : refused.entrySet()) {
            AuthStep card = entry.getKey();
            Exception failure = entry.getValue();
            String text;
            if (failure instanceof NotServed) {
                text = failure.getMessage();
            } else if (!wrongPassword(failure)) {
                text = host.getString(R.string.connect_failed);
            } else if (card.password()) {
                text = host.getString(R.string.result_refused);
            } else {
                text = host.getString(R.string.token_refused);
            }
            // NOTE: a linked card that did not sign in takes its own login and
            // password: unlinked, its fields shown. Set before the outcome, as
            // the switch hides the outcome line.
            if (card.reuses()) {
                card.same.setChecked(false);
            }
            status(card, text, true);
        }

        if (!unserved.isEmpty()) {
            host.toast(notServed(unserved));
        }
        // NOTE: the page stays: the other cards sign in on their own, and
        // Connect commits once all have.
        refreshSignIn();
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
            oauth.startGoogleOauth(pendingEmail, Oauth.fixedScope(shipped, scope));
            return;
        }
        if (shipped == Oauth.Shipped.MICROSOFT) {
            oauth.startMicrosoftOauth(pendingEmail, Oauth.fixedScope(shipped, scope));
            return;
        }
        if (option.method.type() == AuthMethod.Type.OAUTH_ISSUER) {
            // NOTE: the resource, never the base URL. A JMAP account's base is
            // the internal jmap:// marker and a mailbox's is imaps://, and an
            // authorization server asked to issue a token for either answers
            // invalid_target: neither is a URI it protects.
            oauth.startIssuerOauth(
                    new OauthFlow.Target(pendingEmail, option.resource),
                    option.method.issuer(),
                    grantedDomains(),
                    new OauthFlow.Prompt(heading, own));
            return;
        }
        oauth.promptOauthClient(
                new OauthFlow.Target(pendingEmail, option.resource),
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
            if (server.info().announcesCapabilities()) {
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
                        Set<String> capabilities =
                                parts.clients().contact().jmapCapabilities(transport, session);
                        Log.d("pimalaya", "jmap session capabilities: " + capabilities);
                        served = PimDomain.servedByJmap(capabilities);
                    } catch (Exception error) {
                        // NOTE: unread rather than refused: the grant stands,
                        // and the first sync says what the session cannot do.
                        Log.w("pimalaya", "jmap session unread after the grant", error);
                    }
                    Set<PimDomain> read = served;
                    host.postAlive(
                            () -> {
                                List<PimDomain> unserved = new ArrayList<>();
                                for (PimDomain domain : overJmap) {
                                    if (read != null && !read.contains(domain)) {
                                        unserved.add(domain);
                                    }
                                }
                                List<PimDomain> left = fallBack(unserved);
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
            setup.submitCredential = null;
        }
        runSignIns();
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
            if (account.info().announcesCapabilities()) {
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

    /**
     * Signs in to the submission server and out, sending nothing: whether it
     * takes the sending card's credential. Null when it does.
     */
    private Exception probeSending(Account account) {
        try (Transport transport = new Transport()) {
            MailClient.checkSubmission(transport, account);
            return null;
        } catch (Exception error) {
            Log.w("pimalaya", "sending check failed", error);
            return error;
        }
    }

    /**
     * Moves the domains a JMAP session turned out not to serve onto their best
     * option off JMAP, each a card of its own on the sign-in page, and answers
     * those with none, which are left out.
     *
     * <p>Whether a session serves calendars is known only once signed in, so
     * JMAP leads every domain and this is the way back to CalDAV.
     */
    private List<PimDomain> fallBack(List<PimDomain> unserved) {
        List<PimDomain> left = new ArrayList<>();
        if (unserved.isEmpty() || authSteps == null) {
            return left;
        }
        for (PimDomain domain : unserved) {
            DomainSetup setup = setups.get(domain);
            List<SetupOption> offJmap = new ArrayList<>();
            for (SetupOption option : setup.options) {
                if (!"jmap".equals(option.service)) {
                    offJmap.add(option);
                }
            }
            SetupOption fallback = SetupPlan.standardOption(domain, offJmap, pendingEmail);
            if (fallback == null) {
                detach(domain);
                setup.credential = null;
                setup.enabled = false;
                left.add(domain);
                continue;
            }
            Log.d("pimalaya", domain + " not served over jmap, falls back to " + fallback.service);
            moveDomain(domain, fallback, null);
        }
        refreshSignIn();
        return left;
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
    /**
     * What mail's sending signed in with when it is not mail's own login and
     * password, else null: the same secret is stored once.
     */
    private static AccountCredential ownSending(DomainSetup setup) {
        AccountCredential sending = setup.submitCredential;
        AccountCredential reading = setup.credential;
        if (sending == null
                || (reading.login.equals(sending.login) && reading.secret.equals(sending.secret))) {
            return null;
        }
        return sending;
    }

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
                            setup.domain,
                            setup.baseUrl,
                            submitUrl,
                            setup.credential,
                            submitUrl == null ? null : ownSending(setup)));
        }
        connectedAccount = AccountCommit.assemble(connectedEmail, connections);
        if (connectedAccount == null) {
            return;
        }

        // NOTE: mail's mailboxes are asked first, in the bar's order, then
        // this runs again with them chosen.
        Account mailServer = connectedAccount.server(PimDomain.MAIL);
        if (mailServer != null && chosenMailboxes == null) {
            listMailboxes(mailServer);
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
        pickingMailboxes = false;
        ((TextView) host.findViewById(R.id.books_heading)).setText(R.string.books_title);
        ((Button) host.findViewById(R.id.books_continue)).setText(R.string.books_finish);

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
                addChoice(card, book.name(), book.url(), true);
            }
        }

        shell.showAuth(STEP_BOOKS);
        resetConfigContinue();
    }

    /** One checkbox row of the books page, kept with its key. */
    private void addChoice(LinearLayout card, String label, String key, boolean on) {
        if (card.getChildCount() > 0) {
            View divider = CardRows.rowDivider(host);
            ((LinearLayout.LayoutParams) divider.getLayoutParams()).setMarginStart(host.ui.dp(20));
            card.addView(divider);
        }
        CheckBox subscribe = new CheckBox(host);
        subscribe.setChecked(on);
        subscribe.setClickable(false);
        subscribe.setFocusable(false);

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(host.ui.dp(60));
        row.setPadding(host.ui.dp(20), 0, host.ui.dp(8), 0);
        TypedValue ripple = new TypedValue();
        host.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setForeground(host.getDrawable(ripple.resourceId));

        TextView name = new TextView(host);
        name.setText(label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(subscribe);
        row.setOnClickListener(
                view -> {
                    subscribe.toggle();
                    updateBooksContinue();
                });
        card.addView(row);

        bookChoices.add(new BookChoice(key, subscribe));
    }

    /**
     * Lists the signed-in mail's mailboxes for mail's step; a single one is
     * taken without asking.
     */
    private void listMailboxes(Account server) {
        busy();
        host.io.execute(
                () -> {
                    List<org.pimalaya.client.Mailbox> listed;
                    try (org.pimalaya.client.MailSession session = MailClient.openMail(server)) {
                        listed = parts.clients().mail().listMailboxes(session);
                    } catch (Exception error) {
                        Log.w("pimalaya", "mailboxes unlisted", error);
                        host.postAlive(
                                () -> {
                                    resetConfigContinue();
                                    host.showError(error, R.string.connect_failed);
                                });
                        return;
                    }
                    // NOTE: not postAlive: the step it leads to may persist
                    // the account (finishOnboarding).
                    host.main.post(
                            () -> {
                                resetConfigContinue();
                                if (listed.size() <= 1) {
                                    chosenMailboxes = new HashSet<>();
                                    for (org.pimalaya.client.Mailbox mailbox : listed) {
                                        chosenMailboxes.add(mailbox.name());
                                    }
                                    commitConnections();
                                } else {
                                    openMailboxSelection(listed);
                                }
                            });
                });
    }

    /**
     * Mail's step: one checkbox row per mailbox, every one ticked but the
     * junk and the trash, which the store would otherwise hide on its own.
     */
    private void openMailboxSelection(List<org.pimalaya.client.Mailbox> mailboxes) {
        pickingMailboxes = true;
        bookChoices = new ArrayList<>();

        ((TextView) host.findViewById(R.id.books_heading)).setText(R.string.mailboxes_title);
        ((TextView) host.findViewById(R.id.books_line))
                .setText(host.getString(R.string.books_signed, connectedEmail));
        ((TextView) host.findViewById(R.id.books_message)).setText(R.string.mailboxes_description);
        host.findViewById(R.id.books_note).setVisibility(View.GONE);
        ((Button) host.findViewById(R.id.books_continue)).setText(R.string.email_submit);

        LinearLayout container = host.findViewById(R.id.books_container);
        container.removeAllViews();
        LinearLayout card = CardRows.card(host);
        container.addView(card);
        for (org.pimalaya.client.Mailbox mailbox : mailboxes) {
            boolean on =
                    !"junk".equals(mailbox.role())
                            && !org.pimalaya.client.Mailbox.TRASH.equals(mailbox.role());
            addChoice(card, mailbox.name(), mailbox.name(), on);
        }

        shell.showAuth(STEP_BOOKS);
        resetConfigContinue();
    }

    /** Mail's step continued: the ticked mailboxes, then the rest of the commit. */
    private void confirmMailboxes() {
        Set<String> chosen = new HashSet<>();
        for (BookChoice choice : bookChoices) {
            if (choice.subscribe.isChecked()) {
                chosen.add(choice.url);
            }
        }
        chosenMailboxes = chosen;
        pickingMailboxes = false;
        commitConnections();
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
        if (chosenMailboxes != null && stored.server(PimDomain.MAIL) != null) {
            parts.mail().chooseMailboxes(stored.email, chosenMailboxes);
        }
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
