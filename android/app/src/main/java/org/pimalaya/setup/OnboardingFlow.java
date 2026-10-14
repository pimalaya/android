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
import org.pimalaya.MainActivity;
import org.pimalaya.R;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.Endpoints;
import org.pimalaya.account.Oauth;
import org.pimalaya.account.PimDomain;
import org.pimalaya.calendar.PhoneCalendars;
import org.pimalaya.client.Account;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.AuthMethod;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.ServiceConfig;
import org.pimalaya.client.Transport;
import org.pimalaya.phone.Accounts;
import org.pimalaya.phone.PhoneMirror;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.sync.BackgroundCheck;
import org.pimalaya.ui.PillField;

/**
 * The connection wizard behind the auth panel: the email (or server)
 * step with its parallel discovery, the domain step proposing one
 * option per protocol and authentication variant, the sign-in page
 * with a card per server, and the addressbook selection whose commit
 * persists the account and runs the first sync. The OAuth grants live
 * in {@link OauthFlow}, which lands its redeemed tokens back here
 * through {@link #connect}; the host keeps the step navigation (the
 * flipper, the bar) and calls in through {@link #open} and
 * {@link #refreshStep}. Nothing persists before the selection
 * confirms, so backing out of the flow leaves everything untouched.
 */
public final class OnboardingFlow {
    /** The auth flow's steps, inside its own flipper under one bar. */
    public static final int STEP_EMAIL = 0;

    /** What to connect this address for, once discovery has said what it can. */
    public static final int STEP_DOMAIN = 1;

    public static final int STEP_CONFIG = 2;
    public static final int STEP_BOOKS = 3;

    /** The standard setup's outcome when some servers refused the password. */
    public static final int STEP_RESULT = 4;

    /** The advanced setup's sign-ins, one card per server and login. */
    public static final int STEP_SIGNIN = 5;

    /** The OAuth client a server registers none of. */
    public static final int STEP_OAUTH = 6;

    private final MainActivity host;
    private final OauthFlow oauth;

    /** Onboarding state: the entered email and its searched configs. */
    private String pendingEmail;

    private List<ServiceConfig> searchedConfigs = new ArrayList<>();

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

    /**
     * The options under mail, the contacts and the calendars in both setups:
     * notifications and the phone apps, off until turned on and permitted.
     */
    private final SetupSwitches switches = new SetupSwitches();

    /**
     * Whether another app already fills the phone's Contacts app with this
     * address ({@link Accounts#elsewhere}), which keeps the books off it.
     */
    private boolean elsewhere;

    /** The same for the phone's calendars ({@link Accounts#calendarsElsewhere}). */
    private boolean calendarsElsewhere;

    public OnboardingFlow(MainActivity host, OauthFlow oauth) {
        this.host = host;
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
     * Scrolls the domain step to its end, so the password field and its
     * note sit above the keyboard. Delayed past the keyboard's resize of
     * the window, which would otherwise clip the scroll it answers.
     */
    private void revealPassword() {
        android.widget.ScrollView scroll = host.findViewById(R.id.domain_scroll);
        scroll.postDelayed(
                () -> scroll.smoothScrollTo(0, scroll.getChildAt(0).getHeight()), 300);
    }

    /**
     * Scrolls the step's page so {@code view}, a domain just ticked or the
     * card whose configuration was just picked, sits at its top with what it
     * opened below it. Posted past the layout the tick changes, as the
     * password's reveal is.
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
        host.showAuth(STEP_EMAIL);
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

        /** Whether this domain is switched on and has something to connect to. */
        boolean ready() {
            return enabled && selected != null;
        }
    }

