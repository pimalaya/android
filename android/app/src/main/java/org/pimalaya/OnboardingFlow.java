package org.pimalaya;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.pimalaya.client.Account;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.AuthMethod;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.ServiceConfig;

/**
 * The connection wizard behind the auth panel: the email (or server)
 * step with its parallel discovery, the config step proposing one
 * option per protocol and authentication variant, the credential
 * prompts, and the addressbook selection whose commit persists the
 * account and runs the first sync. The OAuth grants live in
 * {@link OauthFlow}, which lands its redeemed tokens back here through
 * {@link #connect}; the host keeps the step navigation (the flipper,
 * the bar, the shared FAB) and calls in through {@link #open},
 * {@link #continueStep} and {@link #stepReady}. Nothing persists
 * before the selection confirms, so backing out of the flow leaves
 * everything untouched.
 */
final class OnboardingFlow {
    private final MainActivity host;
    private final OauthFlow oauth;

    /** Onboarding state: the entered email and its searched configs. */
    private String pendingEmail;

    private List<ServiceConfig> searchedConfigs = new ArrayList<>();

    /** The domain whose flow is running now. */
    private PimDomain pendingDomain = PimDomain.CONTACTS;

    /** The setup screen's per-domain sections. */
    private final java.util.Map<PimDomain, DomainSetup> setups =
            new java.util.EnumMap<>(PimDomain.class);

    /**
     * The domains one in-flight browser grant is signing in for, with the
     * endpoint each of them will use; empty outside a grant.
     *
     * <p>Several, because one authorization server covers every domain that
     * chose it, and the tokens it returns belong to all of them.
     */
    private final java.util.Map<PimDomain, String> oauthGroup =
            new java.util.EnumMap<>(PimDomain.class);

    /** The account being built up, one domain's connection at a time. */
    private AccountEntry connectedAccount;

    /** The interactions Continue planned; null outside the sign-in sequence. */
    private List<AuthStep> authSteps;

    /** Which of them is running. */
    private int authStepIndex;

    /**
     * The fixed provider rule the address matched ({@code provider:google},
     * {@code provider:microsoft}), or null.
     *
     * <p>Kept beside the discovered configs rather than replacing them: a
     * provider match tells us how to sign in for contacts, and says nothing
     * about whether the same address also serves mail or calendars, which only
     * the protocol sweep can answer.
     */
    private String matchedProvider;

    /** The setup-mode choice: standard (true), advanced (false), or
     *  still being asked (null). */
    private Boolean setupMode;

    /** A config step waiting on the setup-mode choice. */
    private Runnable pendingConfigStep;

    /** The just-connected account's addressbooks. */
    private List<Addressbook> pendingBooks;

    /** The email whose books step is open. */
    private String connectedEmail;

    /** The books step's subscribe checkboxes. */
    private List<BookChoice> bookChoices = new ArrayList<>();

    /** The books step's background-sync cadence, null when bookless. */
    private Spinner booksInterval;

    OnboardingFlow(MainActivity host, OauthFlow oauth) {
        this.host = host;
        this.oauth = oauth;
    }

    /** Resets the flow to its first step and shows it. */
    void open() {
        pendingEmail = null;
        searchedConfigs = new ArrayList<>();
        pendingDomain = PimDomain.CONTACTS;
        matchedProvider = null;
        setups.clear();
        oauthGroup.clear();
        connectedAccount = null;
        ((EditText) host.findViewById(R.id.email_input)).setText("");
        host.showAuth(MainActivity.STEP_EMAIL);
    }

    /** The shared FAB's continue action for the given auth step. */
    void continueStep(int step) {
        switch (step) {
            case MainActivity.STEP_EMAIL:
                submitEmail();
                break;
            case MainActivity.STEP_DOMAIN:
                confirmSetup();
                break;
            case MainActivity.STEP_BOOKS:
                confirmBooks();
                break;
            default:
                break;
        }
    }

    /** Whether the given auth step's continue is available. */
    boolean stepReady(int step) {
        switch (step) {
            case MainActivity.STEP_EMAIL:
                return emailSubmittable();
            case MainActivity.STEP_DOMAIN:
                return setupReady();
            case MainActivity.STEP_BOOKS:
                return booksAnyChecked();
            default:
                return false;
        }
    }

    /** Whether the email field holds a submittable address or URI. */
    boolean emailSubmittable() {
        String address =
                ((EditText) host.findViewById(R.id.email_input)).getText().toString().trim();
        return !address.isEmpty() && !address.contains(" ");
    }

    /**
     * The email step's continue: discovery, always.
     *
     * <p>One field, one kind of answer. Asking for "an email, a server or a
     * URI" made the user do the app's job, and the app can do it: discovery
     * knows how to turn an address into servers, and when it turns up nothing
     * the manual server entry is offered from the next step, where it reads as
     * a fallback rather than as a thing to have known in advance.
     */
    private void submitEmail() {
        host.hideKeyboard();

        pendingEmail =
                ((EditText) host.findViewById(R.id.email_input)).getText().toString().trim();
        askSetupMode();
        search();
    }

    /**
     * Asks whether to set the account up the standard way (the first
     * proposal, every addressbook, phone mirroring, background sync
     * every 15 minutes) or step by step, while the discovery already
     * runs behind; the flow proceeds once both the choice and the
     * discovery are in.
     */
    private void askSetupMode() {
        setupMode = null;
        pendingConfigStep = null;

        new AlertDialog.Builder(host)
                .setTitle(R.string.setup_choice_title)
                .setMessage(R.string.setup_choice_message)
                .setCancelable(false)
                .setPositiveButton(R.string.setup_simple, (dialog, which) -> chooseSetup(true))
                .setNegativeButton(R.string.setup_advanced, (dialog, which) -> chooseSetup(false))
                .show();
    }

