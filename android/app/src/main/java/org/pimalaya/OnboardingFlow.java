package org.pimalaya;

import android.Manifest;
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
import org.pimalaya.client.Account;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.AuthMethod;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.ServiceConfig;
import org.pimalaya.client.Transport;

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

    /**
     * Whether the advanced setup is running: a section of configurations
     * per domain and a prompt per sign-in. Off by default, the standard
     * setup being the path; the links on the address and domain steps
     * turn it on.
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

    OnboardingFlow(MainActivity host, OauthFlow oauth) {
        this.host = host;
        this.oauth = oauth;
    }

    /** Wires the steps' own buttons and fields, once the views exist. */
    void bind() {
        EditText email = host.findViewById(R.id.email_input);
        EditText password = host.findViewById(R.id.domain_password);

        for (int id : new int[] {R.id.email_continue, R.id.domain_connect, R.id.result_continue}) {
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

        host.findViewById(R.id.domain_connect).setOnClickListener(view -> confirmSetup());
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

    /** Resets the flow to its first step and shows it. */
    void open() {
        pendingEmail = null;
        searchedConfigs = new ArrayList<>();
        pendingDomain = PimDomain.CONTACTS;
        matchedProvider = null;
        advanced = false;
        verifying = false;
        setups.clear();
        oauthGroup.clear();
        connectedAccount = null;
        ((EditText) host.findViewById(R.id.email_input)).setText("");
        ((EditText) host.findViewById(R.id.domain_password)).setText("");
        host.setAuthLoading(R.id.email_continue, R.id.email_progress, false);
        host.setAuthLoading(R.id.domain_connect, R.id.domain_progress, false);
        host.showAuth(MainActivity.STEP_EMAIL);
    }

    /** The shared FAB's continue action for the given auth step. */
    void continueStep(int step) {
        if (step == MainActivity.STEP_BOOKS) {
            confirmBooks();
        }
    }

    /** Whether the given auth step's continue is available. */
    boolean stepReady(int step) {
        return step == MainActivity.STEP_BOOKS && booksAnyChecked();
    }

    /** Brings a step's own buttons in line with its state, on entering it. */
    void refreshStep(int step) {
        if (step == MainActivity.STEP_EMAIL) {
            host.setFabEnabled(R.id.email_continue, emailSubmittable());
        } else if (step == MainActivity.STEP_DOMAIN && !verifying) {
            resetSetupContinue();
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
     *
     * <p>No question about how much to ask comes first: the standard setup is
     * the path, and the advanced one is a link beside it.
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
                                hits = host.client.searchProvider(transport, pendingEmail, null);
                            }
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

    /** One offered way to connect one domain: a server and a way in. */
    private static final class SetupOption {
        /**
         * The discovered service this connects, or null when nothing
         * discovered it: a provider sign-in, or manual entry.
         *
         * <p>Kept beside the label, which is the same thing in the
         * reader's words. What reads it is the submission section, which
         * a JMAP option has nothing to offer: RFC 8621 submits through
         * the session it reads from, so an SMTP server under it would be
         * a second account nobody asked for.
         */
        final String service;

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

        SetupOption(
                String service,
                String label,
                String detail,
                String baseUrl,
                String resource,
                AuthMethod method,
                String login) {
            this.service = service;
            this.label = label;
            this.detail = detail;
            this.baseUrl = baseUrl;
            this.resource = resource;
            this.method = method;
            this.login = login;
        }

        /**
         * Whether reading mail through this option asks where to send: IMAP,
         * Graph and Gmail do, manual entry being an IMAP server typed by
         * hand.
         */
        boolean asksSubmission() {
            return "imap".equals(service) || readsOverApi() || isManual();
        }

        /**
         * Whether this option reads mail over a provider API, which sends
         * through the same API and nothing else.
         */
        boolean readsOverApi() {
            return "msgraph".equals(service) || "gmail".equals(service);
        }

        /** Whether this option asks for a server instead of proposing one. */
        boolean isManual() {
            return service == null && method == null;
        }

        /** Whether this option signs in through a browser grant. */
        boolean isOauth() {
            return method != null
                    && method.type != AuthMethod.Type.PASSWORD
                    && method.type != AuthMethod.Type.BEARER;
        }
    }

    /**
     * One offered way to send: a server, or nowhere.
     *
     * <p>Its own type rather than another {@link SetupOption}, because it
     * is not a way to connect a domain. Nobody picks between reading mail
     * and sending it: the two ride together, the reading one decides how
     * the account signs in, and this one only says where the messages go.
     */
    private static final class SubmitOption {
        final String label;

        /** The host a row names, null for the row that connects nothing. */
        final String detail;

        /**
         * Where mail is submitted, null until manual entry answers and
         * for the row that says not to send at all.
         */
        String url;

        /** Whether picking it asks for a server rather than proposing one. */
        final boolean manual;

        /**
         * What the mail grant asks for on its behalf, null when nothing: it
         * signs in with the mail connection's credential, so a browser
         * grant for mail has to cover sending too.
         */
        final String scope;

        /**
         * The provider API it submits through (`msgraph`, `gmail`), null for
         * SMTP and the rows around it. Only an account reading over that API
         * can: its token is the API's, where SMTP takes the mail server's.
         */
        final String through;

        /**
         * How an SMTP row signs in, null for the rows that name no method.
         * It signs in with the mail connection's credential, so only a row
         * of the same kind as the mail sign-in can use it.
         */
        final AuthMethod.Type auth;

        SubmitOption(String label, String detail, String url, boolean manual) {
            this(label, detail, url, manual, null, null, null);
        }

        SubmitOption(
                String label,
                String detail,
                String url,
                boolean manual,
                String scope,
                String through,
                AuthMethod.Type auth) {
            this.label = label;
            this.detail = detail;
            this.url = url;
            this.manual = manual;
            this.scope = scope;
            this.through = through;
            this.auth = auth;
        }

        /** Whether it is the row that sends nothing. */
        boolean none() {
            return url == null && !manual && through == null;
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
         * Where this domain sends, for mail and for nothing else.
         *
         * <p>Every other domain leaves these empty: contacts and calendars
         * have nothing to submit, and a mail account reading over JMAP
         * submits through the session it already has.
         */
        final List<SubmitOption> submitOptions = new ArrayList<>();

        final List<android.widget.RadioButton> submitButtons = new ArrayList<>();

        /** Everything drawn under the submission heading, hidden with it. */
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
     * Fills the setup screen with every domain this address offers.
     *
     * <p>The standard setup shows one card, a row per domain it can connect,
     * each ticked: it answers the configuration question itself, with the best
     * sign-in of the best configuration found ({@link #standardOption}), so
     * what is left is what the user actually decides, which of the three to
     * keep on this phone. A domain it cannot connect is not shown. Under the
     * card, the one password every password domain shares, or a word on the
     * browser hop.
     *
     * <p>The advanced setup shows a switch per domain and its configurations
     * under it: each domain takes one or none, the radio deselects, so an
     * address that offers calendars is not obliged to connect them. Reached
     * from the standard screen, it keeps the domains that were ticked and the
     * configuration the standard setup picked for each.
     */
    private void showSetup() {
        ((TextView) host.findViewById(R.id.domain_email)).setText(pendingEmail);

        Map<PimDomain, DomainSetup> previous = new java.util.EnumMap<>(PimDomain.class);
        previous.putAll(setups);

        LinearLayout container = host.findViewById(R.id.domain_container);
        container.removeAllViews();
        setups.clear();

        AccountEntry existing = host.accountFor(pendingEmail);
        boolean discovered = false;
        boolean connectable = false;

        for (PimDomain domain : PimDomain.values()) {
            DomainSetup setup = new DomainSetup(domain);
            setup.options.addAll(optionsFor(domain));
            discovered |= !setup.options.isEmpty();
            if (simpleSetup()) {
                setup.selected = standardOption(setup);
                setup.enabled = setup.selected != null;
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
                setup.submitSelected = firstOffered(setup);
                if (!simpleSetup()) {
                    // NOTE: offered only beside reading over the same API,
                    // which renderSection works out as the reading choice
                    // moves.
                    if (discovered("msgraph")) {
                        setup.submitOptions.add(
                                new SubmitOption(
                                        host.getString(R.string.config_msgraph),
                                        "graph.microsoft.com",
                                        PimalayaClient.msgraphBase(pendingEmail),
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
                                        PimalayaClient.googleBase(pendingEmail),
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
                        // The row for none, so the group opens on an answer
                        // rather than on nothing: not sending is a choice,
                        // and it is the only one this address offers.
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
        host.showAuth(MainActivity.STEP_DOMAIN);
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

    /**
     * The standard setup's card: a row per domain it can connect, its glyph
     * on a tile, its name, and a tick.
     */
    private View domainCard(AccountEntry existing) {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_group);
        card.setClipToOutline(true);

        for (DomainSetup setup : setups.values()) {
            if (!connectable(setup)) {
                continue;
            }
            if (card.getChildCount() > 0) {
                card.addView(rowDivider());
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
     * One domain's row inside a card: its glyph on a tile, its name over an
     * optional status line, and a trailing view.
     */
    private LinearLayout domainRow(PimDomain domain, String status, boolean error, View trailing) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(host.ui.dp(60));
        row.setPadding(host.ui.dp(14), host.ui.dp(8), host.ui.dp(8), host.ui.dp(8));
        TypedValue ripple = new TypedValue();
        host.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setForeground(host.getDrawable(ripple.resourceId));

        android.widget.ImageView glyph = new android.widget.ImageView(host);
        glyph.setImageResource(domain.icon);
        glyph.setBackgroundResource(R.drawable.tile_page);
        glyph.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        glyph.setPadding(host.ui.dp(6), host.ui.dp(6), host.ui.dp(6), host.ui.dp(6));
        glyph.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        host.ui.resolveColor(android.R.attr.textColorPrimary)));
        LinearLayout.LayoutParams glyphParams =
                new LinearLayout.LayoutParams(host.ui.dp(32), host.ui.dp(32));
        glyphParams.setMarginEnd(host.ui.dp(14));
        row.addView(glyph, glyphParams);

        LinearLayout text = new LinearLayout(host);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(host);
        name.setText(domain.label);
        name.setTextSize(16);
        name.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
        text.addView(name);
        if (status != null) {
            TextView line = new TextView(host);
            line.setText(status);
            line.setTextSize(13);
            line.setTextColor(
                    host.ui.resolveColor(
                            error ? android.R.attr.colorError : android.R.attr.textColorSecondary));
            text.addView(line);
        }
        row.addView(
                text,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (trailing != null) {
            row.addView(trailing);
        }
        return row;
    }

    /** The line between two rows of a card, inset past the glyph. */
    private View rowDivider() {
        View divider = new View(host);
        divider.setBackgroundResource(R.color.divider_light);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMarginStart(host.ui.dp(60));
        divider.setLayoutParams(params);
        return divider;
    }

    /**
     * The standard setup's way into one domain, or null when it offers none.
     *
     * <p>Google's and Microsoft's own APIs first, which sign in through the
     * app's registration at either; elsewhere the best-ranked configuration
     * found, JMAP over the rest, then OAuth over an API token over a
     * password. The user picks the domains and the setup picks the rest.
     */
    private SetupOption standardOption(DomainSetup setup) {
        SetupOption best = null;
        for (SetupOption option : setup.options) {
            if (option.method == null) {
                continue;
            }
            if (best == null || standardRank(option) < standardRank(best)) {
                best = option;
            }
        }
        return best;
    }

    /** The standard setup's order: the service first, then the sign-in. */
    private int standardRank(SetupOption option) {
        int service = proprietary(option) ? 0 : 1 + serviceRank(option.service);
        return service * 10 + authRank(option.method.type);
    }

    /**
     * Whether an option reads Google's or Microsoft's own API: a discovered
     * Gmail, Google Calendar or Graph service, or a provider sign-in to the
     * People API or Graph.
     */
    private boolean proprietary(SetupOption option) {
        if (option.service == null) {
            return option.baseUrl != null
                    && (option.baseUrl.equals(PimalayaClient.googleBase(pendingEmail))
                            || option.baseUrl.equals(PimalayaClient.msgraphBase(pendingEmail)));
        }
        switch (option.service) {
            case "gmail":
            case "gcal":
            case "msgraph":
            case "msgraphCalendar":
                return true;
            default:
                return false;
        }
    }

    /** Whether this domain can be switched on at all, in the running mode. */
    private boolean connectable(DomainSetup setup) {
        return simpleSetup() ? setup.selected != null : !setup.options.isEmpty();
    }

    /**
     * Sends a standard setup that has nothing to offer to the advanced one.
     *
     * <p>An address whose every service wants a browser grant or a token
     * leaves the standard screen with three switches that cannot be turned on,
     * which reads as an app that cannot connect this provider rather than as a
     * setup that asked the wrong way in.
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
     * One domain's section in the advanced setup: a switch carrying its glyph
     * and its name, and the configurations under it, manual entry last.
     *
     * <p>Switched off to begin with, unless the standard screen it came from
     * had it ticked: an address that offers three domains is not a request
     * for three.
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
        toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(setup.domain.icon, 0, 0, 0);
        toggle.setCompoundDrawablePadding(host.ui.dp(12));
        toggle.setCompoundDrawableTintList(
                android.content.res.ColorStateList.valueOf(
                        host.ui.resolveColor(android.R.attr.textColorPrimary)));
        toggle.setOnCheckedChangeListener(
                (view, checked) -> {
                    setup.enabled = checked;
                    renderSection(setup);
                    resetSetupContinue();
                });
        section.addView(toggle);

        if (alreadyConnected) {
            section.addView(note(R.string.domain_connected));
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

        if (setup.domain == PimDomain.MAIL) {
            addSubmission(section, setup);
        }

        renderSection(setup);
        return section;
    }

    /**
     * Draws where mail is sent from, in the advanced setup: SMTP by name,
     * what was discovered, manual entry and a row for not sending at all.
     *
     * <p>The standard setup draws nothing: it connects the best endpoint
     * found with mail ({@link #firstOffered}), and an address that publishes
     * none is saved without one, a sender being addable from the account's
     * settings.
     */
    private void addSubmission(LinearLayout section, DomainSetup setup) {
        TextView heading = note(R.string.send_mail_advanced);
        setup.submitViews.add(heading);
        section.addView(heading);

        for (SubmitOption option : setup.submitOptions) {
            android.widget.RadioButton button = new android.widget.RadioButton(host);
            button.setText(submitLabel(option));
            button.setTextSize(15);
            button.setPadding(host.ui.dp(8), host.ui.dp(10), host.ui.dp(8), host.ui.dp(10));
            button.setOnClickListener(
                    view -> {
                        setup.submitSelected = option;
                        if (option.manual) {
                            // NOTE: asked for now rather than during the
                            // sign-in sequence, where the mail server's own
                            // manual entry is asked for. Nothing signs in to
                            // a submission endpoint of its own: it uses the
                            // credential mail used, so there is no step of
                            // its own to ask inside.
                            promptManualSubmission(setup, option);
                        }
                        renderSection(setup);
                    });
            setup.submitButtons.add(button);
            setup.submitViews.add(button);
            section.addView(button);
        }
    }

    /** A submission row's label: the protocol, and the server it names. */
    private String submitLabel(SubmitOption option) {
        String auth = option.auth == null ? "" : " · " + authName(option.auth);
        if (option.detail != null) {
            return option.label + " (" + option.detail + ")" + auth;
        }
        return option.url == null ? option.label : option.label + " (" + hostOf(option.url) + ")";
    }

    /** A secondary line under a section's switch. */
    private TextView note(int text) {
        TextView note = new TextView(host);
        note.setText(text);
        note.setTextSize(13);
        note.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
        return note;
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

        // NOTE: the advanced setup's rows alone. A pick the reading choice
        // no longer allows falls back to the first row it does, which is
        // never empty: not sending is allowed beside every reader.
        if (!setup.submitButtons.isEmpty()
                && (setup.submitSelected == null || !offers(setup, setup.submitSelected))) {
            for (SubmitOption option : setup.submitOptions) {
                if (offers(setup, option)) {
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
            android.widget.RadioButton button = setup.submitButtons.get(index);
            button.setChecked(option == setup.submitSelected);
            button.setVisibility(sends(setup) && offers(setup, option) ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Whether a sending row goes with what mail is read through.
     *
     * <p>Reading over a provider API sends through that API, and SMTP goes
     * with anything else: Microsoft's SMTP signs in with Outlook's token
     * and Graph with Graph's, so pairing one with the other would be a
     * second credential to store and renew for the same mailbox. Gmail's
     * one token would cover both, and is kept to the same shape.
     */
    private static boolean offers(DomainSetup setup, SubmitOption option) {
        if (option.none()) {
            return true;
        }
        String api =
                setup.selected != null && setup.selected.readsOverApi()
                        ? setup.selected.service
                        : null;
        if (!java.util.Objects.equals(option.through, api)) {
            return false;
        }
        if (option.auth == null || setup.selected == null) {
            return true;
        }
        // NOTE: a manual mail server is signed in to with a password.
        AuthMethod.Type reading =
                setup.selected.method == null
                        ? AuthMethod.Type.PASSWORD
                        : setup.selected.method.type;
        return authKind(option.auth) == authKind(reading);
    }

    /** An authentication method's kind: OAuth's grants are one kind. */
    private static int authKind(AuthMethod.Type type) {
        switch (type) {
            case PASSWORD:
                return 2;
            case BEARER:
                return 1;
            default:
                return 0;
        }
    }

    /** The first sending row that goes with what mail is read through. */
    private static SubmitOption firstOffered(DomainSetup setup) {
        for (SubmitOption option : setup.submitOptions) {
            if (!option.none() && !option.manual && offers(setup, option)) {
                return option;
            }
        }
        return null;
    }

    /**
     * Whether this section has a submission question to ask at all.
     *
     * <p>Only once mail is read over IMAP or Graph, since the rows follow
     * that choice and there is nothing to follow before it. A switched-off
     * domain has none, and neither has one reading over JMAP: RFC 8621
     * submits through the session it reads from, so a server offered under
     * it would be a second account to sign in to for something the first
     * one already does.
     */
    private static boolean sends(DomainSetup setup) {
        return setup.enabled && setup.selected != null && setup.selected.asksSubmission();
    }

    /**
     * Brings the domain step's buttons in line with the plan: Continue
     * available once at least one domain is on and every domain that is on
     * knows what to connect to, and, in the standard setup, the shared
     * password typed when one is asked for.
     *
     * <p>Nothing is signed in to yet. What the screen collects is the plan,
     * and the interactions it needs are worked out from the whole plan at
     * once, which is the only way to know that two domains behind one
     * authorization server cost one browser hop rather than two.
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

    /**
     * Puts the running step's continue on its loader: the books step's
     * FAB, or the domain step's button everywhere else.
     */
    private void busy() {
        if (host.authStep() == MainActivity.STEP_BOOKS) {
            host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
        } else {
            host.setAuthLoading(R.id.domain_connect, R.id.domain_progress, true);
        }
    }

    /** The domain step's Continue back to idle. */
    void resetConfigContinue() {
        if (!verifying) {
            resetSetupContinue();
        }
    }

    /**
     * The ticked domains the standard setup's one password signs in to:
     * every one whose chosen sign-in is a password under a known login.
     *
     * <p>A password option with no login, as when a bare domain was typed
     * rather than an address, keeps its own prompt in the sign-in sequence.
     */
    private List<DomainSetup> sharedPassword() {
        List<DomainSetup> shared = new ArrayList<>();
        if (!simpleSetup()) {
            return shared;
        }
        for (DomainSetup setup : setups.values()) {
            if (setup.ready()
                    && setup.selected.method != null
                    && setup.selected.method.type == AuthMethod.Type.PASSWORD
                    && setup.selected.login != null
                    && !setup.selected.login.isEmpty()) {
                shared.add(setup);
            }
        }
        return shared;
    }

    /** Shows the wrong-password line in place of the field's note, or restores it. */
    private void showPasswordError(boolean wrong) {
        TextView note = host.findViewById(R.id.domain_password_note);
        note.setText(wrong ? R.string.password_wrong : R.string.password_shared);
        note.setTextColor(
                host.ui.resolveColor(
                        wrong ? android.R.attr.colorError : android.R.attr.textColorSecondary));
    }

    /**
     * Everything this domain was discovered to be connectable with: one option
     * per configuration and authentication method. Manual entry is not among
     * them, being an answer the discovery did not give: the advanced setup adds
     * it, so an empty list here is the address offering this domain nothing.
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
            for (AuthMethod method : methods) {
                options.add(
                        new SetupOption(
                                config.service,
                                protocolName(config.service),
                                detail,
                                baseUrl,
                                resourceOf(config, method),
                                method,
                                login));
            }
        }

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
     * <p>They are hand-written rather than discovered because neither Google
     * nor Microsoft publishes a CardDAV SRV record or well-known: the sign-in
     * itself is the only way to find out, so the endpoints and scopes are the
     * app's own knowledge of those two.
     */
    private void addProviderOptions(List<SetupOption> options) {
        if ("provider:google".equals(matchedProvider)) {
            // NOTE: the provider rule names Google's CardDAV itself now, so
            // the hand-written one would be the same row twice.
            if (!providerFound("carddav")) {
                options.add(
                        providerOption(
                                R.string.config_carddav,
                                PimalayaClient.googleCarddavBase(pendingEmail),
                                Oauth.GOOGLE_AUTH_ENDPOINT,
                                Oauth.GOOGLE_TOKEN_ENDPOINT,
                                Oauth.GOOGLE_SCOPE));
            }
            options.add(
                    providerOption(
                            R.string.config_google_api,
                            PimalayaClient.googleBase(pendingEmail),
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

    /** Whether discovery named this service, whatever found it. */
    private boolean discovered(String service) {
        for (ServiceConfig config : searchedConfigs) {
            if (service.equals(config.service)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a provider rule named this service. */
    private boolean providerFound(String service) {
        for (ServiceConfig config : searchedConfigs) {
            if (service.equals(config.service) && fromProvider(config)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a config came from a fixed provider rule (Google, Microsoft). */
    private static boolean fromProvider(ServiceConfig config) {
        return config.source != null && config.source.startsWith("provider:");
    }

    private SetupOption providerOption(
            int label, String baseUrl, String authEndpoint, String tokenEndpoint, String scope) {
        return new SetupOption(
                null,
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
     *
     * <p>A provider rule's config asks for none: Google and Microsoft
     * authorize by scope, and answer a resource with {@code invalid_target}.
     */
    private static String resourceOf(ServiceConfig config, AuthMethod method) {
        if (fromProvider(config)) {
            return null;
        }
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

    /**
     * Auth precedence: OAuth 2.0 over API token over password, the device grant
     * last among the OAuth ones since a phone has a browser.
     */
    private static int authRank(AuthMethod.Type type) {
        switch (type) {
            case PASSWORD:
                return 3;
            case BEARER:
                return 2;
            case OAUTH_DEVICE_AUTHORIZATION_GRANT:
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
     * authorization server and audience ({@link #audienceOf}), so the domains
     * one consent actually covers cost one hop: this is where "mail and
     * calendars on one JMAP session" becomes a single step instead of two
     * identical ones.
     */
    private List<AuthStep> planAuthSteps() {
        List<AuthStep> steps = new ArrayList<>();

        for (DomainSetup setup : setups.values()) {
            // NOTE: a domain the shared password already signed in to has
            // nothing left to ask.
            if (!setup.ready() || setup.credential != null) {
                continue;
            }
            SetupOption option = setup.selected;

            AuthStep shared = null;
            if (option.isOauth()) {
                for (AuthStep candidate : steps) {
                    if (candidate.option.isOauth()
                            && sameAuthorizationServer(candidate.option.method, option.method)
                            && audienceOf(candidate.option).equals(audienceOf(option))) {
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
        verifying = false;
        oauthGroup.clear();
        for (DomainSetup setup : setups.values()) {
            setup.credential = null;
        }
        host.showAuth(MainActivity.STEP_DOMAIN);
        resetSetupContinue();
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
     *
     * <p>The standard setup keeps the domain and the count and drops the
     * protocol, which is a thing it deliberately never showed. It cannot drop
     * more than that: three password prompts in a row, each titled the same,
     * read as one prompt failing twice.
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
        String named = String.join(", ", domains);

        if (simpleSetup()) {
            return authSteps.size() == 1
                    ? host.getString(R.string.setup_step_one, named)
                    : host.getString(
                            R.string.setup_step_domain,
                            named,
                            authStepIndex + 1,
                            authSteps.size());
        }

        String kind =
                step.option.method == null
                        ? host.getString(R.string.domain_manual)
                        : authName(step.option.method.type);

        return host.getString(
                R.string.setup_step_title, authStepIndex + 1, authSteps.size(), named, kind);
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
            // NOTE: submission signs in with mail's credential, so the grant
            // that reads mail has to cover sending it too.
            if (domain == PimDomain.MAIL && sends(setup) && setup.submitSelected != null) {
                addScopes(scopes, setup.submitSelected.scope);
            }
        }

        String scope = scopes.isEmpty() ? null : String.join(" ", scopes);
        // NOTE: Google and Microsoft register no client dynamically, so their
        // grants run with the app's own registration.
        if (Oauth.isGoogle(option.method.authorizationEndpoint)) {
            // NOTE: a domain added onto an account Google already granted
            // asks for the union, so the one new grant replaces the old one
            // on every domain rather than living beside it.
            AccountEntry existing = existingAccount(pendingEmail);
            if (existing != null) {
                scope = Oauth.union(existing.grantedScope(Oauth.GOOGLE_CLIENT_ID), scope);
            }
            oauth.startGoogleOauth(pendingEmail, scope, option.baseUrl);
            return;
        }
        if (Oauth.MICROSOFT_AUTH_ENDPOINT.equals(option.method.authorizationEndpoint)) {
            oauth.startMicrosoftOauth(pendingEmail, scope, option.baseUrl);
            return;
        }
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

    /** The account already stored for this address, or null. */
    private AccountEntry existingAccount(String email) {
        for (AccountEntry entry : host.accounts) {
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

    /** What one option's grant is for ({@link Oauth#audience}). */
    private static String audienceOf(SetupOption option) {
        return option.method == null
                ? Oauth.audience(null, option.resource, null)
                : Oauth.audience(
                        option.method.authorizationEndpoint, option.resource, option.method.scope);
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
     * before that leaves everything untouched. The last five parameters
     * carry the refresh material of an OAuth account and the scopes it was
     * granted (all null for a password one), so expired access tokens can
     * be refreshed on later syncs and a later grant can ask for the union.
     */
    void connect(
            Account candidate,
            String email,
            String refreshToken,
            String tokenEndpoint,
            String clientId,
            String clientSecret,
            String scope) {
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
                                clientSecret,
                                scope);

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

    /** Plans and runs the sign-ins the domains still owe, then commits. */
    private void runSignIns() {
        authSteps = planAuthSteps();
        authStepIndex = 0;
        runNextAuthStep();
    }

    /**
     * Tries the standard setup's one password against every domain it covers,
     * side by side, and reports per domain.
     *
     * <p>Every server accepting moves on to whatever else the plan owes (a
     * browser grant for a domain that signs in that way). Every server
     * refusing it says so on the field and saves nothing. Some refusing is
     * the result step's to settle: continue without them, or set them up in
     * the advanced setup.
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
     * Signs in to one domain the way its first sync will, and reports what
     * went wrong, null when nothing did: the mail session opens and
     * authenticates, the address books and calendars list.
     */
    private Exception probe(PimDomain domain, Account account) {
        try {
            switch (domain) {
                case MAIL:
                    try (org.pimalaya.client.MailSession session =
                            PimalayaClient.openMail(account)) {
                        return null;
                    }
                case CONTACTS:
                    try (Transport transport = new Transport()) {
                        host.client.listAddressbooks(transport, account);
                    }
                    return null;
                default:
                    try (Transport transport = new Transport()) {
                        host.client.listCalendars(transport, account);
                    }
                    return null;
            }
        } catch (Exception error) {
            Log.w("pimalaya", "password check failed for " + domain.id, error);
            return error;
        }
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
     * The result step: the one password reached some domains and not
     * others. A row per domain it was tried on, connected or not, and the
     * two ways on.
     */
    private void showResult(List<DomainSetup> shared, Map<PimDomain, Exception> refused) {
        ((TextView) host.findViewById(R.id.result_email)).setText(pendingEmail);

        List<String> names = new ArrayList<>();
        for (PimDomain domain : refused.keySet()) {
            names.add(host.getString(domain.label).toLowerCase(java.util.Locale.getDefault()));
        }
        String named = String.join(", ", names);
        ((TextView) host.findViewById(R.id.result_message))
                .setText(host.getString(R.string.result_message, named));

        LinearLayout container = host.findViewById(R.id.result_container);
        container.removeAllViews();
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_group);
        card.setClipToOutline(true);
        for (DomainSetup setup : shared) {
            Exception failure = refused.get(setup.domain);
            android.widget.ImageView mark = new android.widget.ImageView(host);
            mark.setImageResource(failure == null ? R.drawable.ic_check : R.drawable.ic_error);
            mark.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            host.ui.resolveColor(
                                    failure == null
                                            ? android.R.attr.colorAccent
                                            : android.R.attr.colorError)));
            mark.setPadding(host.ui.dp(12), 0, host.ui.dp(12), 0);
            String status;
            if (failure == null) {
                status = host.getString(R.string.result_connected);
            } else if (wrongPassword(failure)) {
                status = host.getString(R.string.result_refused);
            } else {
                status = host.getString(R.string.connect_failed);
            }
            if (card.getChildCount() > 0) {
                card.addView(rowDivider());
            }
            card.addView(domainRow(setup.domain, status, failure != null, mark));
        }
        container.addView(card);

        Button onward = host.findViewById(R.id.result_continue);
        onward.setText(host.getString(R.string.result_continue, named));
        onward.setOnClickListener(
                view -> {
                    for (PimDomain domain : refused.keySet()) {
                        setups.get(domain).enabled = false;
                    }
                    // NOTE: back on the domain step, the refused domains
                    // unticked, while the rest of the plan runs.
                    host.showAuth(MainActivity.STEP_DOMAIN);
                    LinearLayout domains = host.findViewById(R.id.domain_container);
                    domains.removeAllViews();
                    domains.addView(domainCard(host.accountFor(pendingEmail)));
                    host.setAuthLoading(R.id.domain_connect, R.id.domain_progress, true);
                    runSignIns();
                });

        Button advancedLink = host.findViewById(R.id.result_advanced);
        advancedLink.setText(host.getString(R.string.result_advanced, named));
        advancedLink.setOnClickListener(
                view -> {
                    for (DomainSetup setup : setups.values()) {
                        setup.credential = null;
                    }
                    switchToAdvanced();
                });

        host.showAuth(MainActivity.STEP_RESULT);
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
            String baseUrl = setup.baseUrl;
            String submitUrl =
                    setup.domain == PimDomain.MAIL && sends(setup) && setup.submitSelected != null
                            ? setup.submitSelected.url
                            : null;
            // NOTE: a token signs in to IMAP and SMTP with SASL XOAUTH2,
            // which names its user beside the token; the URL is where that
            // user lives (RFC 5092 section 3.2), the credential staying the
            // bare token every other protocol sends.
            if (setup.domain == PimDomain.MAIL && setup.credential.login.isEmpty()) {
                if (baseUrl.startsWith("imap")) {
                    baseUrl = PimalayaClient.withUser(baseUrl, connectedEmail);
                }
                if (submitUrl != null && submitUrl.startsWith("smtp")) {
                    submitUrl = PimalayaClient.withUser(submitUrl, connectedEmail);
                }
            }
            if (connectedAccount == null) {
                connectedAccount = AccountEntry.empty(connectedEmail);
            }
            connectedAccount =
                    connectedAccount.with(setup.domain, baseUrl, submitUrl, setup.credential);
        }
        if (connectedAccount == null) {
            return;
        }

        Account contacts = connectedAccount.server(PimDomain.CONTACTS);
        if (contacts == null) {
            finishOnboarding();
            return;
        }

        busy();
        String email = connectedEmail;
        host.io.execute(
                () -> {
                    try {
                        List<Addressbook> fetched;
                        try (Transport transport = new Transport()) {
                            fetched = host.client.listAddressbooks(transport, contacts);
                        }
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
     * it off later. The checkbox box sits at the panel's 24dp inset, aligned with the
     * title and paragraph above. Same chrome as the config panel.
     */
    private void openBooksSelection(String email, List<Addressbook> books) {
        connectedEmail = email;
        bookChoices = new ArrayList<>();

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
        commitBooks(subscribed);
    }

    /**
     * The standard setup's commit, selection-free: every addressbook
     * subscribed with phone mirroring, then the first sync straight to the contacts list.
     */
    private void confirmAllBooks(String email) {
        connectedEmail = email;
        java.util.Set<String> subscribed = new java.util.HashSet<>();
        for (Addressbook book : pendingBooks) {
            subscribed.add(book.url);
        }
        commitBooks(subscribed);
    }

    /**
     * The flow's real commit, shared by the books step's Continue and
     * the standard setup: persists the connected account and its
     * addressbooks, subscribes the given ones with phone mirroring on
     * by default, asks the contacts permission that setup needs, then runs
     * the account's first sync.
     */
    private void commitBooks(java.util.Set<String> subscribed) {
        host.base.replaceAddressbooks(connectedEmail, pendingBooks);
        new PimdirCollections(host.pimdir, host)
                .replace(
                        connectedEmail,
                        PimdirSummary.CONTACT,
                        PimdirCollections.of(connectedEmail, pendingBooks));

        for (Addressbook book : pendingBooks) {
            boolean on = subscribed.contains(book.url);
            host.base.setBookState(book.url, on, on, on);
        }

        // NOTE: subscribed books mirror into the Contacts app.
        if (!host.hasContactsPermission()) {
            host.requestPermissions(
                    new String[] {
                        Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS
                    },
                    MainActivity.REQUEST_CONTACTS);
        }

        finishOnboarding();
    }

    /**
     * Persists everything the run connected, as one account, and hands it to
     * the first sync, which fills every domain it covers before the app shows
     * any of them.
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
                            connected.connection(domain).submitUrl,
                            connected.credential(domain));
        }

        // NOTE: a grant that asked for the union (Google's incremental
        // authorization) covers the domains an earlier grant of the same
        // client signed in, so they move onto it and the old one is dropped:
        // one refresh token for the account.
        for (AccountCredential granted : connected.credentials().values()) {
            for (PimDomain domain : merged.regrantable(granted)) {
                merged =
                        host.store.connect(
                                merged.email,
                                domain,
                                merged.connection(domain).baseUrl,
                                merged.connection(domain).submitUrl,
                                granted);
            }
        }

        AccountEntry stored = merged;
        host.accounts.removeIf(entry -> entry.email.equals(stored.email));
        host.accounts.add(stored);

        busy();
        host.syncConnected(stored);
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
     * Everywhere this address submits mail, from the same discovery run
     * that found where it reads it, best first.
     *
     * <p>Implicit TLS behind {@code smtps://} first, then STARTTLS behind
     * {@code smtp://}, which the session upgrades before it authenticates.
     * A plain endpoint is logged and not offered: nothing is ever submitted
     * in the clear.
     */
    private List<SubmitOption> submissionOptions() {
        List<SubmitOption> implicit = new ArrayList<>();
        List<SubmitOption> upgraded = new ArrayList<>();

        for (ServiceConfig config : searchedConfigs) {
            if (!"smtp".equals(config.service) || config.host == null) {
                continue;
            }
            boolean tls = "tls".equalsIgnoreCase(config.security);
            if (!tls && !"starttls".equalsIgnoreCase(config.security)) {
                Log.w(
                        "pimalaya",
                        "skip smtp at " + config.host + ": unsupported security "
                                + config.security);
                continue;
            }
            String authority = config.host + ":" + config.port;
            String url = (tls ? "smtps://" : "smtp://") + authority;

            List<AuthMethod> methods = new ArrayList<>(config.auth);
            java.util.Collections.sort(
                    methods,
                    (left, right) -> Integer.compare(authRank(left.type), authRank(right.type)));
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
                                        method == null ? null : method.scope,
                                        null,
                                        method == null ? null : method.type));
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

    /**
     * Asks for the server this account sends through, and remembers it on
     * the row that was picked.
     *
     * <p>A host and a port, as the mail server's own manual entry asks for
     * one: STARTTLS on 587, implicit TLS otherwise, and 465 when none was
     * typed ({@link PimalayaClient#submitUrl}). Nothing is signed in to,
     * the submission using the credential mail signed in with.
     */
    private void promptManualSubmission(DomainSetup setup, SubmitOption option) {
        EditText field =
                host.ui.field(R.string.manual_submit_server, hostOf(pendingEmail));

        LinearLayout fields = new LinearLayout(host);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(host.ui.dp(24), host.ui.dp(8), host.ui.dp(24), 0);
        fields.addView(field);

        new AlertDialog.Builder(host)
                .setTitle(R.string.send_mail_advanced)
                .setMessage(R.string.manual_submit_message)
                .setView(fields)
                .setPositiveButton(
                        R.string.password_submit,
                        (dialog, which) -> {
                            String entered = field.getText().toString().trim();
                            option.url = entered.isEmpty() ? null : submitUrl(entered);
                            renderSection(setup);
                        })
                .setNegativeButton(
                        android.R.string.cancel,
                        (dialog, which) -> {
                            option.url = null;
                            renderSection(setup);
                        })
                .show();
    }

    /** What was typed, as the endpoint a submission opens. */
    private static String submitUrl(String entered) {
        return PimalayaClient.submitUrl(entered);
    }

    private String endpointUrl(ServiceConfig config) {
        if ("msgraph".equals(config.service) || "msgraphCalendar".equals(config.service)) {
            return PimalayaClient.msgraphBase(pendingEmail);
        }
        if ("gmail".equals(config.service) || "gcal".equals(config.service)) {
            return PimalayaClient.googleBase(pendingEmail);
        }
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
