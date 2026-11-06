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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pimalaya.R;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.Endpoints;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.Oauth;
import org.pimalaya.account.PimDomain;
import org.pimalaya.calendar.PhoneCalendars;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.AuthMethod;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.OauthGrant;
import org.pimalaya.client.OauthRequest;
import org.pimalaya.client.OauthTokens;
import org.pimalaya.client.ServiceConfig;
import org.pimalaya.client.Transport;
import org.pimalaya.mail.SenderName;
import org.pimalaya.phone.Accounts;
import org.pimalaya.phone.PhoneMirror;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.sync.BackgroundCheck;
import org.pimalaya.sync.SyncCoordinator;
import org.pimalaya.sync.SyncPasses;
import org.pimalaya.ui.CardRows;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.PillField;
import org.pimalaya.ui.Sections;
import org.pimalaya.ui.Switches;

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

    /**
     * The domain selection, folded into the servers page (panel_domain, no
     * longer shown).
     */
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

    /**
     * Counts discoveries, so the late answer of one the flow was left during
     * is dropped.
     */
    private int discoveryRun;

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

    /** The servers page's cards, one per ticked domain, sending after mail. */
    private final List<Card> cards = new ArrayList<>();

    /** The domain pages, built on the servers page's Continue. */
    private final List<Page> pages = new ArrayList<>();

    /**
     * The page shown, an index in {@link #identities}: -1 for the servers
     * page, the size of {@link #identities} for the settings page after the
     * last.
     */
    private int pageAt = -1;

    /**
     * The page last scrolled to the top, so a page shown again for a change
     * on it keeps its scroll.
     */
    private int shownAt = Integer.MIN_VALUE;

    /** Whether a card changed since the pages were built, rebuilding them. */
    private boolean cardsChanged = true;

    /** The sign-ins the domain pages add up to, the pages shown between. */
    private final List<Identity> identities = new ArrayList<>();

    /** The sign-in running, Continue on its loader; null when none. */
    private Identity checking;

    /**
     * The scopes each domain of the grant in flight needs, when the provider
     * may grant fewer than asked (Google's granular consent); empty otherwise.
     */
    private final Map<PimDomain, Set<String>> grantNeeds =
            new java.util.EnumMap<>(PimDomain.class);

    /**
     * The fixed provider rule the address matched ({@code provider:google}...).
     *
     * <p>Null when none. Kept beside the discovered configs: a provider match
     * says how to sign in for contacts, not whether the address also serves
     * mail or calendars, which only the protocol sweep answers.
     */
    private String matchedProvider;

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

    /** Whether the books page shows the calendars. */
    private boolean pickingCalendars;

    /** The calendars ticked from the settings page, by URL; null for all. */
    private Set<String> chosenCalendars;

    /** The calendars the last listing held, their choice applied at Finish. */
    private List<org.pimalaya.client.Calendar> listedCalendars;

    /** The mailboxes ticked from Mail's page, by name; null until chosen. */
    private Set<String> chosenMailboxes;

    /** How many mailboxes the last listing held. */
    private int mailboxesTotal;

    /** The addressbooks ticked from the Contacts page, by URL; null for all. */
    private Set<String> chosenBooks;

    /** How many addressbooks the last listing held. */
    private int booksTotal;

    /** The name mail goes out under, from the sending page; empty for none. */
    private String chosenName = "";

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

        for (int id :
                new int[] {
                    R.id.email_continue,
                    R.id.result_continue,
                    R.id.signin_continue,
                    R.id.oauth_continue,
                    R.id.books_continue
                }) {
            ((TextView) host.findViewById(id)).setTextColor(host.accentContrast());
        }

        host.findViewById(R.id.email_continue).setOnClickListener(view -> submitEmail());
        email.setOnEditorActionListener(
                (view, action, event) -> {
                    if (emailSubmittable()) {
                        submitEmail();
                    }
                    return true;
                });

        host.findViewById(R.id.signin_continue).setOnClickListener(view -> pageContinue());
        host.findViewById(R.id.books_continue)
                .setOnClickListener(
                        view -> {
                            if (pickingMailboxes) {
                                confirmMailboxes();
                            } else if (pickingCalendars) {
                                confirmCalendars();
                            } else {
                                confirmBooks();
                            }
                        });
    }

    /** Resets the flow to its first step and shows it. */
    public void open() {
        pendingEmail = null;
        searchedConfigs = new ArrayList<>();
        matchedProvider = null;
        discoveryRun++;
        switches.reset();
        elsewhere = false;
        calendarsElsewhere = false;
        setups.clear();
        oauthGroup.clear();
        grantNeeds.clear();
        cards.clear();
        pages.clear();
        identities.clear();
        pageAt = -1;
        cardsChanged = true;
        checking = null;
        connectedAccount = null;
        chosenMailboxes = null;
        chosenBooks = null;
        chosenCalendars = null;
        listedCalendars = null;
        pickingCalendars = false;
        chosenName = "";
        pickingMailboxes = false;
        ((EditText) host.findViewById(R.id.email_input)).setText("");
        host.setAuthLoading(R.id.email_continue, R.id.email_progress, false);
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
     * The email step's continue: discovery runs, Continue on its loader, then
     * the servers page.
     *
     * <p>Asking for "an email, a server or a URI" made the user do the app's
     * job. The servers page ticks what discovery found, so it waits for it.
     */
    private void submitEmail() {
        host.hideKeyboard();

        pendingEmail =
                ((EditText) host.findViewById(R.id.email_input)).getText().toString().trim();
        host.setAuthLoading(R.id.email_continue, R.id.email_progress, true);
        search();
    }

    /**
     * Searches the address's service configs in the background.
     *
     * <p>The fixed provider rules first, then the protocol sweep. The answer
     * lands only while it is still the flow's latest discovery: the servers
     * page, or the failure reported when it found nothing at all.
     */
    private void search() {
        int run = ++discoveryRun;
        String email = pendingEmail;
        searchedConfigs = new ArrayList<>();
        matchedProvider = null;

        host.io.execute(
                () -> {
                    // NOTE: a null resolver falls back to the bridge's
                    // DNS-over-HTTPS default, which works on mobile
                    // networks that block outbound DNS over TCP.
                    String provider = null;
                    if (email.contains("@")) {
                        try {
                            List<ServiceConfig> hits;
                            try (Transport transport = new Transport()) {
                                hits =
                                        parts.clients()
                                                .account()
                                                .searchProvider(transport, email, null);
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
                        configs = parts.clients().account().searchAll(email, null);
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
                                // NOTE: a discovery the flow was left during
                                // lands nowhere.
                                if (run != discoveryRun) {
                                    return;
                                }
                                searchedConfigs = found;
                                matchedProvider = matched;
                                host.setAuthLoading(
                                        R.id.email_continue, R.id.email_progress, false);
                                if (searchFailure != null && found.isEmpty() && matched == null) {
                                    host.showError(searchFailure, R.string.discover_failed);
                                    return;
                                }
                                proceed();
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

        /**
         * Where this domain sends, for mail and for nothing else.
         *
         * <p>Every other domain leaves these empty: contacts and calendars
         * have nothing to submit, and a mail account reading over JMAP
         * submits through the session it already has.
         */
        final List<SubmitOption> submitOptions = new ArrayList<>();

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
         * What mail's sending page signed in with; null until it did, and
         * for every other domain.
         */
        AccountCredential submitCredential;

        /**
         * The user's own OAuth client, typed on the servers page; null for the
         * default one (the app's own, or a registration at an issuer).
         */
        Client client;
    }

    /**
     * Gives each domain its discovered options and the best of them, ticked
     * when discovery found one this client drives.
     *
     * <p>A domain discovery found nothing for is set up by hand: its card
     * opens unticked, on the custom form once ticked. A domain the account
     * already covers is left unticked too, being connected.
     */
    private void planSetups() {
        AccountEntry existing = AccountEntry.find(accounts, pendingEmail);
        setups.clear();
        for (PimDomain domain : PimDomain.values()) {
            DomainSetup setup = new DomainSetup(domain);
            setups.put(domain, setup);
            setup.options.addAll(optionsFor(domain));
            setup.selected = SetupPlan.standardOption(domain, setup.options, pendingEmail);
            if (setup.selected == null) {
                setup.selected = manualOption();
            }
            setup.enabled =
                    !setup.selected.isManual() && (existing == null || !existing.covers(domain));
            if (domain == PimDomain.MAIL) {
                setup.submitOptions.addAll(submissionOptions());
                // NOTE: the best endpoint found; none leaves the account
                // without one, a sender being addable from its settings.
                setup.submitSelected = SetupPlan.firstOffered(setup.selected, setup.submitOptions);
            }
        }
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
        // NOTE: an option discovered naming no method signs in with a password.
        switch (option.method == null ? AuthMethod.Type.PASSWORD : option.method.type()) {
            case PASSWORD:
                return host.getString(R.string.setup_via_password, server);
            case BEARER:
                return host.getString(R.string.setup_via_token, server);
            default:
                return host.getString(R.string.setup_via_browser, server);
        }
    }

    /**
     * Whether a calendar server holds tasks: CalDAV, and Google and
     * Microsoft through their task services (Google Tasks, To Do), which
     * their sign-in grants beside the calendar.
     *
     * <p>JMAP is told by its base, a marker scheme its calendar writes are
     * not wired for.
     */
    private static boolean takesTasks(String url) {
        String base = url == null ? "" : url;
        return base.startsWith("https://")
                || base.startsWith("http://")
                || base.startsWith("google://")
                || base.startsWith("msgraph://");
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

    /** A submission row's label: the protocol, and the server it names. */
    private String submitLabel(SubmitOption option) {
        String auth = option.auth == null ? "" : " · " + authName(option.auth);
        if (option.detail != null) {
            return option.label + " (" + option.detail + ")" + auth;
        }
        return option.url == null ? option.label : option.label + " (" + SetupPlan.hostOf(option.url) + ")";
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
            case STEP_EMAIL:
                host.setAuthLoading(R.id.email_continue, R.id.email_progress, true);
                break;
            default:
                break;
        }
    }

    /** The running step's main button back to idle, unless a page signs in. */
    public void resetConfigContinue() {
        switch (shell.authStep()) {
            case STEP_SIGNIN:
                if (checking == null) {
                    host.setAuthLoading(R.id.signin_continue, R.id.signin_progress, false);
                    refreshContinue();
                }
                break;
            case STEP_OAUTH:
                host.setAuthLoading(R.id.oauth_continue, R.id.oauth_progress, false);
                break;
            case STEP_BOOKS:
                host.setAuthLoading(R.id.books_continue, R.id.books_progress, false);
                updateBooksContinue();
                break;
            case STEP_EMAIL:
                host.setAuthLoading(R.id.email_continue, R.id.email_progress, false);
                host.setFabEnabled(R.id.email_continue, emailSubmittable());
                break;
            default:
                break;
        }
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
                                "www.googleapis.com",
                                AccountClient.googleCarddavBase(pendingEmail),
                                AuthMethod.codeGrant(
                                        Oauth.GOOGLE_AUTH_ENDPOINT,
                                        Oauth.GOOGLE_TOKEN_ENDPOINT,
                                        Oauth.GOOGLE_SCOPE)));
            }
            options.add(
                    providerOption(
                            R.string.config_google_api,
                            "people.googleapis.com",
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
                        "graph.microsoft.com",
                        AccountClient.msgraphBase(pendingEmail),
                        AuthMethod.codeGrant(
                                Oauth.MICROSOFT_AUTH_ENDPOINT,
                                Oauth.MICROSOFT_TOKEN_ENDPOINT,
                                Oauth.MICROSOFT_SCOPE)));
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

    /**
     * A provider's sign-in, named by the API host it reaches: its base may be
     * a marker standing for the account, which names none.
     */
    private SetupOption providerOption(
            int label, String apiHost, String baseUrl, AuthMethod method) {
        return new SetupOption(
                null,
                host.getString(label),
                apiHost,
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

    /** A switch line whose label is already worded. */
    private View switchLine(CharSequence label, Switch toggle) {
        View row = switchLine(R.string.signin_custom, toggle);
        ((TextView) ((LinearLayout) row).getChildAt(0)).setText(label);
        return row;
    }

    /**
     * One section of the servers page: a domain, or mail's sending.
     *
     * <p>On the option discovery ranked first, said in a sentence, until its
     * settings fill its form from another option found or open it for custom
     * settings, Auto bringing the sentence back; a card discovery found
     * nothing for shows the form from the start.
     */
    private static final class Card {
        final PimDomain domain;
        final boolean sending;

        /** Whether discovery found nothing this client drives for it. */
        final boolean unfound;

        /**
         * Whether it is ticked: a domain to connect, or mail sending through
         * a server of its own.
         */
        boolean ticked;

        /** Whether the card shows its form rather than its sentence. */
        boolean form;

        /**
         * Whether the form holds a JMAP session or a provider's API: its
         * server said rather than typed, its sign-in among the ones found for
         * that server.
         */
        boolean preset;

        /** The reading option the form was filled from; null for none. */
        SetupOption from;

        /** The sending option the form was filled from; null for none. */
        SubmitOption submitFrom;

        /** Whether the form was changed by hand since it was filled. */
        boolean edited;

        /** Whether the form is being filled, its watchers standing by. */
        boolean filling;

        /** How the form signs in. */
        Auth auth = Auth.PASSWORD;

        /** The form's fields, made once and kept across renders. */
        PillField server;

        PillField port;

        /** Whether sending upgrades with STARTTLS rather than starts in TLS. */
        boolean starttls;

        /** The values of the form's security, sign-in and application rows. */
        TextView securityValue;

        TextView authValue;
        TextView appValue;

        /** The application row, shown while the form signs in with OAuth. */
        View appRow;

        /**
         * Whether OAuth runs with the user's own client rather than the app's:
         * its own registration, or one registered at an issuer.
         */
        boolean ownClient;

        /**
         * The user's own OAuth client, shown while the application row reads
         * Custom: the server's endpoints prefilled, the client to enter.
         */
        PillField clientId;

        PillField clientSecret;
        PillField authUrl;
        PillField tokenUrl;
        PillField scope;
        PillField issuer;
        PillField redirect;

        /** Whether the client is an issuer's, its endpoints read from it. */
        boolean byIssuer;

        /** The sending form's line under OAuth: mail's grant covers it. */
        TextView throughMail;

        /** The form's views in order, its error line last. */
        final List<View> formViews = new ArrayList<>();

        /** The line under the form saying what is wrong with it. */
        TextView error;

        Card(PimDomain domain, boolean sending, boolean unfound, boolean ticked) {
            this.domain = domain;
            this.sending = sending;
            this.unfound = unfound;
            this.form = unfound;
            this.ticked = ticked;
        }

        /** Whether the form asks for a URL: contacts and calendars. */
        boolean dav() {
            return !sending && domain != PimDomain.MAIL;
        }
    }

    /** How a card's form signs in. */
    private enum Auth {
        PASSWORD(AuthMethod.Type.PASSWORD),
        TOKEN(AuthMethod.Type.BEARER),
        OAUTH(AuthMethod.Type.OAUTH_AUTHORIZATION_CODE_GRANT);

        final AuthMethod.Type type;

        Auth(AuthMethod.Type type) {
            this.type = type;
        }

        /** How a discovered method signs in, a password when it names none. */
        static Auth of(AuthMethod.Type type) {
            if (type == null || type == AuthMethod.Type.PASSWORD) {
                return PASSWORD;
            }
            return type == AuthMethod.Type.BEARER ? TOKEN : OAUTH;
        }
    }

    /**
     * An OAuth client typed on the servers page: its ID, its secret ("" for
     * none) and where the grant comes back.
     */
    private record Client(String id, String secret, String redirect) {}

    /** How a domain page signs in. */
    private enum SignIn {
        PASSWORD,
        TOKEN,
        BROWSER,
        /** Nothing of its own: mail's sign-in sends too. */
        MAIL
    }

    /**
     * One domain's sign-in: where it signs in and how, and whether it did.
     *
     * <p>Signed in with the others of its {@link Identity}, and a section of
     * the settings page.
     */
    private static final class Page {
        final PimDomain domain;
        final boolean sending;
        final SignIn signIn;

        /** Where it signs in: the domain's base URL, or the submit URL. */
        final String url;

        /** The option a browser grant runs with; null for the other ways. */
        final SetupOption option;

        /** The user's own OAuth client; null for the default one. */
        Client client;

        /** Whether its sign-in went through. */
        boolean signed;

        /** What its last sign-in ran into; null when none. */
        String problem;

        Page(PimDomain domain, boolean sending, SignIn signIn, String url, SetupOption option) {
            this.domain = domain;
            this.sending = sending;
            this.signIn = signIn;
            this.url = url;
            this.option = option;
        }
    }

    /**
     * One sign-in the account needs and the domains it covers: one browser
     * grant for an authorization server, audience and client; a login and
     * password, or a token, per domain, as a provider may give each service
     * its own (app passwords). Sending through mail's own sign-in goes with
     * mail.
     *
     * <p>Its page asks what it needs; its fields are made once and kept while
     * the flow steps between pages, so what was typed stays.
     */
    private static final class Identity {
        final SignIn signIn;
        final List<Page> pages = new ArrayList<>();

        /** The earlier password sign-in whose login and password it reuses. */
        Identity linked;

        /** Whether it was shown: stepping back skips the ones that were not. */
        boolean shown;

        PillField login;
        PillField secret;
        Switch same;

        /** The card's outcome line. */
        TextView status;

        Identity(SignIn signIn) {
            this.signIn = signIn;
        }

        /** The domain it is led by, whose server names it. */
        Page lead() {
            return pages.get(0);
        }

        /** Whether every domain it covers signed in. */
        boolean signed() {
            for (Page page : pages) {
                if (!page.signed) {
                    return false;
                }
            }
            return true;
        }

        /** Whether its switch reuses the linked sign-in's login, password. */
        boolean reuses() {
            return same != null && same.isChecked() && linked != null;
        }
    }

    /**
     * Opens the servers page: a section per domain, sending after mail, each
     * ticked when discovery found it.
     *
     * <p>Built afresh from discovery; the domain pages are built on its
     * Continue.
     */
    private void openServers() {
        cards.clear();
        pages.clear();
        cardsChanged = true;
        for (DomainSetup setup : setups.values()) {
            cards.add(new Card(setup.domain, false, setup.selected.isManual(), setup.enabled));
            if (setup.domain == PimDomain.MAIL) {
                cards.add(sendingCard(setup));
            }
        }
        pageAt = -1;
        shownAt = Integer.MIN_VALUE;
        showPage();
    }

    /**
     * Mail's sending section as discovery planned it, ticked when it found
     * where to send.
     */
    private static Card sendingCard(DomainSetup mail) {
        boolean unfound = sendingUnfound(mail);
        return new Card(PimDomain.MAIL, true, unfound, !unfound);
    }

    /**
     * Whether a section is part of the account: a ticked domain, or sending
     * with mail ticked, over mail's session or through a ticked server.
     */
    private boolean counts(Card card) {
        if (!card.sending) {
            return card.ticked;
        }
        DomainSetup mail = setups.get(PimDomain.MAIL);
        return mail.enabled && (card.ticked || sendsOverSession(mail));
    }

    /** The theme's alpha for what cannot be used for now. */
    private float disabledAlpha() {
        TypedValue value = new TypedValue();
        host.getTheme().resolveAttribute(android.R.attr.disabledAlpha, value, true);
        return value.type == TypedValue.TYPE_FLOAT ? value.getFloat() : 0.38f;
    }

    /** A section's tick toggled: its form shown or folded, Continue in line. */
    private void toggle(Card card) {
        card.ticked = !card.ticked;
        if (!card.sending) {
            setups.get(card.domain).enabled = card.ticked;
        }
        cardsChanged = true;
        showPage();
    }

    /**
     * Whether mail submits through the session it reads with, JMAP, Gmail and
     * Graph, so its sending has no server of its own to choose.
     */
    private static boolean sendsOverSession(DomainSetup mail) {
        SetupOption reading = mail.selected;
        return !reading.isManual() && (!reading.asksSubmission() || reading.readsOverApi());
    }

    /**
     * Whether the browser grant reading mail covers sending too, the
     * submission scopes merged into it ({@link #startGrant}).
     */
    private static boolean sendsThroughGrant(DomainSetup mail) {
        SubmitOption submit = mail.submitSelected;
        return mail.selected.isOauth() && submit != null && Auth.of(submit.auth) == Auth.OAUTH;
    }

    /** Whether nothing was found to send mail through. */
    private static boolean sendingUnfound(DomainSetup mail) {
        return !sendsOverSession(mail) && mail.submitSelected == null;
    }

    /** The name of a card or of its page: the domain's, or mail's sending. */
    private String nameOf(boolean sending, PimDomain domain) {
        return host.getString(sending ? R.string.signin_sending : domain.label);
    }

    /** The address's domain, as the servers page names it. */
    private String domainOf(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at < 0 ? String.valueOf(email) : email.substring(at + 1);
    }

    /**
     * A section's second line: how it connects, in a sentence; on its form,
     * the option it was filled from, or that it is set by hand. Unticked, that
     * the account has it already, or that nothing was found.
     */
    private String cardLine(Card card) {
        if (!counts(card)) {
            AccountEntry existing = AccountEntry.find(accounts, pendingEmail);
            if (!card.sending && existing != null && existing.covers(card.domain)) {
                return host.getString(R.string.domain_connected);
            }
            if (card.unfound) {
                return host.getString(R.string.servers_nothing);
            }
        }
        if (card.form) {
            if (card.unfound) {
                return null;
            }
            if (!card.edited && card.from != null) {
                return choiceLabel(card.from);
            }
            if (!card.edited && card.submitFrom != null) {
                return choiceLabel(card.submitFrom);
            }
            return host.getString(R.string.signin_custom);
        }
        DomainSetup setup = setups.get(card.domain);
        if (!card.sending || sendsOverSession(setup) || sendsThroughGrant(setup)) {
            return sentence(setup.selected);
        }
        SubmitOption submit = setup.submitSelected;
        return host.getString(
                Auth.of(submit.auth) == Auth.TOKEN
                        ? R.string.setup_via_token
                        : R.string.setup_via_password,
                SetupPlan.serverName(submit.url));
    }

    /**
     * Whether a card's settings have choices to list: what discovery found
     * for it; mail's sending over the session mail reads with has none.
     */
    private boolean hasChoices(Card card) {
        DomainSetup setup = setups.get(card.domain);
        if (card.sending) {
            return !sendsOverSession(setup) && !setup.submitOptions.isEmpty();
        }
        return !setup.options.isEmpty();
    }

    /**
     * One section of the servers page: its domain's tile, which is its tick,
     * how it connects and its settings button, then while picked its form.
     *
     * <p>Every section stays on the page whatever is picked: Mail (sending)
     * is greyed out while Mail is not picked, and a settings button while its
     * section is not.
     */
    private View cardView(Card card) {
        DomainSetup setup = setups.get(card.domain);
        boolean locked = card.sending && !setup.enabled;
        // NOTE: sending over mail's session is part of mail, its tick fixed.
        boolean fixed = card.sending && sendsOverSession(setup);
        boolean ticked = counts(card);
        float dimmed = disabledAlpha();
        LinearLayout section = new LinearLayout(host);
        section.setOrientation(LinearLayout.VERTICAL);

        View tune = null;
        if (hasChoices(card)) {
            android.widget.ImageButton button = new android.widget.ImageButton(host);
            button.setImageResource(R.drawable.ic_tune);
            button.setBackgroundResource(R.drawable.ripple_circle);
            button.setContentDescription(host.getString(R.string.signin_tune));
            int size = host.getResources().getDimensionPixelSize(R.dimen.app_bar_button);
            button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            button.setOnClickListener(clicked -> showChoices(card, clicked));
            button.setEnabled(ticked);
            button.setAlpha(ticked || locked ? 1f : dimmed);
            tune = button;
        }
        int glyph = card.sending ? R.drawable.ic_send : card.domain.icon;
        LinearLayout header =
                CardRows.tileRow(
                        host, ticked, glyph, nameOf(card.sending, card.domain), cardLine(card), tune);
        if (locked) {
            header.setForeground(null);
            header.setAlpha(dimmed);
        } else if (fixed) {
            header.setForeground(null);
        } else {
            header.setOnClickListener(clicked -> toggle(card));
        }
        section.addView(header);

        if (ticked && card.form) {
            ensureForm(card);
            LinearLayout form = new LinearLayout(host);
            form.setOrientation(LinearLayout.VERTICAL);
            form.setPadding(host.ui.dp(14), 0, host.ui.dp(14), host.ui.dp(16));
            if (card.unfound) {
                form.addView(noteLine(host.getString(R.string.setup_unfound, domainOf(pendingEmail))));
            }
            for (View field : card.formViews) {
                attach(form, field);
            }
            section.addView(form);
        }
        return section;
    }

    /**
     * A card's settings: Auto, every option discovery found for it by
     * protocol and sign-in, then the custom settings.
     */
    private void showChoices(Card card, View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(host, anchor);
        List<Runnable> actions = new ArrayList<>();
        DomainSetup setup = setups.get(card.domain);
        addMenuItem(menu, actions, host.getString(R.string.choice_auto), () -> autoChoice(card));
        if (card.sending) {
            List<String> labels = new ArrayList<>();
            for (SubmitOption option : setup.submitOptions) {
                labels.add(choiceLabel(option));
            }
            for (int index = 0; index < labels.size(); index++) {
                SubmitOption option = setup.submitOptions.get(index);
                // NOTE: two items reading the same in short name their
                // servers, as two submission ports of one host do.
                String label =
                        java.util.Collections.frequency(labels, labels.get(index)) > 1
                                ? submitLabel(option)
                                : labels.get(index);
                addMenuItem(menu, actions, label, () -> fillSending(card, option));
            }
        } else {
            List<String> labels = new ArrayList<>();
            for (SetupOption option : setup.options) {
                labels.add(choiceLabel(option));
            }
            for (int index = 0; index < labels.size(); index++) {
                SetupOption option = setup.options.get(index);
                String label =
                        java.util.Collections.frequency(labels, labels.get(index)) > 1
                                ? optionLabel(option)
                                : labels.get(index);
                addMenuItem(menu, actions, label, () -> fillForm(card, option));
            }
        }
        addMenuItem(menu, actions, host.getString(R.string.signin_custom), () -> openForm(card));
        menu.setOnMenuItemClickListener(
                item -> {
                    actions.get(item.getItemId()).run();
                    cardsChanged = true;
                    showPage();
                    return true;
                });
        menu.show();
    }

    /** One item of a popup menu and what picking it does. */
    private static void addMenuItem(
            android.widget.PopupMenu menu, List<Runnable> actions, String title, Runnable action) {
        menu.getMenu().add(0, actions.size(), actions.size(), title);
        actions.add(action);
    }

    /** A settings item, the protocol and its sign-in: "JMAP · OAuth 2.0". */
    private String choiceLabel(SetupOption option) {
        return option.method == null
                ? option.label
                : option.label + " · " + authName(option.method.type());
    }

    /** A sending item, the protocol and its sign-in: "SMTP · Password". */
    private String choiceLabel(SubmitOption option) {
        return option.auth == null ? option.label : option.label + " · " + authName(option.auth);
    }

    /**
     * Auto picked: the card back on what discovery ranked first, said in a
     * sentence, its form dropped; mail's sending planned again after it.
     */
    private void autoChoice(Card card) {
        DomainSetup setup = setups.get(card.domain);
        int index = cards.indexOf(card);
        if (index < 0) {
            return;
        }
        if (card.sending) {
            setup.submitSelected = SetupPlan.firstOffered(setup.selected, setup.submitOptions);
            Card planned = sendingCard(setup);
            planned.ticked = card.ticked;
            cards.set(index, planned);
            return;
        }
        SetupOption best = SetupPlan.standardOption(card.domain, setup.options, pendingEmail);
        setup.selected = best == null ? manualOption() : best;
        cards.set(index, new Card(card.domain, false, setup.selected.isManual(), card.ticked));
        if (card.domain == PimDomain.MAIL) {
            replanSending();
        }
    }

    /**
     * Whether the form holds an option: IMAP, CardDAV or CalDAV over TLS. A
     * JMAP session or a provider's API has no server to type.
     */
    private static boolean typeable(SetupOption option) {
        String base = option.baseUrl == null ? "" : option.baseUrl;
        if ("imap".equals(option.service)) {
            return base.startsWith("imap");
        }
        return ("carddav".equals(option.service) || "caldav".equals(option.service))
                && base.startsWith("https://");
    }

    /** Whether an option reaches a preset's server, the same way or another. */
    private static boolean samePreset(SetupOption option, SetupOption preset) {
        return preset != null
                && java.util.Objects.equals(option.service, preset.service)
                && java.util.Objects.equals(option.baseUrl, preset.baseUrl);
    }

    /** How an option signs in, a password when it names no method. */
    private static Auth authOf(SetupOption option) {
        return Auth.of(option.method == null ? null : option.method.type());
    }

    /**
     * The custom settings picked: the form, filled from the option in use when
     * it holds it, empty otherwise; a form already typed stays as it is.
     */
    private void openForm(Card card) {
        if (card.form && !card.preset) {
            return;
        }
        DomainSetup setup = setups.get(card.domain);
        if (card.sending && setup.submitSelected != null) {
            fillSending(card, setup.submitSelected);
            return;
        }
        if (!card.sending && typeable(setup.selected)) {
            fillForm(card, setup.selected);
            return;
        }
        ensureForm(card);
        card.filling = true;
        card.server.input.setText("");
        if (card.port != null) {
            card.port.input.setText("");
        }
        setAuth(card, Auth.PASSWORD);
        card.filling = false;
        card.form = true;
        card.preset = false;
        card.from = null;
        card.submitFrom = null;
        card.edited = false;
        layoutForm(card);
        // NOTE: mail typed by hand reads over IMAP, which sends through a
        // server of its own, so sending is planned again.
        if (card.domain == PimDomain.MAIL && !card.sending) {
            setup.selected = manualOption();
            replanSending();
        }
    }

    /**
     * Fills a reading card's form from a discovered option: a server to type
     * for IMAP, CardDAV and CalDAV, one said for a JMAP session or an API.
     */
    private void fillForm(Card card, SetupOption option) {
        ensureForm(card);
        card.filling = true;
        card.preset = !typeable(option);
        card.from = option;
        if (card.preset) {
            card.server.input.setText(
                    option.detail != null ? option.detail : SetupPlan.hostOf(option.baseUrl));
            if (card.port != null) {
                card.port.input.setText("");
            }
        } else if (card.dav()) {
            card.server.input.setText(option.baseUrl);
        } else {
            java.net.URI uri = uriOf(option.baseUrl);
            card.server.input.setText(uri == null || uri.getHost() == null ? "" : uri.getHost());
            card.port.input.setText(
                    uri == null || uri.getPort() < 0 ? "" : String.valueOf(uri.getPort()));
        }
        setAuth(card, authOf(option));
        if (card.auth == Auth.OAUTH) {
            fillClient(card, option);
        }
        card.filling = false;
        card.form = true;
        card.edited = false;
        layoutForm(card);
        setups.get(card.domain).selected = option;
        if (card.domain == PimDomain.MAIL) {
            replanSending();
        }
    }

    /** Fills the sending card's form from a discovered submission server. */
    private void fillSending(Card card, SubmitOption option) {
        ensureForm(card);
        card.filling = true;
        java.net.URI uri = uriOf(option.url);
        card.server.input.setText(uri == null || uri.getHost() == null ? "" : uri.getHost());
        card.port.input.setText(uri == null || uri.getPort() < 0 ? "" : String.valueOf(uri.getPort()));
        setSecurity(card, uri != null && "smtp".equals(uri.getScheme()));
        setAuth(card, Auth.of(option.auth));
        card.filling = false;
        card.form = true;
        card.preset = false;
        card.submitFrom = option;
        card.edited = false;
        layoutForm(card);
        setups.get(PimDomain.MAIL).submitSelected = option;
    }

    /**
     * Fills a reading form's OAuth application from the option it signs in
     * like: the app's own where it has one (its registration at Google and
     * Microsoft, one registered at an issuer), else the user's; the user's
     * fields hold the server's endpoints or issuer, the client to enter.
     */
    private void fillClient(Card card, SetupOption base) {
        AuthMethod method = base == null ? null : base.method;
        boolean filling = card.filling;
        card.filling = true;
        card.byIssuer = method != null && method.type() == AuthMethod.Type.OAUTH_ISSUER;
        boolean endpoints = method != null && !card.byIssuer;
        card.clientId.input.setText("");
        card.clientSecret.input.setText("");
        card.redirect.input.setText(
                card.byIssuer ? Oauth.REDIRECT_URI : "http://127.0.0.1:" + OauthFlow.freePort());
        card.issuer.input.setText(card.byIssuer ? orEmpty(method.issuer()) : "");
        card.authUrl.input.setText(endpoints ? orEmpty(method.authorizationEndpoint()) : "");
        card.tokenUrl.input.setText(endpoints ? orEmpty(method.tokenEndpoint()) : "");
        card.scope.input.setText(endpoints ? orEmpty(method.scope()) : "");
        setApplication(card, !appFits(base));
        card.filling = filling;
    }

    /**
     * Whether the app's own application signs in like an OAuth option: its
     * registration at Google and Microsoft, or one registered at an issuer.
     */
    private static boolean appFits(SetupOption base) {
        if (base == null || base.method == null) {
            return false;
        }
        if (base.method.type() == AuthMethod.Type.OAUTH_ISSUER) {
            return true;
        }
        return shippedClient(base.method.authorizationEndpoint(), base.baseUrl) != null;
    }

    /** The discovered OAuth option a reading form signs in like, or null. */
    private SetupOption oauthBase(Card card) {
        if (card.preset) {
            return card.from != null && card.from.isOauth() ? card.from : null;
        }
        return knownOauth(card);
    }

    /** The app's own client at a provider's OAuth server, or null when none. */
    private static Client shippedClient(String authUrl, String url) {
        Oauth.Shipped shipped = Oauth.shipped(authUrl, url == null ? List.of() : List.of(url));
        if (shipped == Oauth.Shipped.GOOGLE) {
            return new Client(Oauth.GOOGLE_CLIENT_ID, "", Oauth.GOOGLE_REDIRECT_URI);
        }
        if (shipped == Oauth.Shipped.MICROSOFT) {
            return new Client(Oauth.MICROSOFT_CLIENT_ID, "", Oauth.MICROSOFT_REDIRECT_URI);
        }
        return null;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** A URL as a URI, or null when it reads as none. */
    private static java.net.URI uriOf(String url) {
        try {
            return url == null ? null : java.net.URI.create(url);
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }

    /**
     * Mail's option changed: sending starts again from what that option
     * offers, unless its card was set by hand.
     */
    private void replanSending() {
        DomainSetup mail = setups.get(PimDomain.MAIL);
        mail.submitSelected = SetupPlan.firstOffered(mail.selected, mail.submitOptions);
        for (int index = 0; index < cards.size(); index++) {
            Card card = cards.get(index);
            if (card.sending && !(card.form && card.edited)) {
                cards.set(index, sendingCard(mail));
            }
        }
    }

    /** Sets how a card's form signs in, its row saying it. */
    private void setAuth(Card card, Auth auth) {
        card.auth = auth;
        if (card.authValue != null) {
            card.authValue.setText(authName(auth.type));
        }
    }

    /** Sets which OAuth application a reading form runs, its row saying it. */
    private void setApplication(Card card, boolean own) {
        card.ownClient = own;
        if (card.appValue != null) {
            card.appValue.setText(own ? R.string.application_custom : R.string.app_name);
        }
    }

    /** Sets how a sending form secures its connection, its row saying it. */
    private void setSecurity(Card card, boolean starttls) {
        card.starttls = starttls;
        if (card.securityValue != null) {
            card.securityValue.setText(
                    starttls ? R.string.security_starttls : R.string.security_tls);
        }
    }

    /**
     * A sign-in picked in a form's menu: a preset's fills the form from the
     * option found for it; a typed form's makes it custom, OAuth on the app's
     * own application where it has one at the server typed.
     */
    private void pickAuth(Card card, Auth auth) {
        if (auth == card.auth) {
            return;
        }
        if (card.preset) {
            for (SetupOption option : setups.get(card.domain).options) {
                if (samePreset(option, card.from) && authOf(option) == auth) {
                    fillForm(card, option);
                    break;
                }
            }
        } else {
            setAuth(card, auth);
            if (auth == Auth.OAUTH && !card.sending) {
                fillClient(card, knownOauth(card));
            }
            layoutForm(card);
            edited(card);
        }
        cardsChanged = true;
        showPage();
    }

    /**
     * The OAuth application picked in a reading form's menu: the app's own, or
     * Custom, which shows the user's client.
     */
    private void pickApplication(Card card, boolean own) {
        if (own == card.ownClient) {
            return;
        }
        setApplication(card, own);
        layoutForm(card);
        clientEdited(card);
        showPage();
    }

    /** The security picked in a sending form's menu: by hand, custom. */
    private void pickSecurity(Card card, boolean starttls) {
        if (starttls == card.starttls) {
            return;
        }
        setSecurity(card, starttls);
        edited(card);
        showPage();
    }

    /**
     * The ways a card's form may sign in: a preset's, the ones found for its
     * server; for sending, OAuth only when mail signs in through the browser,
     * whose grant then covers it.
     */
    private List<Auth> authsOf(Card card) {
        if (card.preset) {
            List<Auth> auths = new ArrayList<>();
            for (SetupOption option : setups.get(card.domain).options) {
                Auth auth = authOf(option);
                if (samePreset(option, card.from) && !auths.contains(auth)) {
                    auths.add(auth);
                }
            }
            return auths;
        }
        if (!card.sending || setups.get(PimDomain.MAIL).selected.isOauth()) {
            return List.of(Auth.PASSWORD, Auth.TOKEN, Auth.OAUTH);
        }
        return List.of(Auth.PASSWORD, Auth.TOKEN);
    }

    /**
     * Makes a card's form once: a host and a port for mail and sending, the
     * security for sending, a URL for contacts and calendars, how it signs in,
     * then a reading form's OAuth application and the user's client; an edit
     * of the server makes it custom.
     */
    private void ensureForm(Card card) {
        if (card.server != null) {
            return;
        }
        if (card.dav()) {
            card.server = PillField.labeled(host, R.string.field_url, "", false, true);
            card.formViews.add(card.server.view);
        } else {
            card.server = PillField.labeled(host, R.string.custom_server, "", false, true);
            card.port = PillField.labeled(host, R.string.custom_port, "", false, true);
            card.port.input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            card.formViews.add(card.server.view);
            card.formViews.add(card.port.view);
        }
        if (card.sending) {
            card.starttls = false;
            LinearLayout security =
                    menuRow(
                            R.string.field_security,
                            host.getString(R.string.security_tls),
                            row ->
                                    showMenu(
                                            row,
                                            List.of(
                                                    host.getString(R.string.security_starttls),
                                                    host.getString(R.string.security_tls)),
                                            index -> pickSecurity(card, index == 0)));
            card.securityValue = (TextView) security.getChildAt(1);
            card.formViews.add(security);
        }
        LinearLayout auth =
                menuRow(
                        R.string.signin_with_label,
                        authName(card.auth.type),
                        row -> {
                            List<Auth> offered = authsOf(card);
                            List<String> names = new ArrayList<>();
                            for (Auth way : offered) {
                                names.add(authName(way.type));
                            }
                            showMenu(row, names, index -> pickAuth(card, offered.get(index)));
                        });
        card.authValue = (TextView) auth.getChildAt(1);
        card.formViews.add(auth);
        if (card.sending) {
            card.throughMail = noteLine(host.getString(R.string.client_through_mail));
            card.throughMail.setPadding(host.ui.dp(16), host.ui.dp(8), host.ui.dp(16), 0);
            card.formViews.add(card.throughMail);
        } else {
            LinearLayout app =
                    menuRow(
                            R.string.field_application,
                            host.getString(R.string.app_name),
                            row -> {
                                boolean fits = appFits(oauthBase(card));
                                List<String> names = new ArrayList<>();
                                if (fits) {
                                    names.add(host.getString(R.string.app_name));
                                }
                                names.add(host.getString(R.string.application_custom));
                                showMenu(
                                        row,
                                        names,
                                        index -> pickApplication(card, !fits || index > 0));
                            });
            card.appValue = (TextView) app.getChildAt(1);
            card.appRow = app;
            card.formViews.add(app);
            card.clientId = PillField.labeled(host, R.string.field_client_id, "", false, true);
            card.clientSecret =
                    PillField.labeled(host, R.string.field_client_secret, "", true, true);
            card.clientSecret.input.setHint(R.string.field_optional);
            card.authUrl = PillField.labeled(host, R.string.field_auth_url, "", false, true);
            card.tokenUrl = PillField.labeled(host, R.string.field_token_url, "", false, true);
            card.scope = PillField.labeled(host, R.string.field_scope, "", false, true);
            card.issuer = PillField.labeled(host, R.string.field_issuer, "", false, true);
            card.redirect = PillField.labeled(host, R.string.field_redirect, "", false, true);
            for (PillField field :
                    new PillField[] {
                        card.clientId,
                        card.clientSecret,
                        card.issuer,
                        card.authUrl,
                        card.tokenUrl,
                        card.scope,
                        card.redirect
                    }) {
                card.formViews.add(field.view);
                watch(field, () -> clientEdited(card));
            }
        }
        card.error = new TextView(host);
        card.error.setTextSize(13);
        card.error.setTextColor(host.ui.resolveColor(android.R.attr.colorError));
        card.error.setPadding(host.ui.dp(16), host.ui.dp(8), host.ui.dp(16), 0);
        card.error.setVisibility(View.GONE);
        card.formViews.add(card.error);
        for (PillField field : new PillField[] {card.server, card.port}) {
            if (field != null) {
                watch(field, () -> edited(card));
            }
        }
        layoutForm(card);
    }

    /**
     * Shows what a card's form asks: its port unless a preset's, whose server
     * is said rather than typed; while it signs in with OAuth, the application
     * and, for Custom, the user's client, by issuer or by endpoints.
     */
    private void layoutForm(Card card) {
        card.server.input.setEnabled(!card.preset);
        if (card.port != null) {
            card.port.view.setVisibility(card.preset ? View.GONE : View.VISIBLE);
        }
        if (card.dav()) {
            ((TextView) card.server.view.getChildAt(0))
                    .setText(card.preset ? R.string.custom_server : R.string.field_url);
        }
        boolean oauth = card.auth == Auth.OAUTH;
        if (card.sending) {
            card.throughMail.setVisibility(oauth ? View.VISIBLE : View.GONE);
            return;
        }
        card.appRow.setVisibility(oauth ? View.VISIBLE : View.GONE);
        boolean own = oauth && card.ownClient;
        for (PillField field : new PillField[] {card.clientId, card.clientSecret, card.redirect}) {
            field.view.setVisibility(own ? View.VISIBLE : View.GONE);
        }
        card.issuer.view.setVisibility(own && card.byIssuer ? View.VISIBLE : View.GONE);
        for (PillField field : new PillField[] {card.authUrl, card.tokenUrl, card.scope}) {
            field.view.setVisibility(own && !card.byIssuer ? View.VISIBLE : View.GONE);
        }
    }

    /** A card's form changed: by hand it is custom, its error line gone. */
    private void edited(Card card) {
        cardsChanged = true;
        if (card.filling) {
            return;
        }
        card.edited = true;
        if (card.error != null) {
            card.error.setVisibility(View.GONE);
        }
    }

    /**
     * A form's OAuth application or client changed: the pages follow, its
     * error line gone; the option stays the one it was filled from.
     */
    private void clientEdited(Card card) {
        cardsChanged = true;
        if (card.error != null) {
            card.error.setVisibility(View.GONE);
        }
    }

    /**
     * A form row picking its value in a menu, laid out as a field: its name
     * inside it, the value, a chevron; the ripple kept to the pill's ends.
     *
     * @param open shows the menu, anchored on the value given
     */
    private LinearLayout menuRow(
            int label, String value, java.util.function.Consumer<View> open) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.field_on_card);
        row.setPadding(host.ui.dp(24), 0, host.ui.dp(14), 0);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, host.ui.dp(56));
        params.topMargin = host.ui.dp(10);
        row.setLayoutParams(params);
        TypedValue ripple = new TypedValue();
        host.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setForeground(host.getDrawable(ripple.resourceId));
        // NOTE: the ripple keeps to the pill's round ends.
        row.setClipToOutline(true);

        TextView name = new TextView(host);
        name.setText(label);
        name.setTextSize(15);
        name.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
        name.setMinWidth(host.ui.dp(72));
        LinearLayout.LayoutParams nameParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        nameParams.setMarginEnd(host.ui.dp(12));
        row.addView(name, nameParams);

        TextView shown = new TextView(host);
        shown.setText(value);
        shown.setTextSize(17);
        shown.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
        row.addView(shown, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(dropdown());
        row.setOnClickListener(clicked -> open.accept(shown));
        return row;
    }

    /** A menu of plain items under {@code anchor}, one picked by its index. */
    private void showMenu(
            View anchor, List<String> items, java.util.function.IntConsumer picked) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(host, anchor);
        for (int index = 0; index < items.size(); index++) {
            menu.getMenu().add(0, index, index, items.get(index));
        }
        menu.setOnMenuItemClickListener(
                item -> {
                    picked.accept(item.getItemId());
                    return true;
                });
        menu.show();
    }

    /** A card of the servers page or of a domain page, padded and spaced. */
    private LinearLayout cardFrame() {
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
        return card;
    }

    /** A card's header: glyph, name over a line, and an optional button. */
    private LinearLayout cardHeader(int glyph, String name, String line, View trailing) {
        LinearLayout header = CardRows.domainRow(host, glyph, name, line, false, trailing, true);
        header.setPadding(0, 0, 0, host.ui.dp(4));
        header.setMinimumHeight(0);
        header.setForeground(null);
        return header;
    }

    /** A secondary line inside a card. */
    private TextView noteLine(String text) {
        TextView note = new TextView(host);
        note.setText(text);
        note.setTextSize(13);
        note.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
        note.setPadding(host.ui.dp(4), host.ui.dp(4), host.ui.dp(4), host.ui.dp(8));
        return note;
    }

    /** Moves a kept view into {@code parent}, out of its earlier render. */
    private static void attach(LinearLayout parent, View view) {
        if (view.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) view.getParent()).removeView(view);
        }
        parent.addView(view);
    }

    /** Runs {@code changed} after each edit of the field. */
    private void watch(PillField field, Runnable changed) {
        field.input.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        changed.run();
                    }
                });
    }

    /** Where a custom reading form signs in, null when it is not encrypted. */
    private static String customUrl(Card card) {
        if (card.port == null) {
            return Endpoints.manualUrl(card.domain, card.server.text());
        }
        String port = card.port.text();
        return Endpoints.manualUrl(
                card.domain, card.server.text() + (port.isEmpty() ? "" : ":" + port));
    }

    /**
     * Where a custom sending form submits: implicit TLS on 465 or STARTTLS on
     * 587 when no port is typed; null when it reads as no server.
     */
    private static String customSubmitUrl(Card card) {
        boolean starttls = card.starttls;
        String port = card.port.text();
        if (port.isEmpty()) {
            port = starttls ? "587" : "465";
        }
        return Endpoints.submitUrl(
                (starttls ? "smtp://" : "smtps://") + card.server.text() + ":" + port);
    }

    /** What is wrong with a ticked card's form, or null. */
    private String formProblem(Card card) {
        if (!card.preset) {
            if (card.server.text().isEmpty()) {
                return host.getString(R.string.server_required);
            }
            String url = card.sending ? customSubmitUrl(card) : customUrl(card);
            if (url == null) {
                return host.getString(R.string.server_insecure);
            }
        }
        return clientProblem(card);
    }

    /** What is wrong with a reading form's OAuth application, or null. */
    private String clientProblem(Card card) {
        if (card.sending || card.auth != Auth.OAUTH) {
            return null;
        }
        if (!card.ownClient) {
            // NOTE: a server typed after OAuth was picked may have no
            // application of the app's own.
            return appFits(oauthBase(card))
                    ? null
                    : host.getString(R.string.application_unavailable);
        }
        if (card.byIssuer && card.issuer.text().isEmpty()) {
            return host.getString(R.string.issuer_required);
        }
        if (!card.byIssuer && (card.authUrl.text().isEmpty() || card.tokenUrl.text().isEmpty())) {
            return host.getString(R.string.endpoints_required);
        }
        if (card.clientId.text().isEmpty()) {
            return host.getString(R.string.client_required);
        }
        return comesBack(card.redirect.text())
                ? null
                : host.getString(R.string.oauth_redirect_invalid);
    }

    /**
     * Whether a grant comes back to the app at a redirect: a loopback port it
     * listens on, or a scheme of its manifest.
     */
    private static boolean comesBack(String redirect) {
        return redirect.startsWith("http://127.0.0.1:")
                || redirect.equals(Oauth.REDIRECT_URI)
                || redirect.equals(Oauth.GOOGLE_REDIRECT_URI)
                || redirect.equals(Oauth.MICROSOFT_REDIRECT_URI);
    }

    /**
     * The user's own client a reading form signs in with, as typed; null for
     * the app's own application.
     */
    private static Client clientOf(Card card) {
        if (!card.ownClient) {
            return null;
        }
        return new Client(card.clientId.text(), card.clientSecret.text(), card.redirect.text());
    }

    /** The OAuth server a reading form names: its issuer, or its endpoints. */
    private static AuthMethod typedMethod(Card card) {
        if (card.byIssuer) {
            return AuthMethod.issuer(card.issuer.text());
        }
        String scope = card.scope.text();
        return AuthMethod.codeGrant(
                card.authUrl.text(), card.tokenUrl.text(), scope.isEmpty() ? null : scope);
    }

    /**
     * An option signing in at the OAuth server its form names: the option
     * itself while the form keeps its issuer, or its endpoints and scope.
     */
    private static SetupOption typedServer(Card card, SetupOption option) {
        AuthMethod typed = typedMethod(card);
        AuthMethod found = option.method;
        boolean same =
                found != null
                        && found.type() == typed.type()
                        && orEmpty(found.issuer()).equals(orEmpty(typed.issuer()))
                        && orEmpty(found.authorizationEndpoint())
                                .equals(orEmpty(typed.authorizationEndpoint()))
                        && orEmpty(found.tokenEndpoint()).equals(orEmpty(typed.tokenEndpoint()))
                        && orEmpty(found.scope()).equals(orEmpty(typed.scope()));
        if (same) {
            return option;
        }
        return new SetupOption(
                option.service,
                option.label,
                option.detail,
                option.baseUrl,
                option.resource,
                typed,
                option.login);
    }

    /**
     * What each ticked card's form adds up to: the option it was filled from
     * while untouched, what was typed otherwise; OAuth with the user's own
     * client at the server its fields name. Sending unticked does not send.
     */
    private void resolveForms() {
        for (Card card : cards) {
            DomainSetup setup = setups.get(card.domain);
            if (!card.sending) {
                setup.client = null;
            } else if (!counts(card)) {
                setup.submitSelected = null;
                continue;
            }
            if (!card.form || !counts(card)) {
                continue;
            }
            if (!card.sending) {
                SetupOption option =
                        !card.edited && card.from != null ? card.from : typedOption(card);
                if (card.auth == Auth.OAUTH && card.ownClient) {
                    option = typedServer(card, option);
                    setup.client = clientOf(card);
                }
                setup.selected = option;
            } else {
                setup.submitSelected =
                        !card.edited && card.submitFrom != null
                                ? card.submitFrom
                                : typedSubmit(card);
            }
        }
    }

    /**
     * The option a hand-filled reading form adds up to; OAuth as a discovered
     * option of the same server, or at the server the user's client fields
     * name, for the resource that option asks.
     */
    private SetupOption typedOption(Card card) {
        String url = customUrl(card);
        String service =
                card.domain == PimDomain.MAIL
                        ? "imap"
                        : card.domain == PimDomain.CONTACTS ? "carddav" : "caldav";
        String server = SetupPlan.hostOf(url);
        AuthMethod method;
        String resource = null;
        if (card.auth == Auth.OAUTH) {
            SetupOption known = knownOauth(card);
            method = card.ownClient || known == null ? typedMethod(card) : known.method;
            resource = known == null ? null : known.resource;
        } else {
            method = card.auth == Auth.TOKEN ? AuthMethod.bearer() : AuthMethod.password();
        }
        return new SetupOption(service, protocolName(service), server, url, resource, method, null);
    }

    /**
     * The OAuth option a typed reading form signs in like: the one it was
     * filled from, else one found at the server typed; null for none.
     */
    private SetupOption knownOauth(Card card) {
        String url = customUrl(card);
        String server = url == null ? null : SetupPlan.hostOf(url);
        if (server == null) {
            return null;
        }
        if (card.from != null
                && card.from.isOauth()
                && server.equalsIgnoreCase(SetupPlan.hostOf(card.from.baseUrl))) {
            return card.from;
        }
        for (SetupOption option : setups.get(card.domain).options) {
            if (option.isOauth() && server.equalsIgnoreCase(SetupPlan.hostOf(option.baseUrl))) {
                return option;
            }
        }
        return null;
    }

    /**
     * The submission server a hand-filled sending form adds up to, OAuth with
     * the scope a discovered option of the same server asks.
     */
    private SubmitOption typedSubmit(Card card) {
        String url = customSubmitUrl(card);
        String server = SetupPlan.hostOf(url);
        String scope = null;
        if (card.auth == Auth.OAUTH) {
            for (SubmitOption known : setups.get(PimDomain.MAIL).submitOptions) {
                if (known.scope != null && server.equalsIgnoreCase(SetupPlan.hostOf(known.url))) {
                    scope = known.scope;
                    break;
                }
            }
        }
        return new SubmitOption(
                host.getString(R.string.config_smtp), server, url, false, scope, null, card.auth.type);
    }

    /**
     * Continue on the servers page: the ticked forms checked and read, then
     * the domain pages, built again when a card changed since.
     */
    private void serversContinue() {
        boolean ready = true;
        for (Card card : cards) {
            if (!card.form || !counts(card)) {
                continue;
            }
            ensureForm(card);
            String problem = formProblem(card);
            card.error.setText(problem == null ? "" : problem);
            card.error.setVisibility(problem == null ? View.GONE : View.VISIBLE);
            ready &= problem == null;
        }
        if (!ready) {
            return;
        }
        resolveForms();
        if (cardsChanged || pages.isEmpty()) {
            buildPages();
            cardsChanged = false;
        }
        pageAt = -1;
        nextPage();
    }

    /**
     * The domain pages the ticked cards add up to, then the sign-ins they
     * need.
     */
    private void buildPages() {
        pages.clear();
        oauthGroup.clear();
        for (DomainSetup setup : setups.values()) {
            setup.credential = null;
            setup.submitCredential = null;
            setup.baseUrl = null;
        }
        for (Card card : cards) {
            Page page = counts(card) ? pageOf(card) : null;
            if (page != null) {
                pages.add(page);
            }
        }
        identities.clear();
        buildIdentities();
    }

    /**
     * Groups the pages into sign-ins, in their order: a password sign-in
     * after the first is linked to an earlier one, to reuse its login and
     * password. A sign-in already made keeps its page's state and fields.
     */
    private void buildIdentities() {
        Map<Page, Identity> before = new HashMap<>();
        for (Identity identity : identities) {
            before.put(identity.lead(), identity);
        }
        identities.clear();
        List<Identity> passwords = new ArrayList<>();
        for (Page page : pages) {
            Identity joined = null;
            for (Identity identity : identities) {
                if (together(identity, page)) {
                    joined = identity;
                    break;
                }
            }
            if (joined == null) {
                joined = new Identity(page.signIn);
                identities.add(joined);
            }
            joined.pages.add(page);
            if (joined.signIn == SignIn.PASSWORD && joined.pages.size() == 1) {
                joined.linked = linkOf(joined, passwords);
                passwords.add(joined);
            }
        }
        for (Identity identity : identities) {
            Identity old = before.get(identity.lead());
            if (old != null && old.signIn == identity.signIn) {
                identity.shown = old.shown;
                identity.login = old.login;
                identity.secret = old.secret;
                identity.same = old.same;
                identity.status = old.status;
            }
        }
    }

    /**
     * Whether a page signs in with a sign-in: under the same browser grant,
     * or sending through mail's sign-in beside mail. A password or token is
     * a sign-in of its own, mail's and sending's included.
     */
    private boolean together(Identity identity, Page page) {
        if (page.signIn == SignIn.MAIL) {
            return identity.pages.contains(readingPage(PimDomain.MAIL));
        }
        return page.signIn == SignIn.BROWSER
                && identity.signIn == SignIn.BROWSER
                && sameGrant(page, identity.lead());
    }

    /**
     * The earlier password sign-in one may reuse: the one whose server bears
     * the same name, else the first; null for the first.
     */
    private static Identity linkOf(Identity identity, List<Identity> earlier) {
        for (Identity before : earlier) {
            if (sameServer(before.lead(), identity.lead())) {
                return before;
            }
        }
        return earlier.isEmpty() ? null : earlier.get(0);
    }

    /**
     * A sign-in page's title: the provider of a browser grant, else the
     * domain it signs in.
     */
    private String identityTitle(Identity identity) {
        if (identity.signIn == SignIn.BROWSER) {
            return identityName(identity);
        }
        Page lead = identity.lead();
        return nameOf(lead.sending, lead.domain);
    }

    /** A sign-in's sending through mail's sign-in, signed once mail is. */
    private static void settle(Identity identity) {
        for (Page page : identity.pages) {
            if (page.signIn != SignIn.MAIL && !page.signed) {
                return;
            }
        }
        for (Page page : identity.pages) {
            page.signed = true;
        }
    }

    /** A card's page, or null for sending with no server: no sending. */
    private Page pageOf(Card card) {
        DomainSetup setup = setups.get(card.domain);
        if (!card.sending) {
            Page page = pageOf(card.domain, setup.selected);
            page.client = setup.client;
            return page;
        }
        if (sendsOverSession(setup) || sendsThroughGrant(setup)) {
            return new Page(PimDomain.MAIL, true, SignIn.MAIL, null, null);
        }
        SubmitOption submit = setup.submitSelected;
        if (submit == null) {
            return null;
        }
        SignIn signIn = Auth.of(submit.auth) == Auth.TOKEN ? SignIn.TOKEN : SignIn.PASSWORD;
        return new Page(PimDomain.MAIL, true, signIn, submit.url, null);
    }

    /** A reading page signing in with an option. */
    private static Page pageOf(PimDomain domain, SetupOption option) {
        if (option.isOauth()) {
            return new Page(domain, false, SignIn.BROWSER, option.baseUrl, option);
        }
        SignIn signIn =
                option.method != null && option.method.type() == AuthMethod.Type.BEARER
                        ? SignIn.TOKEN
                        : SignIn.PASSWORD;
        return new Page(domain, false, signIn, option.baseUrl, null);
    }

    /**
     * Whether two pages sign in at servers of one name, their service label
     * dropped (imap.example.org and dav.example.org): one password covers
     * both, never a server of another name.
     */
    private static boolean sameServer(Page left, Page right) {
        return SetupPlan.serverName(left.url).equalsIgnoreCase(SetupPlan.serverName(right.url));
    }

    /**
     * Shows the servers page, the sign-in page at {@link #pageAt} or the
     * settings page after the last, on the sign-in step, its Continue in line.
     */
    private void showPage() {
        ((TextView) host.findViewById(R.id.signin_line)).setText(pendingEmail);
        TextView title = host.findViewById(R.id.signin_title);
        TextView message = host.findViewById(R.id.signin_message);
        LinearLayout container = host.findViewById(R.id.signin_container);
        container.removeAllViews();
        Button onward = host.findViewById(R.id.signin_continue);
        if (pageAt < 0) {
            title.setText(R.string.servers_title);
            message.setText(host.getString(R.string.servers_found, domainOf(pendingEmail)));
            message.setVisibility(View.VISIBLE);
            // NOTE: one card, a section per domain under a hairline inset
            // past the tiles.
            LinearLayout group = CardRows.card(host);
            int inset =
                    host.ui.dp(28)
                            + host.getResources().getDimensionPixelSize(R.dimen.domain_tile);
            for (Card card : cards) {
                if (group.getChildCount() > 0) {
                    group.addView(CardRows.rowDivider(host, inset));
                }
                group.addView(cardView(card));
            }
            container.addView(group);
            onward.setText(R.string.email_submit);
        } else if (pageAt >= identities.size()) {
            title.setText(R.string.settings_review_title);
            message.setText(R.string.settings_review_message);
            message.setVisibility(View.VISIBLE);
            // NOTE: one card as on the domains page, a section per domain
            // under a hairline inset past the tiles.
            LinearLayout group = CardRows.card(host);
            int inset =
                    host.ui.dp(28)
                            + host.getResources().getDimensionPixelSize(R.dimen.domain_tile);
            for (Page page : pages) {
                if (group.getChildCount() > 0) {
                    group.addView(CardRows.rowDivider(host, inset));
                }
                group.addView(settingsCard(page));
            }
            container.addView(group);
            onward.setText(R.string.books_finish);
        } else {
            Identity identity = identities.get(pageAt);
            identity.shown = true;
            title.setText(identityTitle(identity));
            // NOTE: the domains a browser grant covers; a password page is
            // one domain's, its title.
            message.setText(host.getString(R.string.identity_for, domainNames(identity)));
            message.setVisibility(
                    identity.signIn == SignIn.BROWSER ? View.VISIBLE : View.GONE);
            container.addView(signInCard(identity));
            onward.setText(R.string.email_submit);
        }
        // NOTE: a page shown again for a change on it keeps its scroll.
        if (shownAt != pageAt) {
            host.findViewById(R.id.signin_scroll).scrollTo(0, 0);
            shownAt = pageAt;
        }
        if (shell.authStep() != STEP_SIGNIN) {
            shell.showAuth(STEP_SIGNIN);
        }
        resetConfigContinue();
    }

    /** Where a page signs in, as its card names it: provider or host. */
    private String pageServer(Page page) {
        SetupOption option =
                page.signIn == SignIn.MAIL ? setups.get(PimDomain.MAIL).selected : page.option;
        if (option != null) {
            String provider = SetupPlan.provider(option, pendingEmail);
            if (provider != null) {
                return provider;
            }
            if (page.signIn == SignIn.MAIL) {
                return SetupPlan.serverName(option);
            }
        }
        return SetupPlan.hostOf(page.url);
    }

    /**
     * A sign-in's name, its page's title: the provider or server of a browser
     * grant, the name the servers of a password or token share.
     */
    private String identityName(Identity identity) {
        Page lead = identity.lead();
        if (identity.signIn == SignIn.BROWSER || identity.signIn == SignIn.MAIL) {
            return pageServer(lead);
        }
        return SetupPlan.serverName(lead.url);
    }

    /** The domains a sign-in covers, by name. */
    private String domainNames(Identity identity) {
        List<String> names = new ArrayList<>();
        for (Page page : identity.pages) {
            names.add(nameOf(page.sending, page.domain));
        }
        return String.join(", ", names);
    }

    /**
     * Where a sign-in signs in, as its card names it: the provider, or every
     * host its password or token goes to.
     */
    private String identityServers(Identity identity) {
        if (identity.signIn == SignIn.BROWSER || identity.signIn == SignIn.MAIL) {
            return pageServer(identity.lead());
        }
        java.util.Set<String> hosts = new java.util.LinkedHashSet<>();
        for (Page page : identity.pages) {
            if (page.signIn != SignIn.MAIL) {
                hosts.add(SetupPlan.hostOf(page.url));
            }
        }
        return String.join(", ", hosts);
    }

    /** How a sign-in signs in, or that it did, under its servers. */
    private String identityHow(Identity identity) {
        if (identity.signed()) {
            return host.getString(R.string.signin_done);
        }
        switch (identity.signIn) {
            case PASSWORD:
                return host.getString(R.string.signin_with_password);
            case TOKEN:
                return host.getString(R.string.signin_with_token);
            case BROWSER:
                return sentence(identity.lead().option);
            default:
                return host.getString(R.string.signin_through, nameOf(false, PimDomain.MAIL));
        }
    }

    /**
     * What a sign-in's last try ran into, said on its card; null when none.
     * The domains it failed for are named when it covers several.
     */
    private String identityProblem(Identity identity) {
        List<String> names = new ArrayList<>();
        String problem = null;
        int asking = 0;
        for (Page page : identity.pages) {
            if (page.signIn == SignIn.MAIL) {
                continue;
            }
            asking++;
            if (!page.signed && page.problem != null) {
                names.add(nameOf(page.sending, page.domain));
                problem = page.problem;
            }
        }
        if (problem == null) {
            return null;
        }
        if (identity.signIn == SignIn.BROWSER) {
            return host.getString(R.string.signin_not_allowed_for, String.join(", ", names));
        }
        return asking > 1
                ? host.getString(R.string.signin_problem_for, problem, String.join(", ", names))
                : problem;
    }

    /**
     * A sign-in's card: where it signs in, how, and what that needs; folded
     * to its header once signed in.
     */
    private View signInCard(Identity identity) {
        if (identity.status == null) {
            identity.status = new TextView(host);
            identity.status.setTextSize(13);
            identity.status.setTextColor(host.ui.resolveColor(android.R.attr.colorError));
            identity.status.setPadding(host.ui.dp(16), host.ui.dp(8), host.ui.dp(16), 0);
        }
        boolean signed = identity.signed();
        LinearLayout card = cardFrame();
        int glyph = signed ? R.drawable.ic_check : R.drawable.ic_account;
        card.addView(cardHeader(glyph, identityServers(identity), identityHow(identity), null));
        if (!signed) {
            if (identity.signIn == SignIn.PASSWORD || identity.signIn == SignIn.TOKEN) {
                if (identity.secret == null) {
                    makeFields(identity);
                }
                if (identity.same != null) {
                    attach(card, (View) identity.same.getParent());
                }
                if (identity.login != null) {
                    attach(card, identity.login.view);
                }
                attach(card, identity.secret.view);
                showFields(identity);
            } else if (identity.signIn == SignIn.BROWSER) {
                card.addView(
                        noteLine(
                                host.getString(
                                        R.string.signin_browser_opens,
                                        pageServer(identity.lead()))));
            }
        }
        String problem = signed ? null : identityProblem(identity);
        identity.status.setText(problem == null ? "" : problem);
        identity.status.setVisibility(problem == null ? View.GONE : View.VISIBLE);
        attach(card, identity.status);
        return card;
    }

    /**
     * Makes a sign-in's fields once: a token, or a login and a password, the
     * switch reusing an earlier sign-in's login and password before them.
     */
    private void makeFields(Identity identity) {
        Runnable edited =
                () -> {
                    for (Page page : identity.pages) {
                        page.problem = null;
                    }
                    identity.status.setVisibility(View.GONE);
                    refreshContinue();
                };
        if (identity.signIn == SignIn.TOKEN) {
            identity.secret = PillField.labeled(host, R.string.field_token, "", true, true);
            watch(identity.secret, edited);
            return;
        }
        String hint = null;
        for (Page page : identity.pages) {
            if (hint == null && !page.sending) {
                hint = setups.get(page.domain).selected.login;
            }
        }
        String login = hint != null && !hint.isEmpty() ? hint : emailLogin();
        identity.login = PillField.labeled(host, R.string.custom_login, login, false, true);
        identity.secret = PillField.labeled(host, R.string.password_hint, "", true, true);
        watch(identity.login, edited);
        watch(identity.secret, edited);
        if (identity.linked != null) {
            // NOTE: on for a server of the linked one's name (imap.example.org
            // and smtp.example.org), off for another, so a password never goes
            // to a name it was not typed for unasked.
            identity.same = Switches.of(host);
            identity.same.setChecked(sameServer(identity.lead(), identity.linked.lead()));
            identity.same.setOnCheckedChangeListener(
                    (view, checked) -> {
                        showFields(identity);
                        edited.run();
                    });
            switchLine(
                    host.getString(R.string.signin_same, identityTitle(identity.linked)),
                    identity.same);
        }
    }

    /** A sign-in's fields shown unless its switch reuses another's. */
    private static void showFields(Identity identity) {
        int shown = identity.reuses() ? View.GONE : View.VISIBLE;
        if (identity.login != null) {
            identity.login.view.setVisibility(shown);
        }
        identity.secret.view.setVisibility(shown);
    }

    /**
     * Whether one browser grant signs both pages in: the same authorization
     * server and audience, through the same client.
     */
    private static boolean sameGrant(Page other, Page page) {
        return other.signIn == SignIn.BROWSER
                && (other == page
                        || (SetupPlan.sameSignIn(other.option, page.option)
                                && java.util.Objects.equals(other.client, page.client)));
    }

    /**
     * A domain's section of the settings page: its tile and name, then the
     * rows of the account settings.
     */
    private View settingsCard(Page page) {
        Sections sections = new Sections(host, new LinearLayout(host));
        List<View> rows = new ArrayList<>();
        if (page.sending) {
            rows.add(
                    sections.row(
                            host.getString(R.string.sender_name),
                            chosenName.isEmpty()
                                    ? host.getString(R.string.sender_name_none)
                                    : chosenName,
                            chevron(),
                            this::pickName));
        } else if (page.domain == PimDomain.MAIL) {
            rows.add(
                    switchRow(
                            sections,
                            R.string.background_notify,
                            R.string.notify_mail_note,
                            notifySwitch()));
            rows.add(
                    sections.row(
                            host.getString(R.string.subscribed_mailboxes),
                            chosenMailboxes == null
                                    ? host.getString(R.string.mailboxes_default)
                                    : host.getString(
                                            R.string.subscribed_of,
                                            chosenMailboxes.size(),
                                            mailboxesTotal),
                            chevron(),
                            () -> pickMailboxes(page)));
        } else if (page.domain == PimDomain.CONTACTS) {
            rows.add(
                    switchRow(
                            sections,
                            R.string.phone_contacts,
                            R.string.phone_contacts_note,
                            mirrorSwitch(PhoneMirror.CONTACTS)));
            rows.add(
                    sections.row(
                            host.getString(R.string.subscribed_addressbooks),
                            chosenBooks == null
                                    ? host.getString(R.string.subscribed_all)
                                    : host.getString(
                                            R.string.subscribed_of, chosenBooks.size(), booksTotal),
                            chevron(),
                            () -> pickBooks(page)));
        } else {
            rows.add(
                    switchRow(
                            sections,
                            R.string.phone_calendar,
                            R.string.phone_calendar_note,
                            mirrorSwitch(PhoneMirror.CALENDAR)));
            rows.add(
                    sections.row(
                            host.getString(R.string.subscribed_calendars),
                            chosenCalendars == null || listedCalendars == null
                                    ? host.getString(R.string.subscribed_all)
                                    : host.getString(
                                            R.string.subscribed_of,
                                            chosenCalendars.size(),
                                            listedCalendars.size()),
                            chevron(),
                            () -> pickCalendars(page)));
        }

        // NOTE: a section of the page's one card, led by its domain's tile as
        // the domains page draws it, picked.
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        int glyph = page.sending ? R.drawable.ic_send : page.domain.icon;
        LinearLayout header =
                CardRows.tileRow(host, true, glyph, nameOf(page.sending, page.domain), null, null);
        header.setForeground(null);
        card.addView(header);
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) {
                View line = new View(host);
                line.setBackgroundColor(host.getColor(R.color.divider_light));
                LinearLayout.LayoutParams lineParams =
                        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
                lineParams.setMarginStart(host.ui.dp(16));
                card.addView(line, lineParams);
            }
            card.addView(rows.get(index));
        }
        if (page.domain == PimDomain.CALENDAR && !takesTasks(page.url)) {
            TextView note = noteLine(host.getString(R.string.onboarding_no_tasks));
            note.setPadding(host.ui.dp(16), 0, host.ui.dp(16), host.ui.dp(12));
            card.addView(note);
        }
        return card;
    }

    /** A row ending in a switch, the row as a whole toggling it. */
    private View switchRow(Sections sections, int title, int line, Switch toggle) {
        LinearLayout row =
                (LinearLayout)
                        sections.row(
                                host.getString(title), host.getString(line), null, toggle::toggle);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(host.ui.dp(12));
        row.addView(toggle, params);
        return row;
    }

    /** The trailing chevron of a row opening a choice. */
    private View chevron() {
        android.widget.ImageView glyph = new android.widget.ImageView(host);
        glyph.setImageResource(R.drawable.ic_chevron_right);
        glyph.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        host.ui.resolveColor(android.R.attr.textColorSecondary)));
        return glyph;
    }

    /** The trailing arrow of a row opening a menu. */
    private View dropdown() {
        android.widget.ImageView glyph = new android.widget.ImageView(host);
        glyph.setImageResource(R.drawable.ic_expand_more);
        glyph.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        host.ui.resolveColor(android.R.attr.textColorSecondary)));
        return glyph;
    }

    /**
     * The switch notifying the account's new mail; turned on, it asks the
     * notifications permission, a refusal turning it back off.
     */
    private Switch notifySwitch() {
        Switch toggle = Switches.of(host);
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
        return toggle;
    }

    /**
     * The switch showing the account on the phone; turned on, it asks the
     * mirror's permissions, a refusal turning it back off.
     */
    private Switch mirrorSwitch(PhoneMirror mirror) {
        Switch toggle = Switches.of(host);
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
        return toggle;
    }

    /** Asks for the name the account's mail goes out under. */
    private void pickName() {
        PillField field = PillField.of(host, R.string.sender_name, chosenName, false, false);
        field.input.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                        | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.input.setTypeface(android.graphics.Typeface.DEFAULT);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(host);
        frame.setPadding(host.ui.dp(20), host.ui.dp(12), host.ui.dp(20), 0);
        frame.addView(field.view);
        new AlertDialog.Builder(host)
                .setTitle(R.string.sender_name)
                .setView(frame)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> {
                            chosenName = field.text().trim();
                            showPage();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The mailboxes to subscribe, listed from Mail's signed-in server. */
    private void pickMailboxes(Page page) {
        if (checking == null && page.signed) {
            listMailboxes(serverOf(page), this::openMailboxSelection);
        }
    }

    /** The addressbooks to subscribe, listed from the signed-in server. */
    private void pickBooks(Page page) {
        if (checking == null && page.signed) {
            listBooks(serverOf(page), this::openBooksSelection);
        }
    }

    /** The calendars to subscribe, listed from the signed-in server. */
    private void pickCalendars(Page page) {
        if (checking == null && page.signed) {
            listCalendars(serverOf(page), this::openCalendarsSelection);
        }
    }

    /** The server a signed-in page reads from, as the account will. */
    private Account serverOf(Page page) {
        DomainSetup setup = setups.get(page.domain);
        AccountCommit.Connection connection =
                new AccountCommit.Connection(page.domain, setup.baseUrl, null, setup.credential);
        return AccountCommit.assemble(pendingEmail, List.of(connection)).server(page.domain);
    }

    /**
     * The sign-in step's Continue: the servers page's, a sign-in page's, or
     * the settings page's Finish, which saves the account.
     */
    private void pageContinue() {
        host.hideKeyboard();
        if (checking != null) {
            return;
        }
        if (pageAt < 0) {
            serversContinue();
            return;
        }
        if (pageAt >= identities.size()) {
            busy();
            commitConnections();
            return;
        }
        signIn(identities.get(pageAt));
    }

    /**
     * The next sign-in asking something, else the settings page; a sign-in
     * already made is passed.
     *
     * <p>A lone browser grant asks nothing but a trip to the browser: it runs
     * from the page shown, its own page shown only when the grant left
     * something to ask again.
     */
    private void nextPage() {
        int next = pageAt + 1;
        while (next < identities.size()) {
            Identity identity = identities.get(next);
            settle(identity);
            if (!identity.signed()) {
                break;
            }
            next++;
        }
        if (next < identities.size() && direct(identities.get(next))) {
            signIn(identities.get(next));
            return;
        }
        pageAt = next;
        showPage();
    }

    /**
     * Whether a sign-in runs without a page of its own: the account's only
     * one, a browser grant with nothing to say yet.
     */
    private boolean direct(Identity identity) {
        if (identities.size() != 1 || identity.signIn != SignIn.BROWSER) {
            return false;
        }
        for (Page page : identity.pages) {
            if (page.problem != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Signs a sign-in in, then the next page: its browser grant run, or its
     * password or token tried at every server it covers the way they will
     * sync.
     */
    private void signIn(Identity identity) {
        settle(identity);
        if (identity.signed()) {
            nextPage();
            return;
        }
        checking = identity;
        if (identity.signIn == SignIn.BROWSER) {
            host.setAuthLoading(R.id.signin_continue, R.id.signin_progress, true);
            startGrant(identity.lead());
            return;
        }
        probe(identity);
    }

    /**
     * Tries a sign-in's password or token at each server it covers not
     * signed in yet, one after the other, the login and password of the
     * sign-in it reuses when switched so.
     */
    private void probe(Identity identity) {
        Identity source = identity;
        // NOTE: a sign-in links only to one before it, so the walk ends.
        while (source.reuses()) {
            source = source.linked;
        }
        String login = identity.signIn == SignIn.PASSWORD ? source.login.text() : "";
        String secret = source.secret.input.getText().toString();
        List<Page> left = new ArrayList<>();
        for (Page page : identity.pages) {
            if (!page.signed && page.signIn != SignIn.MAIL) {
                page.problem = null;
                left.add(page);
            }
        }
        if (identity.status != null) {
            identity.status.setVisibility(View.GONE);
        }
        host.setAuthLoading(R.id.signin_continue, R.id.signin_progress, true);
        new Thread(
                        () -> {
                            Map<Page, Exception> failures = new HashMap<>();
                            for (Page page : left) {
                                // NOTE: a token signs in to IMAP and SMTP with
                                // SASL XOAUTH2, which names its user in the
                                // URL, as the stored account will.
                                String url =
                                        page.domain == PimDomain.MAIL && login.isEmpty()
                                                ? Endpoints.asUser(page.url, pendingEmail)
                                                : page.url;
                                Exception failure =
                                        page.sending
                                                ? probeSending(new Account(url, login, secret, url))
                                                : probe(page.domain, new Account(url, login, secret));
                                if (failure != null) {
                                    failures.put(page, failure);
                                }
                            }
                            // NOTE: not postAlive: what it leads to may persist
                            // the account (finishOnboarding), which a destroyed
                            // activity must not drop.
                            host.main.post(() -> probed(identity, left, login, secret, failures));
                        },
                        "onboarding-sign-in")
                .start();
    }

    /**
     * A sign-in's outcome, back on the main thread: the servers that took it
     * signed in, a refusal said for the others, the page staying for them.
     */
    private void probed(
            Identity identity,
            List<Page> tried,
            String login,
            String secret,
            Map<Page, Exception> failures) {
        if (checking != identity) {
            return;
        }
        checking = null;
        host.setAuthLoading(R.id.signin_continue, R.id.signin_progress, false);
        // NOTE: a reused login and password a server refused: the sign-in
        // takes its own, its fields shown; first, as turning the switch off
        // clears what the fields said.
        if (!failures.isEmpty() && identity.reuses()) {
            identity.same.setChecked(false);
        }
        AccountCredential credential = AccountCredential.password(login, secret);
        for (Page page : tried) {
            Exception failure = failures.get(page);
            if (failure == null) {
                page.signed = true;
                DomainSetup setup = setups.get(page.domain);
                if (page.sending) {
                    setup.submitCredential = credential;
                } else {
                    setup.baseUrl = page.url;
                    setup.credential = credential;
                }
            } else if (failure instanceof NotServed) {
                page.problem = failure.getMessage();
            } else if (!wrongPassword(failure)) {
                page.problem = host.getString(R.string.connect_failed);
            } else {
                page.problem =
                        host.getString(
                                identity.signIn == SignIn.TOKEN
                                        ? R.string.token_refused
                                        : R.string.password_wrong);
            }
        }
        settle(identity);
        if (identity.signed()) {
            nextPage();
            return;
        }
        showPage();
    }

    /**
     * The sign-in step's Continue in line with its page: a domain picked on
     * the servers page, the fields filled on a sign-in page.
     */
    private void refreshContinue() {
        if (shell.authStep() != STEP_SIGNIN || checking != null) {
            return;
        }
        boolean ready = true;
        if (pageAt < 0) {
            ready = false;
            for (DomainSetup setup : setups.values()) {
                ready |= setup.enabled;
            }
        } else if (pageAt < identities.size()) {
            Identity identity = identities.get(pageAt);
            if (!identity.signed() && identity.signIn == SignIn.PASSWORD) {
                ready =
                        identity.reuses()
                                || (filled(identity.login) && filled(identity.secret));
            } else if (!identity.signed() && identity.signIn == SignIn.TOKEN) {
                ready = filled(identity.secret);
            }
        }
        host.setFabEnabled(R.id.signin_continue, ready);
    }

    private static boolean filled(PillField field) {
        return field != null && !field.text().isEmpty();
    }

    /**
     * Runs one browser grant for the page and every page of its authorization
     * server and audience through the same client, the scopes merged.
     *
     * <p>Signed pages too: a grant asked again for a permission left unticked
     * lands on them all, one credential for the account. Through the user's
     * own client when the servers page names one, else the app's registration
     * at Google and Microsoft, or dynamic registration at an issuer.
     */
    private void startGrant(Page page) {
        SetupOption option = page.option;
        oauthGroup.clear();
        grantNeeds.clear();

        Set<String> scopes = new java.util.LinkedHashSet<>();
        List<String> endpoints = new ArrayList<>();
        List<Page> group = new ArrayList<>();
        for (Page other : pages) {
            if (sameGrant(other, page)) {
                group.add(other);
            }
        }
        for (Page member : group) {
            DomainSetup setup = setups.get(member.domain);
            oauthGroup.put(member.domain, member.url);
            endpoints.add(member.url);
            SetupPlan.addScopes(scopes, member.option.method.scope());
            // NOTE: submission signs in with mail's credential, so the grant
            // that reads mail has to cover sending it too.
            if (member.domain == PimDomain.MAIL
                    && sends(setup)
                    && setup.submitSelected != null
                    && setup.submitSelected.url != null) {
                SetupPlan.addScopes(scopes, setup.submitSelected.scope);
                endpoints.add(setup.submitSelected.url);
            }
        }

        String scope = scopes.isEmpty() ? null : String.join(" ", scopes);
        String heading =
                nameOf(page.sending, page.domain)
                        + " · "
                        + (option.detail != null ? option.detail : SetupPlan.hostOf(option.baseUrl));
        // NOTE: the resource, never the base URL. A JMAP account's base is the
        // internal jmap:// marker and a mailbox's is imaps://, and an
        // authorization server asked to issue a token for either answers
        // invalid_target: neither is a URI it protects.
        OauthFlow.Target target = new OauthFlow.Target(pendingEmail, option.resource);
        if (page.client != null) {
            OauthRequest request =
                    new OauthRequest(
                            page.client.id(), page.client.secret(), page.client.redirect(), scope);
            if (option.method.type() == AuthMethod.Type.OAUTH_ISSUER) {
                oauth.startIssuerOauth(
                        target, option.method.issuer(), grantedDomains(), heading, request);
            } else {
                oauth.startClientOauth(
                        target,
                        new OauthFlow.AuthServer(
                                option.method.authorizationEndpoint(),
                                option.method.tokenEndpoint()),
                        request,
                        null);
            }
            return;
        }
        // NOTE: Google and Microsoft register no client dynamically, so their
        // grants run with the app's own registration, and only for their own
        // hosts: any other falls to a generic grant (Oauth.shipped).
        Oauth.Shipped shipped = Oauth.shipped(option.method.authorizationEndpoint(), endpoints);
        if (shipped == Oauth.Shipped.GOOGLE) {
            // NOTE: a domain added onto an account Google already granted
            // asks for the union, so the one new grant replaces the old one
            // on every domain rather than living beside it.
            AccountEntry existing = existingAccount(pendingEmail);
            if (existing != null) {
                scope = Oauth.union(existing.grantedScope(Oauth.GOOGLE_CLIENT_ID), scope);
            }
            String asked = Oauth.fixedScope(shipped, Oauth.withTasks(shipped, scope));
            // NOTE: Google's consent page lets each permission be unticked,
            // so a domain is signed in only when its scopes come back.
            Set<String> fixed = scopeSet(asked);
            for (Page member : group) {
                Set<String> needs = scopeSet(member.option.method.scope());
                needs.retainAll(fixed);
                grantNeeds.put(member.domain, needs);
            }
            oauth.startGoogleOauth(pendingEmail, asked);
            return;
        }
        if (shipped == Oauth.Shipped.MICROSOFT) {
            oauth.startMicrosoftOauth(
                    pendingEmail, Oauth.fixedScope(shipped, Oauth.withTasks(shipped, scope)));
            return;
        }
        if (option.method.type() == AuthMethod.Type.OAUTH_ISSUER) {
            oauth.startIssuerOauth(target, option.method.issuer(), grantedDomains(), heading, null);
            return;
        }
        oauth.promptOauthClient(
                target,
                new OauthFlow.AuthServer(
                        option.method.authorizationEndpoint(), option.method.tokenEndpoint()),
                scope,
                heading);
    }

    /** A space-separated scope as a set, empty for none. */
    private static Set<String> scopeSet(String scope) {
        Set<String> set = new HashSet<>();
        if (scope != null) {
            for (String part : scope.trim().split("\\s+")) {
                if (!part.isEmpty()) {
                    set.add(part);
                }
            }
        }
        return set;
    }

    /** The reading page of a domain, or null. */
    private Page readingPage(PimDomain domain) {
        for (Page page : pages) {
            if (page.domain == domain && !page.sending) {
                return page;
            }
        }
        return null;
    }

    /**
     * Lands a redeemed browser grant on every page it covers.
     *
     * <p>A page whose scopes did not come back (unticked on Google's page)
     * says so and asks again on its Continue. Refresh material and granted
     * scopes are kept, for later refreshes and union grants.
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
        Set<String> granted = tokens.scope() == null ? null : scopeSet(tokens.scope());

        List<PimDomain> overJmap = new ArrayList<>();
        Account jmap = null;
        for (Map.Entry<PimDomain, String> entry : oauthGroup.entrySet()) {
            PimDomain domain = entry.getKey();
            DomainSetup setup = setups.get(domain);
            Page page = readingPage(domain);
            if (setup == null || page == null) {
                continue;
            }
            Set<String> needs = grantNeeds.get(domain);
            if (granted != null && needs != null && !granted.containsAll(needs)) {
                page.problem = host.getString(R.string.signin_not_allowed);
                continue;
            }
            page.problem = null;
            page.signed = true;
            setup.baseUrl = entry.getValue();
            setup.credential = credential;
            Account server = new Account(setup.baseUrl, "", tokens.accessToken());
            if (server.info().announcesCapabilities()) {
                overJmap.add(domain);
                jmap = server;
            }
        }
        oauthGroup.clear();
        grantNeeds.clear();

        if (shell.authStep() == STEP_OAUTH) {
            shell.showAuth(STEP_SIGNIN);
        }
        if (jmap == null) {
            granted();
            return;
        }

        // NOTE: a grant probes nothing, so the JMAP session is read here,
        // for the domains it serves no account for to fall back alone.
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

    /**
     * A grant landed: on to the first sign-in still asking something, its
     * own page when the grant left a domain to ask again.
     */
    private void granted() {
        checking = null;
        host.setAuthLoading(R.id.signin_continue, R.id.signin_progress, false);
        pageAt = -1;
        if (pages.isEmpty()) {
            showPage();
            return;
        }
        nextPage();
    }

    /**
     * Moves the pages of the domains a JMAP session turned out not to serve
     * onto their best option off JMAP, to sign in on their own, and answers
     * those with none, which are left out.
     *
     * <p>Whether a session serves calendars is known only once signed in, so
     * JMAP leads every domain and this is the way back to CalDAV.
     */
    private List<PimDomain> fallBack(List<PimDomain> unserved) {
        List<PimDomain> left = new ArrayList<>();
        for (PimDomain domain : unserved) {
            DomainSetup setup = setups.get(domain);
            Page page = readingPage(domain);
            setup.credential = null;
            setup.baseUrl = null;
            if (page != null) {
                page.signed = false;
            }
            List<SetupOption> offJmap = new ArrayList<>();
            for (SetupOption option : setup.options) {
                if (!"jmap".equals(option.service)) {
                    offJmap.add(option);
                }
            }
            SetupOption fallback = SetupPlan.standardOption(domain, offJmap, pendingEmail);
            if (page == null) {
                continue;
            }
            int at = pages.indexOf(page);
            if (fallback == null) {
                setup.enabled = false;
                pages.remove(at);
                left.add(domain);
                continue;
            }
            Log.d("pimalaya", domain + " not served over jmap, falls back to " + fallback.service);
            setup.selected = fallback;
            pages.set(at, pageOf(domain, fallback));
        }
        // NOTE: a domain fallen back signs in on its own, or with another
        // sign-in of its server.
        if (!unserved.isEmpty()) {
            buildIdentities();
        }
        return left;
    }

    /**
     * Steps back within the sign-in step: the result to the page it left, a
     * sign-in page or the settings page to the page shown before, the servers
     * page to the address, what signed in dropped there.
     */
    public void abortAuthSteps() {
        if (checking != null) {
            return;
        }
        if (shell.authStep() == STEP_RESULT) {
            showPage();
            return;
        }
        if (pageAt >= 0) {
            // NOTE: back to the page shown before, past the ones passed.
            int back = Math.min(pageAt, identities.size()) - 1;
            while (back >= 0 && !identities.get(back).shown) {
                back--;
            }
            pageAt = back;
            showPage();
            return;
        }
        cards.clear();
        pages.clear();
        identities.clear();
        cardsChanged = true;
        oauthGroup.clear();
        for (DomainSetup setup : setups.values()) {
            setup.credential = null;
            setup.submitCredential = null;
            setup.baseUrl = null;
        }
        shell.showAuth(STEP_EMAIL);
        resetConfigContinue();
    }

    /** A grant failed or was refused: its page stays, Continue back. */
    public void grantAborted() {
        checking = null;
        oauthGroup.clear();
        grantNeeds.clear();
        resetConfigContinue();
    }

    /** Back from the OAuth client page or the books page, to the page. */
    public void backToSignIn() {
        checking = null;
        pickingMailboxes = false;
        pickingCalendars = false;
        showPage();
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

    /** What each domain connects to, and its tick, then the servers page. */
    private void proceed() {
        planSetups();
        connectedEmail = pendingEmail;
        for (DomainSetup setup : setups.values()) {
            setup.credential = null;
            setup.submitCredential = null;
        }
        openServers();
    }

    /**
     * The flow was left: a discovery still running lands nowhere, its answer
     * dropped.
     */
    public void leave() {
        discoveryRun++;
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
     * Onward once signed in and what the phone already shows is said: the
     * addressbooks subscribed as picked, every one when none was, then the
     * save.
     */
    private void connected(String email) {
        if (pendingBooks == null) {
            finishOnboarding();
            return;
        }
        Set<String> subscribed = new HashSet<>();
        for (Addressbook book : pendingBooks) {
            if (chosenBooks == null || chosenBooks.contains(book.url())) {
                subscribed.add(book.url());
            }
        }
        commitBooks(subscribed);
    }

    /**
     * Turns what the pages signed in to into one account: its mailboxes and
     * addressbooks as picked or by default, what the phone already shows said,
     * then the save.
     */
    private void commitConnections() {
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
            resetConfigContinue();
            return;
        }

        // NOTE: mailboxes nobody picked are every one but the junk and the
        // trash, which takes their listing; then this runs again.
        Account mailServer = connectedAccount.server(PimDomain.MAIL);
        if (mailServer != null && chosenMailboxes == null) {
            listMailboxes(
                    mailServer,
                    listed -> {
                        chosenMailboxes = defaultMailboxes(listed);
                        commitConnections();
                    });
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
                                    if (calendarsFound || found) {
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

    /** Whether a mailbox is subscribed until picked: not the junk or trash. */
    private static boolean keptByDefault(org.pimalaya.client.Mailbox mailbox) {
        return !"junk".equals(mailbox.role())
                && !org.pimalaya.client.Mailbox.TRASH.equals(mailbox.role());
    }

    /** The mailboxes subscribed when none was picked; a single one is taken. */
    private static Set<String> defaultMailboxes(List<org.pimalaya.client.Mailbox> listed) {
        Set<String> chosen = new HashSet<>();
        for (org.pimalaya.client.Mailbox mailbox : listed) {
            if (listed.size() == 1 || keptByDefault(mailbox)) {
                chosen.add(mailbox.name());
            }
        }
        return chosen;
    }

    /** Lists a signed-in mail server's mailboxes, then hands them on. */
    private void listMailboxes(
            Account server, java.util.function.Consumer<List<org.pimalaya.client.Mailbox>> then) {
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
                    // NOTE: not postAlive: what it leads to may persist the
                    // account (finishOnboarding).
                    host.main.post(
                            () -> {
                                resetConfigContinue();
                                then.accept(listed);
                            });
                });
    }

    /** Lists a signed-in contacts server's addressbooks, then hands them on. */
    private void listBooks(Account server, java.util.function.Consumer<List<Addressbook>> then) {
        busy();
        host.io.execute(
                () -> {
                    List<Addressbook> listed;
                    try (Transport transport = new Transport()) {
                        listed = parts.clients().contact().listAddressbooks(transport, server);
                    } catch (Exception error) {
                        Log.w("pimalaya", "addressbooks unlisted", error);
                        host.postAlive(
                                () -> {
                                    resetConfigContinue();
                                    host.showError(error, R.string.connect_failed);
                                });
                        return;
                    }
                    host.postAlive(
                            () -> {
                                resetConfigContinue();
                                then.accept(listed);
                            });
                });
    }

    /** Lists a signed-in calendar server's calendars, then hands them on. */
    private void listCalendars(
            Account server, java.util.function.Consumer<List<org.pimalaya.client.Calendar>> then) {
        busy();
        host.io.execute(
                () -> {
                    List<org.pimalaya.client.Calendar> listed;
                    try (Transport transport = new Transport()) {
                        listed = parts.clients().calendar().listCalendars(transport, server);
                    } catch (Exception error) {
                        Log.w("pimalaya", "calendars unlisted", error);
                        host.postAlive(
                                () -> {
                                    resetConfigContinue();
                                    host.showError(error, R.string.connect_failed);
                                });
                        return;
                    }
                    host.postAlive(
                            () -> {
                                resetConfigContinue();
                                then.accept(listed);
                            });
                });
    }

    /** The calendars to subscribe, a checkbox row each, all until picked. */
    private void openCalendarsSelection(List<org.pimalaya.client.Calendar> calendars) {
        pickingMailboxes = false;
        pickingCalendars = true;
        bookChoices = new ArrayList<>();
        listedCalendars = calendars;

        ((TextView) host.findViewById(R.id.books_heading)).setText(R.string.calendars_title);
        ((TextView) host.findViewById(R.id.books_line))
                .setText(host.getString(R.string.books_signed, pendingEmail));
        ((TextView) host.findViewById(R.id.books_message)).setText(R.string.calendars_description);
        host.findViewById(R.id.books_note).setVisibility(View.GONE);
        ((Button) host.findViewById(R.id.books_continue)).setText(R.string.email_submit);

        LinearLayout container = host.findViewById(R.id.books_container);
        container.removeAllViews();
        if (calendars.isEmpty()) {
            TextView empty = new TextView(host);
            empty.setText(R.string.calendars_none);
            empty.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
            container.addView(empty);
        } else {
            LinearLayout card = CardRows.card(host);
            container.addView(card);
            for (org.pimalaya.client.Calendar calendar : calendars) {
                boolean on = chosenCalendars == null || chosenCalendars.contains(calendar.url());
                addChoice(card, calendar.name(), calendar.url(), on);
            }
        }

        shell.showAuth(STEP_BOOKS);
        resetConfigContinue();
    }

    /** The calendars picked, back to the settings page. */
    private void confirmCalendars() {
        Set<String> chosen = new HashSet<>();
        for (BookChoice choice : bookChoices) {
            if (choice.subscribe.isChecked()) {
                chosen.add(choice.url);
            }
        }
        chosenCalendars = chosen;
        pickingCalendars = false;
        showPage();
    }

    /**
     * Keeps the calendars not picked out of the calendar list, which is also
     * what the calendar sync takes: by their store ids, the account's id and
     * each calendar's URL, before the first sync lists them.
     */
    private void applyCalendars(String email) {
        if (chosenCalendars == null || listedCalendars == null) {
            return;
        }
        String account = sync.passes().accountIdOf(email);
        MergedFilter filter = MergedFilter.of(host, PimDomain.CALENDAR);
        for (org.pimalaya.client.Calendar calendar : listedCalendars) {
            String id = org.pimalaya.pimdir.PimdirAccount.collectionId(account, calendar.url());
            if (filter.chosen(id) != chosenCalendars.contains(calendar.url())) {
                filter.toggleCollection(id);
            }
        }
    }

    /**
     * The mailboxes to subscribe, a checkbox row each, every one but the junk
     * and the trash until picked.
     */
    private void openMailboxSelection(List<org.pimalaya.client.Mailbox> mailboxes) {
        pickingMailboxes = true;
        pickingCalendars = false;
        bookChoices = new ArrayList<>();
        mailboxesTotal = mailboxes.size();

        ((TextView) host.findViewById(R.id.books_heading)).setText(R.string.mailboxes_title);
        ((TextView) host.findViewById(R.id.books_line))
                .setText(host.getString(R.string.books_signed, pendingEmail));
        ((TextView) host.findViewById(R.id.books_message)).setText(R.string.mailboxes_description);
        host.findViewById(R.id.books_note).setVisibility(View.GONE);
        ((Button) host.findViewById(R.id.books_continue)).setText(R.string.email_submit);

        LinearLayout container = host.findViewById(R.id.books_container);
        container.removeAllViews();
        LinearLayout card = CardRows.card(host);
        container.addView(card);
        for (org.pimalaya.client.Mailbox mailbox : mailboxes) {
            boolean on =
                    chosenMailboxes == null
                            ? keptByDefault(mailbox)
                            : chosenMailboxes.contains(mailbox.name());
            addChoice(card, mailbox.name(), mailbox.name(), on);
        }

        shell.showAuth(STEP_BOOKS);
        resetConfigContinue();
    }

    /** The mailboxes picked, back to Mail's page. */
    private void confirmMailboxes() {
        Set<String> chosen = new HashSet<>();
        for (BookChoice choice : bookChoices) {
            if (choice.subscribe.isChecked()) {
                chosen.add(choice.url);
            }
        }
        chosenMailboxes = chosen;
        pickingMailboxes = false;
        showPage();
    }

    /** The addressbooks to subscribe, a checkbox row each, all until picked. */
    private void openBooksSelection(List<Addressbook> books) {
        pickingMailboxes = false;
        pickingCalendars = false;
        bookChoices = new ArrayList<>();
        booksTotal = books.size();

        ((TextView) host.findViewById(R.id.books_heading)).setText(R.string.books_title);
        ((TextView) host.findViewById(R.id.books_line))
                .setText(host.getString(R.string.books_signed, pendingEmail));
        ((TextView) host.findViewById(R.id.books_message)).setText(R.string.books_description);
        host.findViewById(R.id.books_note).setVisibility(View.GONE);
        ((Button) host.findViewById(R.id.books_continue)).setText(R.string.email_submit);

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
                boolean on = chosenBooks == null || chosenBooks.contains(book.url());
                addChoice(card, book.name(), book.url(), on);
            }
        }

        shell.showAuth(STEP_BOOKS);
        resetConfigContinue();
    }

    /** The addressbooks picked, back to the Contacts page. */
    private void confirmBooks() {
        Set<String> chosen = new HashSet<>();
        for (BookChoice choice : bookChoices) {
            if (choice.subscribe.isChecked()) {
                chosen.add(choice.url);
            }
        }
        chosenBooks = chosen;
        showPage();
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
        // being listed by the first sync, which shows them on the phone; the
        // ones not picked are kept out of that sync.
        if (connected.server(PimDomain.CALENDAR) != null) {
            applyCalendars(connected.email);
            PhoneCalendars.setAccount(
                    host,
                    connected.email,
                    switches.mirrors.contains(PhoneMirror.CALENDAR) && !calendarsElsewhere);
        }

        AccountEntry stored = AccountCommit.persist(parts.accounts(), connected);
        if (chosenMailboxes != null && stored.server(PimDomain.MAIL) != null) {
            parts.mail().chooseMailboxes(stored.email, chosenMailboxes);
        }
        if (stored.server(PimDomain.MAIL) != null) {
            if (!chosenName.isEmpty()) {
                SenderName.set(host, stored.email, chosenName);
            }
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