    private void chooseSetup(boolean simple) {
        setupMode = simple;
        if (pendingConfigStep != null) {
            Runnable step = pendingConfigStep;
            pendingConfigStep = null;
            step.run();
        }
    }

    /** Runs a config step now, or once the setup choice is made. */
    private void deliverConfigs(Runnable step) {
        if (setupMode == null) {
            pendingConfigStep = step;
        } else {
            step.run();
        }
    }

    /** True inside a standard (one-tap) account setup. */
    private boolean simpleSetup() {
        return setupMode == Boolean.TRUE;
    }

    /**
     * Searches the email's (or bare domain's) CardDAV and JMAP service
     * configs, then proposes them. The MX provider probe runs first and
     * is decisive: a domain whose mail exchanges live at Google or
     * Microsoft is hosted there, suite and contacts included, so the
     * dedicated provider variants show right away and the protocol
     * sweep (PACC, CardDAV and JMAP resolves), which would only produce
     * origin-fallback noise there, is skipped. No match runs the sweep
     * in parallel. A failing search surfaces as an error dialog and
     * stays on the email panel, and a domain nothing was discovered for
     * falls back to the manual server rows.
     */
    private void search() {
        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);

        host.io.execute(
                () -> {
                    // NOTE: a null resolver falls back to the bridge's
                    // DNS-over-HTTPS default, which works on mobile
                    // networks that block outbound DNS over TCP.
                    String provider = null;
                    if (!emailLogin().isEmpty()) {
                        try {
                            List<ServiceConfig> hits =
                                    host.client.searchProvider(pendingEmail, null);
                            provider = hits.isEmpty() ? null : hits.get(0).source;
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
                        configs = host.client.searchAll(pendingEmail, null);
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
                    host.main.post(
                            () -> {
                                host.setAuthLoading(R.id.fab, R.id.fab_progress, false);
                                if (searchFailure != null && found.isEmpty() && matched == null) {
                                    deliverConfigs(
                                            () ->
                                                    host.showError(
                                                            searchFailure,
                                                            R.string.discover_failed));
                                    return;
                                }
                                searchedConfigs = found;
                                matchedProvider = matched;
                                deliverConfigs(this::showSetup);
                            });
                });
    }

    /** One offered way to connect one domain: a server and a way in. */
    private static final class SetupOption {
        final String label;
        final String detail;

        /** What the account stores as its server for this domain. */
        final String baseUrl;

        /**
         * The RFC 8707 resource an OAuth grant is asked for, or null when the
         * service has no URI form.
         *
         * <p>Deliberately not {@link #baseUrl}. A JMAP account's base is the
         * internal {@code jmap://} marker the backend dispatches on, and an
         * authorization server asked to issue a token for that URI has no idea
         * what it names: Fastmail answers {@code invalid_target}. The resource
         * is the HTTPS session URL the service was discovered at, which is what
         * the server actually protects.
         */
        final String resource;

        final AuthMethod method;
        final String login;

        /**
         * Where mail is submitted, for an IMAP option whose discovery run
         * also turned up an SMTP endpoint; null everywhere else.
         *
         * <p>Not an option of its own, because submission is not a way to
         * connect a domain: nobody picks between reading mail and sending
         * it, and an SMTP row on the picker would connect to something
         * that answers no listing. It rides the option that reads.
         */
        final String submitUrl;

        SetupOption(
                String label,
                String detail,
                String baseUrl,
                String resource,
                AuthMethod method,
                String login) {
            this(label, detail, baseUrl, resource, method, login, null);
        }

        SetupOption(
                String label,
                String detail,
                String baseUrl,
                String resource,
                AuthMethod method,
                String login,
                String submitUrl) {
            this.label = label;
            this.detail = detail;
            this.baseUrl = baseUrl;
            this.resource = resource;
            this.method = method;
            this.login = login;
            this.submitUrl = submitUrl;
        }

        /** Whether this option signs in through a browser grant. */
        boolean isOauth() {
            return method != null
                    && method.type != AuthMethod.Type.PASSWORD
                    && method.type != AuthMethod.Type.BEARER;
        }
    }

    /** One domain's section of the setup screen. */
    private static final class DomainSetup {
        final PimDomain domain;

        DomainSetup(PimDomain domain) {
            this.domain = domain;
        }

        final List<SetupOption> options = new ArrayList<>();
        final List<android.widget.RadioButton> buttons = new ArrayList<>();

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

        /** Whether this domain is switched on and has something to connect to. */
        boolean ready() {
            return enabled && selected != null;
        }
    }

    /**
     * One interaction the sign-in sequence owes the user: a dialog, or a
     * browser hop.
     *
     * <p>Several domains when one browser grant covers them all, which is the
     * whole reason the sequence is planned rather than run per domain: the
     * plan is what knows that mail and calendars behind one authorization
     * server are one step and not two.
     */
    private static final class AuthStep {
        final List<PimDomain> domains = new ArrayList<>();
        final SetupOption option;

        AuthStep(SetupOption option) {
            this.option = option;
        }
    }

    /**
     * Fills the setup screen: every domain this address offers, with its
     * configurations under it and a button that signs in to the one picked.
     *
     * <p>One page rather than a domain step and then a configuration step. The
     * stepper hid what was being configured, because a screen of protocols with
     * no domain on it could have belonged to any of them; here the domain is
     * the heading above its own options and there is nothing to remember
     * between screens.
     *
     * <p>Each domain takes one option or none: the radio deselects, so an
     * address that offers calendars is not obliged to connect them. Continue
     * waits until everything picked has actually been signed in to, which is
     * what the per-domain button is for.
     */
    private void showSetup() {
        ((TextView) host.findViewById(R.id.domain_email)).setText(pendingEmail);

        LinearLayout container = host.findViewById(R.id.domain_container);
        container.removeAllViews();
        setups.clear();

        AccountEntry existing = host.accountFor(pendingEmail);
        boolean anything = false;

        for (PimDomain domain : PimDomain.values()) {
            DomainSetup setup = new DomainSetup(domain);
            setup.options.addAll(optionsFor(domain));
            anything |= !setup.options.isEmpty();
            setups.put(domain, setup);
            container.addView(sectionOf(setup, existing != null && existing.covers(domain)));
        }

        TextView message = host.findViewById(R.id.domain_message);
        message.setText(anything ? R.string.domain_message : R.string.domain_none);

        resetSetupContinue();
        host.showAuth(MainActivity.STEP_DOMAIN);
    }

    /**
     * One domain's section: a switch naming it, and the configurations under
     * it.
     *
     * <p>Switched on by default, because an address that offers a domain
     * almost always wants it; switching off discards the domain outright,
     * which is a clearer answer than an empty selection and leaves nothing to
     * misread on the way out.
     */
    private View sectionOf(DomainSetup setup, boolean alreadyConnected) {
        LinearLayout section = new LinearLayout(host);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, host.ui.dp(8), 0, host.ui.dp(16));

        android.widget.Switch toggle = new android.widget.Switch(host);
        toggle.setText(host.getString(setup.domain.label));
        toggle.setTextSize(16);
        toggle.setTypeface(toggle.getTypeface(), android.graphics.Typeface.BOLD);
        toggle.setChecked(setup.enabled);
        toggle.setPadding(0, host.ui.dp(8), 0, host.ui.dp(4));
        toggle.setOnCheckedChangeListener(
                (view, checked) -> {
                    setup.enabled = checked;
                    renderSection(setup);
                    resetSetupContinue();
                });
        section.addView(toggle);

        if (alreadyConnected) {
            TextView note = new TextView(host);
            note.setText(R.string.domain_connected);
            note.setTextSize(13);
            note.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
            section.addView(note);
        }

        for (SetupOption option : setup.options) {
            android.widget.RadioButton button = new android.widget.RadioButton(host);
            button.setText(optionLabel(option));
            button.setTextSize(15);
            button.setPadding(host.ui.dp(8), host.ui.dp(10), host.ui.dp(8), host.ui.dp(10));
            // NOTE: not a RadioGroup, so the group can start with nothing
            // picked; a RadioGroup has no empty state to open in.
            button.setOnClickListener(
                    view -> {
                        setup.selected = option;
                        renderSection(setup);
                        resetSetupContinue();
                    });
            setup.buttons.add(button);
            section.addView(button);
        }

        renderSection(setup);
        return section;
    }