    /**
     * One sign-in the selection owes: a card on the sign-in page, or a
     * browser hop.
     *
     * <p>Several domains when one sign-in covers them all, which is the whole
     * reason the sequence is planned rather than run per domain: domains
     * behind one authorization server are one browser hop, and domains on one
     * server under one login are one password.
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
            return option.method == null || option.method.type == AuthMethod.Type.PASSWORD;
        }

        /** Whether the "same as" box is ticked, its fields reused. */
        boolean reuses() {
            return same != null && same.isChecked();
        }
    }

    /**
     * Fills the setup screen with every domain this address offers.
     *
     * <p>The standard setup shows one card, a row per domain it can connect,
     * each ticked: it answers the configuration question itself, with the best
     * sign-in of the best configuration found ({@link SetupPlan#standardOption}), so
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
        host.showAuth(STEP_DOMAIN);
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
     * The tick putting the books in the phone's contacts, or the calendars
     * in its calendar. Turned on, it asks the mirror's permissions, and a
     * refusal turns it back off.
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
                    host.askMirrors(
                            java.util.EnumSet.of(mirror),
                            granted -> toggle.setChecked(switches.mirror(mirror, true, granted)));
                });
        boolean contacts = mirror == PhoneMirror.CONTACTS;
        return tickRow(
                contacts ? R.string.phone_contacts : R.string.phone_calendar,
                contacts ? R.string.phone_contacts_note : R.string.phone_calendar_note,
                toggle);
    }

    /**
     * The tick notifying the account's new mail. Turned on, it asks the
     * notifications permission, and a refusal turns it back off.
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
                    host.askNotifications(
                            granted -> toggle.setChecked(switches.notify(true, granted)));
                });
        return tickRow(R.string.background_notify, R.string.notify_mail_note, toggle);
    }

    /** An option's row under a hairline: its title over its line, then its tick. */
    private View tickRow(int titleText, int lineText, CheckBox toggle) {
        LinearLayout item = new LinearLayout(host);
        item.setOrientation(LinearLayout.VERTICAL);
        item.addView(rowDivider());

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(host.ui.dp(56));
        row.setPadding(host.ui.dp(60), host.ui.dp(8), host.ui.dp(8), host.ui.dp(8));
        TypedValue ripple = new TypedValue();
        host.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setForeground(host.getDrawable(ripple.resourceId));

        LinearLayout text = new LinearLayout(host);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(host);
        title.setText(titleText);
        title.setTextSize(16);
        title.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
        text.addView(title);
        TextView line = new TextView(host);
        line.setText(lineText);
        line.setTextSize(13);
        line.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
        text.addView(line);
        LinearLayout.LayoutParams textParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginEnd(host.ui.dp(12));
        row.addView(text, textParams);
        row.addView(toggle);
        row.setOnClickListener(view -> toggle.toggle());

        item.addView(row);
        return item;
    }

    /**
     * One domain's row inside a card: its glyph on a tile, its name over an
     * optional status line, and a trailing view.
     */
    private LinearLayout domainRow(PimDomain domain, String status, boolean error, View trailing) {
        return domainRow(domain, status, error, trailing, false);
    }

    /** The same row, its name in bold when it heads a card. */
    private LinearLayout domainRow(
            PimDomain domain, String status, boolean error, View trailing, boolean bold) {
        return domainRow(domain, host.getString(domain.label), status, error, trailing, bold);
    }

    /** The same row under a name of its own, for a card covering several. */
    private LinearLayout domainRow(
            PimDomain domain,
            String label,
            String status,
            boolean error,
            View trailing,
            boolean bold) {
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
        name.setText(label);
        name.setTextSize(16);
        if (bold) {
            name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        }
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
     * One domain's card in the advanced setup: a header with its glyph, its
     * name and a switch, and under it, while it is on, a radio row per
     * configuration, manual entry last, then mail's sending rows.
     *
     * <p>Switched off to begin with, unless the standard screen it came from
     * had it ticked: an address that offers three domains is not a request
     * for three.
     */
    private View sectionOf(DomainSetup setup, boolean alreadyConnected) {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_group);
        card.setClipToOutline(true);
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
                            : optionDetail(option.detail, option.method.type);
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
     * One radio row inside a card, under a hairline: the radio, then the
     * protocol over its server and sign-in. Not a RadioGroup, so a group can
     * start with nothing picked; the row as a whole picks.
     *
     * @return the row's radio, which the caller checks
     */
    private android.widget.RadioButton optionRow(
            LinearLayout into, String primary, String secondary, Runnable onPick) {
        LinearLayout item = new LinearLayout(host);
        item.setOrientation(LinearLayout.VERTICAL);
        item.addView(rowDivider());

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
     * Draws where mail is sent from, in the advanced setup: a sending heading
     * over SMTP by name, what was discovered, a row for not sending at all
     * and manual entry, whose server the sign-in page asks for.
     *
     * <p>The standard setup draws nothing: it connects the best endpoint
     * found with mail ({@link SetupPlan#firstOffered}), and an address that publishes
     * none is saved without one, a sender being addable from the account's
     * settings.
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
        return option.url == null ? option.label : option.label + " (" + hostOf(option.url) + ")";
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

    /** Puts the running step's main button on its loader. */
    public void busy() {
        switch (host.authStep()) {
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
        switch (host.authStep()) {
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
                        Integer.compare(domain.rank(left.service), domain.rank(right.service)));

        for (ServiceConfig config : configs) {
            String baseUrl = Endpoints.endpointUrl(config, pendingEmail);
            if (baseUrl == null) {
                continue;
            }
            String detail = config.url != null ? hostOf(config.url) : config.host;
            String login = config.username != null ? config.username : emailLogin();

            List<AuthMethod> methods = new ArrayList<>(config.auth);
            java.util.Collections.sort(
                    methods,
                    (left, right) -> Integer.compare(SetupPlan.authRank(left.type), SetupPlan.authRank(right.type)));
            for (AuthMethod method : methods) {
                options.add(
                        new SetupOption(
                                config.service,
                                protocolName(config.service),
                                detail,
                                baseUrl,
                                SetupPlan.resourceOf(config, method),
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
            if (service.equals(config.service) && SetupPlan.fromProvider(config)) {
                return true;
            }
        }
        return false;
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
     * Works out the sign-ins the whole selection needs, in order, one card
     * each.
     *
     * <p>Browser grants pool by authorization server and audience
     * ({@link SetupPlan#audienceOf}), so the domains one consent covers cost one hop.
     * A password or a token pools by server and login: mail, contacts and
     * calendars on one JMAP session are one password, not three. Manual entry
     * names a server of its own and is never pooled.
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
     * <p>Everything already signed in to this run is dropped with it. Half a
     * sequence is not half an account: leaving the completed steps behind
     * would persist an account covering fewer domains than the screen still
     * shows selected.
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
        host.showAuth(STEP_DOMAIN);
        resetSetupContinue();
    }

    /**
     * A grant that failed or was refused: the chain stops where it is, what
     * signed in keeps its credential, and the step's button comes back.
     */
    public void grantAborted() {
        connecting = false;
        resetConfigContinue();
    }

    /**
     * Back from the OAuth client page or the addressbooks: to the sign-in
     * page with nothing lost, or, for a standard setup that never showed
     * one, to the domains.
     */
    public void backToSignIn() {
        if (!signInPage) {
            abortAuthSteps();
            return;
        }
        connecting = false;
        host.showAuth(STEP_SIGNIN);
        resetConfigContinue();
    }

    /**
     * Plans the sign-ins the domains still owe and runs them: on the sign-in
     * page when one asks for something to type, or whenever the advanced setup
     * runs; straight to the browser otherwise, one grant after the other.
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
     * The sign-in page: a card per planned sign-in, its domains and server
     * on top, then what its method asks for.
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

        host.showAuth(STEP_SIGNIN);
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
                domainRow(step.domains.get(0), domainNames(step), signInDetail(step), false, null, true);
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
     * Where a card's password or token goes, shown before it does: each
     * endpoint's scheme and host, flagged when it sits outside the address's
     * domain, which is what a forged configuration would point at. Null for a
     * browser card, whose secret the app never holds, and for a server entered
     * by hand, which the user just named.
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
     * A card's second line: the protocol and its server, or that it was
     * entered by hand, and where mail sends through when it does.
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
     * Brings the cards in line with their state, and Connect with what they
     * hold: available once every card left to sign in has what it asks for. A
     * browser card does not hold it back, Connect running its grant.
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

    /** One server's check: a domain of a card, signed in the way it will sync. */
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
     * A server typed on a card that cannot be signed in to encrypted (plain
     * {@code imap://} or {@code http://}, say): said on the card, and nothing
     * is sent.
     */
    private void insecure(AuthStep step) {
        status(step, host.getString(R.string.server_insecure), true);
        resetConfigContinue();
    }

    /**
     * Connect on the sign-in page: tries every card still to sign in, side
     * by side, reporting each on its card; once every one has, runs the
     * browser cards left, then commits.
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
                    // account (finishOnboarding), which a destroyed activity must not drop.
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
     * Runs the next browser card not signed in yet, or commits once none is
     * left. A grant comes back through {@link #connect}, which calls this
     * again while the chain runs.
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
     * Runs one browser grant for every domain of the card, with their scopes
     * merged: through the app's own registration at Google and Microsoft,
     * through dynamic registration at an issuer, or with the user's own
     * client from the OAuth client page.
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
            SetupPlan.addScopes(scopes, setup.selected.method.scope);
            // NOTE: submission signs in with mail's credential, so the grant
            // that reads mail has to cover sending it too.
            if (domain == PimDomain.MAIL && sends(setup) && setup.submitSelected != null) {
                SetupPlan.addScopes(scopes, setup.submitSelected.scope);
                endpoints.add(setup.submitSelected.url);
            }
        }

        String scope = scopes.isEmpty() ? null : String.join(" ", scopes);
        String heading =
                domainNames(step) + " · " + (option.detail != null ? option.detail : hostOf(option.baseUrl));
        // NOTE: Google and Microsoft register no client dynamically, so their
        // grants run with the app's own registration, and only for their own
        // hosts: any other falls to a generic grant (Oauth.shipped).
        Oauth.Shipped shipped =
                own ? null : Oauth.shipped(option.method.authorizationEndpoint, endpoints);
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
                    grantedDomains(),
                    heading,
                    own);
            return;
        }
        oauth.promptOauthClient(
                pendingEmail,
                option.baseUrl,
                option.method.authorizationEndpoint,
                option.method.tokenEndpoint,
                scope,
                option.resource,
                heading,
                own);
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

    /**
     * The entered email as a login prefill; empty when the connection
     * field carried a bare domain or a URL instead.
     */
    private String emailLogin() {
        return pendingEmail != null && pendingEmail.contains("@") ? pendingEmail : "";
    }

    /**
     * Lands a redeemed browser grant on every domain of its card, then runs
     * the next grant while the chain does, or comes back to the sign-in page
     * for a card signed in on its own. Nothing persists yet: the account and
     * its books only store once everything is in, so backing out of the flow
     * leaves everything untouched. The last five parameters carry the refresh
     * material and the scopes granted, so expired access tokens can be
     * refreshed on later syncs and a later grant can ask for the union.
     */
    public void connect(
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
        // every copy but the one it just issued.
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

        List<PimDomain> overJmap = new ArrayList<>();
        Account jmap = null;
        for (java.util.Map.Entry<PimDomain, String> granted : oauthGroup.entrySet()) {
            DomainSetup setup = setups.get(granted.getKey());
            if (setup == null) {
                continue;
            }
            setup.baseUrl = granted.getValue();
            setup.credential = credential;
            Account server = new Account(setup.baseUrl, candidate.login, candidate.password);
            if (PimalayaClient.isJmap(server)) {
                overJmap.add(setup.domain);
                jmap = server;
            }
        }
        oauthGroup.clear();
        if (pendingStep != null) {
            pendingStep.signed = true;
            pendingStep = null;
        }

        if (signInPage && host.authStep() == STEP_OAUTH) {
            host.showAuth(STEP_SIGNIN);
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
                                        host.client.jmapCapabilities(transport, session));
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
                    // NOTE: not postAlive: the step it leads to may persist the
                    // account (finishOnboarding), which a destroyed activity must not drop.
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
     *
     * <p>Over JMAP, the session is read first: a domain it serves no account
     * for is {@link NotServed}, on its own, whatever the other domains of the
     * same sign-in answer.
     */
    private Exception probe(PimDomain domain, Account account) {
        try {
            if (PimalayaClient.isJmap(account)) {
                Set<String> capabilities;
                try (Transport transport = new Transport()) {
                    capabilities = host.client.jmapCapabilities(transport, account);
                }
                if (!PimDomain.servedByJmap(capabilities).contains(domain)) {
                    return new NotServed(notServed(List.of(domain)));
                }
            }
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

    /** A domain the JMAP session serves no account for, said in the user's words. */
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
     * The result step: the one password reached some domains and not
     * others. A row per domain it was tried on, connected or not, and the
     * two ways on.
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
                    host.showAuth(STEP_DOMAIN);
                    LinearLayout domains = host.findViewById(R.id.domain_container);
                    domains.removeAllViews();
                    domains.addView(domainCard(host.accountFor(pendingEmail)));
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

        host.showAuth(STEP_RESULT);
    }

    /**
     * The result once every domain signed in, when another app already fills
     * the phone's Contacts or Calendar app with the address: a row per domain
     * connected, the contacts or the calendars saying they stay off the
     * phone, and Continue, which goes on unmirrored. The standard setup's,
     * and the advanced one's for the calendars, which have no page of their
     * own to say it on.
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

        host.showAuth(STEP_RESULT);
    }

    /**
     * Onward once the setup's sign-ins are connected and what the phone
     * already shows is said: the books page in the advanced setup, every
     * book in the standard one, and straight to the first sync without
     * contacts.
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
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_group);
        card.setClipToOutline(true);
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
            card.addView(rowDivider());
        }
        card.addView(domainRow(domain, status, failed, mark));
    }

    /**
     * Turns everything the sequence signed in to into one account, and adds
     * the contacts domain's address book selection when it is among them.
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
                                fetched = host.client.listAddressbooks(transport, contacts);
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
                        // NOTE: not postAlive: the step it leads to may persist the
                        // account (finishOnboarding), which a destroyed activity must not drop.
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
     * The addressbooks step: one card with a row per book, its name and a
     * checkbox, every one ticked to begin with. The phone's switch, on the
     * page before, applies to every book ticked; the account's settings
     * change it per book later. A note says when another app already fills
     * the phone's Contacts app with the address, the books staying off it.
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
            LinearLayout card = new LinearLayout(host);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.card_group);
            card.setClipToOutline(true);
            container.addView(card);

            for (Addressbook book : books) {
                if (card.getChildCount() > 0) {
                    View divider = rowDivider();
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
                name.setText(book.name);
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

                bookChoices.add(new BookChoice(book.url, subscribe));
            }
        }

        host.showAuth(STEP_BOOKS);
        resetConfigContinue();
    }

    /**
     * Finish is enabled while at least one addressbook is on, or when the
     * account has none to pick.
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

    /**
     * The standard setup's commit, selection-free: every addressbook
     * subscribed, then the first sync straight to the contacts list.
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
     * addressbooks, subscribes the given ones, in the phone's Contacts app
     * when the switch says so and no other app fills it with the address
     * already, creates their Android accounts, then runs the account's
     * first sync, which projects them.
     */
    private void commitBooks(java.util.Set<String> subscribed) {
        boolean phone = switches.mirrors.contains(PhoneMirror.CONTACTS) && !elsewhere;
        AccountCommit.books(
                host.base,
                new PimdirCollections(host.pimdir, host),
                connectedEmail,
                pendingBooks,
                subscribed,
                phone);
        host.reconcilePhone();

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

        // NOTE: the calendar switch is kept for the account, its calendars
        // being listed by the first sync, which shows them on the phone.
        if (connected.server(PimDomain.CALENDAR) != null) {
            PhoneCalendars.setAccount(
                    host,
                    connected.email,
                    switches.mirrors.contains(PhoneMirror.CALENDAR) && !calendarsElsewhere);
        }

        AccountEntry stored = AccountCommit.persist(host.store, connected);
        host.accounts.removeIf(entry -> entry.email.equals(stored.email));
        host.accounts.add(stored);

        // NOTE: written only when on, so setting an address up again leaves
        // its choice alone; after the save, as it may schedule the job.
        if (switches.notifies && connected.server(PimDomain.MAIL) != null) {
            BackgroundCheck.setNotifies(host, stored.email, true);
        }

        busy();
        host.syncConnected(stored);
    }

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
            String url = Endpoints.submissionUrl(config);
            if (url == null) {
                continue;
            }
            boolean tls = url.startsWith("smtps:");
            String authority = config.host + ":" + config.port;

            List<AuthMethod> methods = new ArrayList<>(config.auth);
            java.util.Collections.sort(
                    methods,
                    (left, right) -> Integer.compare(SetupPlan.authRank(left.type), SetupPlan.authRank(right.type)));
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