    /** Shows or dims one section's options, following its switch. */
    private void renderSection(DomainSetup setup) {
        for (int index = 0; index < setup.buttons.size(); index++) {
            android.widget.RadioButton button = setup.buttons.get(index);
            button.setChecked(setup.options.get(index) == setup.selected);
            // NOTE: gone, not dimmed. A switched-off domain has nothing to say
            // and a greyed list of protocols under it is noise the reader has
            // to skip past on the way to the domains they do want.
            button.setVisibility(setup.enabled ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Continue: available once at least one domain is on and every domain
     * that is on knows what to connect to.
     *
     * <p>Nothing is signed in to yet. What the screen collects is the plan,
     * and the interactions it needs are worked out from the whole plan at
     * once, which is the only way to know that two domains behind one
     * authorization server cost one browser hop rather than two.
     */
    private void resetSetupContinue() {
        host.setAuthLoading(R.id.fab, R.id.fab_progress, false);
        host.setFabEnabled(R.id.fab, setupReady());
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

    /** The config Continue back to idle. */
    void resetConfigContinue() {
        host.setAuthLoading(R.id.fab, R.id.fab_progress, false);
        host.setFabEnabled(R.id.fab, setupReady());
    }

    /**
     * Everything this domain can be connected with: one option per discovered
     * configuration and authentication method, plus manual entry.
     */
    private List<SetupOption> optionsFor(PimDomain domain) {
        List<SetupOption> options = new ArrayList<>();

        List<ServiceConfig> configs = new ArrayList<>(PimDomain.configsFor(domain, searchedConfigs));
        java.util.Collections.sort(
                configs,
                (left, right) ->
                        Integer.compare(serviceRank(left.service), serviceRank(right.service)));

        for (ServiceConfig config : configs) {
            String baseUrl = endpointUrl(config);
            if (baseUrl == null) {
                continue;
            }
            String detail = config.url != null ? hostOf(config.url) : config.host;
            String login = config.username != null ? config.username : emailLogin();

            List<AuthMethod> methods = new ArrayList<>(config.auth);
            java.util.Collections.sort(
                    methods,
                    (left, right) -> Integer.compare(authRank(left.type), authRank(right.type)));
            String submitUrl = "imap".equals(config.service) ? submissionUrl() : null;
            for (AuthMethod method : methods) {
                options.add(
                        new SetupOption(
                                protocolName(config.service),
                                detail,
                                baseUrl,
                                resourceOf(config, method),
                                method,
                                login,
                                submitUrl));
            }
        }

        // NOTE: the provider sign-ins are contacts sign-ins (CardDAV, the
        // People API, Graph). No protocol signal reveals where contacts live at
        // Google or Microsoft, so the sign-in itself is the real test.
        if (domain == PimDomain.CONTACTS && matchedProvider != null) {
            addProviderOptions(options);
        }

        options.add(
                new SetupOption(
                        host.getString(R.string.domain_manual), null, null, null, null, null));
        return options;
    }

    /**
     * The matched provider's contacts sign-ins, as options.
     *
     * <p>They are hand-written rather than discovered because neither Google
     * nor Microsoft publishes a CardDAV SRV record or well-known: the sign-in
     * itself is the only way to find out, so the endpoints and scopes are the
     * app's own knowledge of those two.
     */
    private void addProviderOptions(List<SetupOption> options) {
        if ("provider:google".equals(matchedProvider)) {
            options.add(
                    providerOption(
                            R.string.config_carddav,
                            PimalayaClient.googleCarddavBase(pendingEmail),
                            Oauth.GOOGLE_AUTH_ENDPOINT,
                            Oauth.GOOGLE_TOKEN_ENDPOINT,
                            Oauth.GOOGLE_SCOPE));
            options.add(
                    providerOption(
                            R.string.config_google_api,
                            PimalayaClient.googlePeopleBase(pendingEmail),
                            Oauth.GOOGLE_AUTH_ENDPOINT,
                            Oauth.GOOGLE_TOKEN_ENDPOINT,
                            Oauth.GOOGLE_PEOPLE_SCOPE));
            return;
        }
        options.add(
                providerOption(
                        R.string.config_msgraph,
                        PimalayaClient.msgraphBase(pendingEmail),
                        Oauth.MICROSOFT_AUTH_ENDPOINT,
                        Oauth.MICROSOFT_TOKEN_ENDPOINT,
                        Oauth.MICROSOFT_SCOPE));
    }

    private SetupOption providerOption(
            int label, String baseUrl, String authEndpoint, String tokenEndpoint, String scope) {
        return new SetupOption(
                host.getString(label),
                hostOf(baseUrl),
                baseUrl,
                // NOTE: Google and Microsoft authorize by scope, not by RFC 8707
                // resource, and sending one earns an invalid_target from them.
                null,
                AuthMethod.oauthCodeGrant(authEndpoint, tokenEndpoint, scope),
                emailLogin());
    }

    /**
     * The RFC 8707 resource an OAuth grant asks for, or null when there is
     * none to ask for.
     *
     * <p>An HTTP service names itself: the URL it was discovered at is the URI
     * the server protects. A text one does not, and IMAP is the case that
     * matters, because nothing in PACC, autoconfig or SRV carries a resource
     * for it and a server that requires one (Fastmail answers
     * {@code invalid_target} without) leaves no other way in.
     *
     * <p>So a text service falls back to the origin of its authorization
     * endpoint, on the reasoning that a provider issuing tokens at
     * {@code https://api.example.com/oauth/authorize} protects its resources
     * under the same origin. That is a derivation, not a discovery: a provider
     * that separates the two will need its resource from RFC 9728 protected
     * resource metadata, which nothing fetches yet.
     */
    private static String resourceOf(ServiceConfig config, AuthMethod method) {
        if (config.url != null) {
            return config.url;
        }
        if (method == null || method.authorizationEndpoint == null) {
            return null;
        }
        try {
            java.net.URL endpoint = new java.net.URL(method.authorizationEndpoint);
            String port = endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort();
            return endpoint.getProtocol() + "://" + endpoint.getHost() + port + "/";
        } catch (Exception error) {
            return null;
        }
    }

    /** Protocol preference: JMAP, then the DAVs, then the rest. */
    private static int serviceRank(String service) {
        switch (service) {
            case "jmap":
                return 0;
            case "carddav":
            case "caldav":
                return 1;
            default:
                return 2;
        }
    }

    /** Auth precedence: OAuth 2.0 over API token over password. */
    private static int authRank(AuthMethod.Type type) {
        switch (type) {
            case PASSWORD:
                return 2;
            case BEARER:
                return 1;
            default:
                return 0;
        }
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
        return option.label + detail + " · " + authName(option.method.type);
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
     * Signs in to one domain's picked option.
     *
     * <p>A password or a token is asked for per domain, because each is a
     * separate secret even when the server is the same. A browser grant is not:
     * every domain that picked OAuth at the same authorization endpoint is
     * signed in to <strong>once</strong>, with the scopes merged, because that
     * is what one authorization server means. Selecting OAuth for mail and
     * calendars and a token for contacts is therefore one browser hop and one
     * token prompt, not three interactions.
     */
    /**
     * Works out the interactions the whole selection needs, in order.
     *
     * <p>A password and a token are one step each, because each is its own
     * secret however many domains share a server. Browser grants are pooled by
     * authorization server and resource, so the domains one consent actually
     * covers cost one hop: this is where "mail and calendars on one JMAP
     * session" becomes a single step instead of two identical ones.
     */
    private List<AuthStep> planAuthSteps() {
        List<AuthStep> steps = new ArrayList<>();

        for (DomainSetup setup : setups.values()) {
            if (!setup.ready()) {
                continue;
            }
            SetupOption option = setup.selected;

            AuthStep shared = null;
            if (option.isOauth()) {
                for (AuthStep candidate : steps) {
                    if (candidate.option.isOauth()
                            && sameAuthorizationServer(candidate.option.method, option.method)
                            && sameResource(candidate.option.resource, option.resource)) {
                        shared = candidate;
                        break;
                    }
                }
            }

            if (shared != null) {
                shared.domains.add(setup.domain);
            } else {
                AuthStep step = new AuthStep(option);
                step.domains.add(setup.domain);
                steps.add(step);
            }
        }
        return steps;
    }

    /**
     * Abandons the sign-in sequence and returns to the selection.
     *
     * <p>Everything already signed in to this run is dropped with it. Half a
     * sequence is not half an account: leaving the completed steps behind
     * would persist an account covering fewer domains than the screen still
     * shows selected.
     */
    void abortAuthSteps() {
        authSteps = null;
        authStepIndex = 0;
        oauthGroup.clear();
        for (DomainSetup setup : setups.values()) {
            setup.credential = null;
        }
        resetSetupContinue();
        host.showAuth(MainActivity.STEP_DOMAIN);
    }

    /** Runs the next planned interaction, or finishes when there are none. */
    private void runNextAuthStep() {
        if (authSteps == null || authStepIndex >= authSteps.size()) {
            commitConnections();
            return;
        }

        AuthStep step = authSteps.get(authStepIndex);
        SetupOption option = step.option;
        pendingDomain = step.domains.get(0);

        if (option.method == null) {
            promptManualEndpoint(pendingEmail);
            return;
        }
        switch (option.method.type) {
            case PASSWORD:
                promptCredentials(option.baseUrl, option.detail, option.login);
                break;
            case BEARER:
                promptToken(option.baseUrl, option.detail);
                break;
            default:
                startGroupedOauth(step);
                break;
        }
    }

    /**
     * The dialog title of the running step: how far along it is, which domain
     * it is for, and how it signs in.
     *
     * <p>A sequence of unlabelled credential prompts is indistinguishable from
     * one prompt that keeps failing, which is what this exists to prevent.
     */
    private String stepTitle() {
        if (authSteps == null || authStepIndex >= authSteps.size()) {
            return host.getString(R.string.password_title);
        }
        AuthStep step = authSteps.get(authStepIndex);

        List<String> domains = new ArrayList<>();
        for (PimDomain domain : step.domains) {
            domains.add(host.getString(domain.label));
        }
        String kind =
                step.option.method == null
                        ? host.getString(R.string.domain_manual)
                        : authName(step.option.method.type);

        return host.getString(
                R.string.setup_step_title,
                authStepIndex + 1,
                authSteps.size(),
                String.join(", ", domains),
                kind);
    }

    /**
     * Runs one browser grant for every domain of the step, with their scopes
     * merged.
     */
    private void startGroupedOauth(AuthStep step) {
        SetupOption option = step.option;
        oauthGroup.clear();

        java.util.Set<String> scopes = new java.util.LinkedHashSet<>();
        for (PimDomain domain : step.domains) {
            DomainSetup setup = setups.get(domain);
            oauthGroup.put(domain, setup.selected.baseUrl);
            addScopes(scopes, setup.selected.method.scope);
        }

        String scope = scopes.isEmpty() ? null : String.join(" ", scopes);
        if (option.method.type == AuthMethod.Type.OAUTH_ISSUER) {
            // NOTE: the resource, never the base URL. A JMAP account's base is
            // the internal jmap:// marker and a mailbox's is imaps://, and an
            // authorization server asked to issue a token for either answers
            // invalid_target: neither is a URI it protects.
            oauth.startIssuerOauth(
                    pendingEmail,
                    option.baseUrl,
                    option.method.issuer,
                    option.resource,
                    grantedDomains());
            return;
        }
        oauth.promptOauthClient(
                pendingEmail,
                option.baseUrl,
                option.method.authorizationEndpoint,
                option.method.tokenEndpoint,
                scope,
                option.resource,
                null,
                null,
                null);
    }

    /** The domains the in-flight grant covers, for the scopes to ask for. */
    private String grantedDomains() {
        List<String> ids = new ArrayList<>();
        for (PimDomain domain : oauthGroup.keySet()) {
            ids.add(domain.id);
        }
        return String.join(" ", ids);
    }

    private static void addScopes(java.util.Set<String> scopes, String scope) {
        if (scope == null || scope.isEmpty()) {
            return;
        }
        for (String part : scope.split("\\s+")) {
            if (!part.isEmpty()) {
                scopes.add(part);
            }
        }
    }

    /** Whether two OAuth methods would send the user to the same server. */
    private static boolean sameAuthorizationServer(AuthMethod left, AuthMethod right) {
        if (left.type != right.type) {
            return false;
        }
        if (left.issuer != null || right.issuer != null) {
            return left.issuer != null && left.issuer.equals(right.issuer);
        }
        return left.authorizationEndpoint != null
                && left.authorizationEndpoint.equals(right.authorizationEndpoint);
    }

    /** Whether two options name the same protected resource, absence included. */
    private static boolean sameResource(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    /**
     * Asks for the one thing discovery would have produced for this domain: a
     * server. What is asked for follows the domain, since a mailbox is named by
     * a host and a port and a DAV collection by a URL.
     */
    private void promptManualEndpoint(String address) {
        String suggestion = address.contains("://") ? address : "https://" + address;
        EditText field =
                host.ui.field(
                        pendingDomain == PimDomain.MAIL
                                ? R.string.manual_mail_server
                                : R.string.manual_dav_url,
                        pendingDomain == PimDomain.MAIL ? hostOf(suggestion) : suggestion);

        LinearLayout fields = new LinearLayout(host);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(host.ui.dp(24), host.ui.dp(8), host.ui.dp(24), 0);
        fields.addView(field);

        new AlertDialog.Builder(host)
                .setTitle(stepTitle())
                .setMessage(
                        host.getString(
                                R.string.manual_message, host.getString(pendingDomain.label)))
                .setView(fields)
                .setPositiveButton(
                        R.string.password_submit,
                        (dialog, which) -> {
                            String entered = field.getText().toString().trim();
                            if (entered.isEmpty()) {
                                abortAuthSteps();
                                return;
                            }
                            String url = manualUrl(entered);
                            promptCredentials(url, hostOf(url), emailLogin());
                        })
                .setNegativeButton(
                        android.R.string.cancel, (dialog, which) -> abortAuthSteps())
                .show();
    }

    /**
     * What was typed, as the URL the domain's client connects to: implicit-TLS
     * IMAP on its default port for mail, the URL itself for a DAV collection.
     */
    private String manualUrl(String entered) {
        if (pendingDomain != PimDomain.MAIL) {
            return entered.contains("://") ? entered : "https://" + entered;
        }
        if (entered.contains("://")) {
            return entered;
        }
        return entered.contains(":") ? "imaps://" + entered : "imaps://" + entered + ":993";
    }

    /**
     * The entered email as a login prefill; empty when the connection
     * field carried a bare domain or a URL instead.
     */
    private String emailLogin() {
        return pendingEmail != null && pendingEmail.contains("@") ? pendingEmail : "";
    }

    /**
     * Prompts for the login and secret of a server. The login is only
     * prefilled (from the discovered username or the entered email): a
     * provider's login is not necessarily the address, so the user
     * confirms it; leaving it empty sends the secret as a Bearer token.
     * Verifies the account on submit.
     */
    private void promptCredentials(String baseUrl, String serverHost, String prefilledLogin) {
        EditText login = host.ui.field(R.string.custom_login, prefilledLogin);
        EditText secret = new EditText(host);
        secret.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setHint(R.string.password_hint);

        LinearLayout fields = new LinearLayout(host);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(host.ui.dp(24), host.ui.dp(8), host.ui.dp(24), 0);
        fields.addView(login);
        fields.addView(secret);

        new AlertDialog.Builder(host)
                .setTitle(stepTitle())
                .setMessage(host.getString(R.string.password_server, pendingEmail, serverHost))
                .setView(fields)
                .setPositiveButton(
                        R.string.password_submit,
                        (dialog, which) -> {
                            String password = secret.getText().toString();
                            if (password.isEmpty()) {
                                host.toast(host.getString(R.string.password_empty));
                                return;
                            }
                            connect(
                                    new Account(
                                            baseUrl,
                                            login.getText().toString().trim(),
                                            password),
                                    pendingEmail,
                                    null,
                                    null,
                                    null,
                                    null);
                        })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> abortAuthSteps())
                .show();
    }

    /**
     * Prompts for an API token in a dialog. No login is asked: a token
     * carries its own identity, and the empty login makes the backends
     * send it as Bearer instead of Basic. Verifies and persists the
     * account on submit.
     */
    private void promptToken(String baseUrl, String serverHost) {
        EditText input = new EditText(host);
        input.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(R.string.config_token);

        // NOTE: inset the field from the dialog edges (setView is flush).
        LinearLayout wrapper = new LinearLayout(host);
        wrapper.setPadding(host.ui.dp(24), host.ui.dp(8), host.ui.dp(24), 0);
        wrapper.addView(
                input,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(host)
                .setTitle(stepTitle())
                .setMessage(host.getString(R.string.password_server, pendingEmail, serverHost))
                .setView(wrapper)
                .setPositiveButton(
                        R.string.password_submit,
                        (dialog, which) -> {
                            String token = input.getText().toString();
                            if (token.isEmpty()) {
                                host.toast(host.getString(R.string.password_empty));
                                return;
                            }
                            connect(
                                    new Account(baseUrl, "", token),
                                    pendingEmail,
                                    null,
                                    null,
                                    null,
                                    null);
                        })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> abortAuthSteps())
                .show();
    }

    /**
     * Verifies the account connects, then moves to the addressbook
     * selection. Nothing persists yet: the account and its books only
     * store when the selection confirms, so backing out of the flow
     * before that leaves everything untouched. The last four parameters
     * carry the refresh material of an OAuth account (all null for a
     * password one), so expired access tokens can be refreshed on later
     * syncs.
     */
    void connect(
            Account candidate,
            String email,
            String refreshToken,
            String tokenEndpoint,
            String clientId,
            String clientSecret) {
        connectedEmail = email;

        // NOTE: a browser grant covers every domain that chose the same
        // authorization server, so one credential lands on all of them, each
        // against its own endpoint. That sharing is the point: the grant is
        // one consent, and a provider that rotates its refresh token retires
        // every copy but the one it just issued. A password or token prompt
        // covers the one domain whose button opened it.
        AccountCredential credential =
                refreshToken == null
                        ? AccountCredential.password(candidate.login, candidate.password)
                        : AccountCredential.oauth(
                                candidate.password,
                                refreshToken,
                                tokenEndpoint,
                                clientId,
                                clientSecret);

        if (!oauthGroup.isEmpty()) {
            for (java.util.Map.Entry<PimDomain, String> granted : oauthGroup.entrySet()) {
                DomainSetup setup = setups.get(granted.getKey());
                if (setup == null) {
                    continue;
                }
                setup.baseUrl = granted.getValue();
                setup.credential = credential;
            }
            oauthGroup.clear();
        } else {
            DomainSetup setup = setups.get(pendingDomain);
            if (setup != null) {
                setup.baseUrl = candidate.baseUrl;
                setup.credential = credential;
            }
        }

        // NOTE: one step done, on to the next. The sequence advances here
        // rather than at each prompt because a browser grant returns through
        // this same callback after an OS round trip, so this is the one place
        // every kind of step comes back to.
        authStepIndex++;
        runNextAuthStep();
    }

    /**
     * Continue from the setup screen: everything signed in to becomes one
     * account, and the contacts domain adds its address book selection.
     */
    private void confirmSetup() {
        connectedEmail = pendingEmail;
        for (DomainSetup setup : setups.values()) {
            setup.credential = null;
        }
        authSteps = planAuthSteps();
        authStepIndex = 0;
        runNextAuthStep();
    }

    /**
     * Turns everything the sequence signed in to into one account, and adds
     * the contacts domain's address book selection when it is among them.
     */
    private void commitConnections() {
        authSteps = null;

        connectedAccount = null;
        for (DomainSetup setup : setups.values()) {
            if (setup.credential == null) {
                continue;
            }
            String submitUrl =
                    setup.domain == PimDomain.MAIL && setup.selected != null
                            ? setup.selected.submitUrl
                            : null;
            if (connectedAccount == null) {
                connectedAccount = AccountEntry.empty(connectedEmail);
            }
            connectedAccount =
                    connectedAccount.with(
                            setup.domain, setup.baseUrl, submitUrl, setup.credential);
        }
        if (connectedAccount == null) {
            return;
        }

        Account contacts = connectedAccount.server(PimDomain.CONTACTS);
        if (contacts == null) {
            finishOnboarding();
            return;
        }

        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
        String email = connectedEmail;
        host.io.execute(
                () -> {
                    try {
                        List<Addressbook> fetched = host.client.listAddressbooks(contacts);
                        host.main.post(
                                () -> {
                                    resetConfigContinue();
                                    pendingBooks = fetched;
                                    if (simpleSetup()) {
                                        confirmAllBooks(email);
                                    } else {
                                        openBooksSelection(email, fetched);
                                    }
                                });
                    } catch (Exception error) {
                        Log.w("pimalaya", "connect failed", error);
                        host.main.post(
                                () -> {
                                    resetConfigContinue();
                                    host.showError(error, R.string.connect_failed);
                                });
                    }
                });
    }

    /**
     * Lists the just-connected account's addressbooks as a plain
     * checkbox per book, its name the label, subscribed by default.
     * Phone-contacts mirroring is turned on by default for every
     * subscribed book; the drawer's per-book settings let the user turn
     * it off later. Below the list, one cadence dropdown seeds the
     * background sync of every selected book (never by default). The
     * checkbox box sits at the panel's 24dp inset, aligned with the
     * title and paragraph above. Same chrome as the config panel.
     */
    private void openBooksSelection(String email, List<Addressbook> books) {
        connectedEmail = email;
        bookChoices = new ArrayList<>();
        booksInterval = null;

        LinearLayout container = host.findViewById(R.id.books_container);
        container.removeAllViews();

        if (books.isEmpty()) {
            TextView empty = new TextView(host);
            empty.setText(R.string.books_none);
            empty.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
            empty.setPadding(0, host.ui.dp(16), 0, host.ui.dp(16));
            container.addView(empty);
        }

        for (Addressbook book : books) {
            boolean first = container.getChildCount() == 0;

            CheckBox subscribe = new CheckBox(host);
            subscribe.setText(book.name);
            subscribe.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            subscribe.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
            subscribe.setChecked(true);
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = first ? host.ui.dp(8) : host.ui.dp(4);
            subscribe.setLayoutParams(params);
            subscribe.setOnCheckedChangeListener((view, checked) -> updateBooksContinue());
            container.addView(subscribe);

            bookChoices.add(new BookChoice(book.url, subscribe));
        }

        if (!books.isEmpty()) {
            TextView cadence = new TextView(host);
            cadence.setText(R.string.book_background_sync);
            cadence.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
            cadence.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams cadenceParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            cadenceParams.topMargin = host.ui.dp(24);
            container.addView(cadence, cadenceParams);

            booksInterval = host.intervalSpinner();
            LinearLayout.LayoutParams intervalParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            intervalParams.topMargin = host.ui.dp(8);
            container.addView(booksInterval, intervalParams);
        }

        host.setAuthLoading(R.id.fab, R.id.fab_progress, false);
        updateBooksContinue();
        host.showAuth(MainActivity.STEP_BOOKS);
    }

    /** Continue is enabled only while at least one addressbook is on. */
    private void updateBooksContinue() {
        host.setFabEnabled(R.id.fab, booksAnyChecked());
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
        long minutes =
                booksInterval == null
                        ? 0
                        : BackgroundSync.INTERVAL_MINUTES[booksInterval.getSelectedItemPosition()];
        commitBooks(subscribed, minutes);
    }

    /**
     * The standard setup's commit, selection-free: every addressbook
     * subscribed with phone mirroring and a 15-minute background sync,
     * then the first sync straight to the contacts list.
     */
    private void confirmAllBooks(String email) {
        connectedEmail = email;
        java.util.Set<String> subscribed = new java.util.HashSet<>();
        for (Addressbook book : pendingBooks) {
            subscribed.add(book.url);
        }
        commitBooks(subscribed, 15);
    }

    /**
     * The flow's real commit, shared by the books step's Continue and
     * the standard setup: persists the connected account and its
     * addressbooks, subscribes the given ones with phone mirroring on
     * by default and the given background sync cadence, asks the
     * permissions that setup needs (contacts, plus notifications when a
     * cadence is on), then runs the account's first sync.
     */
    private void commitBooks(java.util.Set<String> subscribed, long minutes) {
        host.base.replaceAddressbooks(connectedEmail, pendingBooks);
        new PimdirCollections(host.pimdir, host)
                .replace(
                        connectedEmail,
                        PimdirSummary.CONTACT,
                        PimdirCollections.of(connectedEmail, pendingBooks));

        for (Addressbook book : pendingBooks) {
            boolean on = subscribed.contains(book.url);
            host.base.setBookState(book.url, on, on, on);
            BackgroundSync.setInterval(host, book.url, on ? minutes : 0);
        }
        BackgroundSync.reconcile(host, host.base.loadAllAddressbooks());

        // NOTE: ask up front in one grouped request; two
        // requestPermissions calls would cancel each other. Contacts
        // because subscribed books mirror into the Contacts app;
        // notifications (Android 13+) for the background sync report.
        List<String> permissions = new ArrayList<>();
        if (!host.hasContactsPermission()) {
            permissions.add(Manifest.permission.READ_CONTACTS);
            permissions.add(Manifest.permission.WRITE_CONTACTS);
        }
        if (minutes > 0
                && Build.VERSION.SDK_INT >= 33
                && host.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            host.requestPermissions(
                    permissions.toArray(new String[0]), MainActivity.REQUEST_CONTACTS);
        }

        finishOnboarding();
    }

    /**
     * Persists everything the run connected, as one account, and lands on the
     * screen of a domain it covers.
     *
     * <p>One save at the end rather than one per domain: the account is the
     * unit, and a run that connected mail and contacts should leave one entry
     * behind holding both, not two entries racing to replace each other.
     */
    private void finishOnboarding() {
        if (connectedAccount == null) {
            return;
        }

        AccountEntry connected = connectedAccount;
        connectedAccount = null;

        // NOTE: merged into whatever the address already had, so connecting a
        // domain onto an existing account keeps the domains it already covers
        // instead of replacing the account with a one-domain one.
        AccountEntry merged = connected;
        for (PimDomain domain : connected.domains()) {
            merged =
                    host.store.connect(
                            connected.email,
                            domain,
                            connected.connection(domain).baseUrl,
                            connected.credential(domain));
        }

        AccountEntry stored = merged;
        host.accounts.removeIf(entry -> entry.email.equals(stored.email));
        host.accounts.add(stored);

        if (stored.covers(PimDomain.CONTACTS)) {
            host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
            host.syncRemote(true);
            return;
        }

        boolean mail = stored.covers(PimDomain.MAIL);
        host.leaveOnboarding(mail ? MainActivity.PANEL_MAIL : MainActivity.PANEL_CALENDAR);
        if (mail) {
            host.syncMail();
        }
        if (stored.covers(PimDomain.CALENDAR)) {
            host.syncCalendars();
        }
    }

    /**
     * The URL an account connects to for one discovered config, or null when
     * this app cannot drive it.
     *
     * <p>An HTTP endpoint arrives ready to use. A TCP one does not: discovery
     * reports a host, a port and a security mode, and the scheme is the client's
     * to choose. Only implicit TLS is offered, because the IMAP client has no
     * STARTTLS step, and a {@code starttls} endpoint driven as if it were
     * implicit would connect in the clear rather than fail.
     */
    /**
     * Where this address submits mail, from the same discovery run that
     * found where it reads it, or null when nothing was found.
     *
     * <p>Implicit TLS only, for the reason {@link #endpointUrl} gives:
     * this client has no STARTTLS step, and driving a {@code starttls}
     * endpoint as if it were implicit would hand a message over in the
     * clear rather than fail.
     */
    private String submissionUrl() {
        for (ServiceConfig config : searchedConfigs) {
            if (!"smtp".equals(config.service) || config.host == null) {
                continue;
            }
            if (!"tls".equalsIgnoreCase(config.security)) {
                Log.w(
                        "pimalaya",
                        "skip smtp at " + config.host + ": unsupported security "
                                + config.security);
                continue;
            }
            return "smtps://" + config.host + ":" + config.port;
        }
        return null;
    }

    private static String endpointUrl(ServiceConfig config) {
        if (config.url != null) {
            return "jmap".equals(config.service)
                    ? PimalayaClient.jmapBase(config.url)
                    : config.url;
        }
        if (config.host == null || !"imap".equals(config.service)) {
            return null;
        }
        if (!"tls".equalsIgnoreCase(config.security)) {
            Log.w(
                    "pimalaya",
                    "skip " + config.service + " at " + config.host
                            + ": unsupported security " + config.security);
            return null;
        }
        return "imaps://" + config.host + ":" + config.port;
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception error) {
            return url;
        }
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
