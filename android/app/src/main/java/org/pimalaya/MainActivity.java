package org.pimalaya;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.job.JobScheduler;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.content.res.ColorStateList;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.pimalaya.client.Cards;
import org.pimalaya.client.Account;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.Card;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.Transport;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.client.SubmissionRefused;

/**
 * Single-activity host walking the app's screens through a ViewFlipper.
 *
 * <p>First launch is the connection flow: email, provider detection and
 * discovery, proposed configurations, the credentials and a connection
 * check, then the addressbook selection. Every subsequent launch lands
 * on the app's root, the merged mail list, with the switcher in the bar
 * flipping to the two other domains over the same merged view.
 *
 * <p>The contacts list behind that switcher is contact-first per
 * docs/merged-view.md (replicas sharing a vCard UID collapse into one
 * row; storage and sync stay strictly per-replica). A merged row opens
 * one edit form for the whole contact and saving fans the form out onto
 * every replica. Accounts live behind the drawer the bar's logo opens;
 * syncs are manual, from there or from the pull-down.
 */
public class MainActivity extends Activity {
    // NOTE: everything from contacts to event is the content flipper's
    // child indexes, in the order activity_main.xml includes them; auth
    // and account are whole-frame overlays sliding over it, bar included
    // (only the FAB stays above them), so their ids only have to miss
    // the flipper's range.
    static final int PANEL_CONTACTS = 0;
    static final int PANEL_CONTACT = 1;
    static final int PANEL_ADVANCED = 2;
    static final int PANEL_SOURCE = 3;
    static final int PANEL_MAIL = 4;
    static final int PANEL_CALENDAR = 5;
    static final int PANEL_COMPOSE = 6;
    static final int PANEL_MESSAGE = 7;
    static final int PANEL_EVENT_VIEW = 8;
    private static final int PANEL_AUTH = 20;
    static final int PANEL_ACCOUNT = 21;
    static final int PANEL_FILTER = 22;
    static final int PANEL_DELETED = 23;

    /**
     * The domain the app opens on, and the one every flow that finishes
     * without a domain of its own lands back on. Mail, because it is the
     * one people check rather than consult: an agenda and an address
     * book are looked up when something is wanted from them, while an
     * inbox is the reason the app was opened at all.
     */
    private static final int PANEL_ROOT = PANEL_MAIL;

    /** The auth flow's steps, inside its own flipper under one bar. */
    static final int STEP_EMAIL = 0;

    /** What to connect this address for, once discovery has said what it can. */
    static final int STEP_DOMAIN = 1;

    static final int STEP_CONFIG = 2;
    static final int STEP_BOOKS = 3;

    /** The standard setup's outcome when some servers refused the password. */
    static final int STEP_RESULT = 4;

    /** Whether an auth step's main button is loading, the step frozen. */
    private boolean authBusy;

    static final int REQUEST_CONTACTS = 1;
    private static final int REQUEST_IMPORT = 2;
    private static final int REQUEST_EXPORT = 3;

    /** The shared backend client, the single-thread io executor, the
     *  main-thread handler and the theme helper. */
    final PimalayaClient client = new PimalayaClient();
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());
    final Ui ui = new Ui(this);

    SecureStore store;
    CardStore base;

    /** The pimdir store every domain and every account shares. */
    PimdirDb pimdir;

    /** The contacts inside it. */
    PimdirContacts contacts;
    SyncRunner runner;
    private ViewFlipper flipper;
    private ViewFlipper authFlipper;
    ContactForm form;

    /** Every connected account (multi-account). */
    final List<AccountEntry> accounts = new ArrayList<>();

    /** Each domain's filter, read once from where it was last left. */
    private final Map<PimDomain, MergedFilter> filters = new java.util.EnumMap<>(PimDomain.class);

    /** The filter page over a list. */
    private final FilterPage filterPage = new FilterPage(this);

    /** The deleted items page, raised from the drawer. */
    private final DeletedItemsPage deletedPage = new DeletedItemsPage(this);

    /** The calendar side of the store, and the agenda over it. */
    EventStore events;

    CalendarList calendarList;

    /** The reader one agenda row opens onto. */
    EventView eventView;

    /** The mail side of the store, and the merged list over it. */
    MailStore mail;

    MailList mailList;

    /** The reader one message row opens onto. */
    MessageView messageView;

    /** The composer the mail add button opens. */
    MessageCompose compose;

    /** The connection wizard and its OAuth grants (see onCreate wiring). */
    private OnboardingFlow onboarding;

    private OauthFlow oauth;

    /** The advanced raw-vCard sub-editor over the working document. */
    private final AdvancedEditor advancedEditor = new AdvancedEditor(this);

    /** The contact save fan-out over the working edit session. */
    private final ContactWriter writer = new ContactWriter(this);

    /** The vCard file import and export over the contacts list. */
    private final VcfTransfer vcfTransfer = new VcfTransfer(this);

    /** The full-screen account settings controller over the drawer. */
    private final AccountSettings accountSettings = new AccountSettings(this);

    /** The accounts drawer, opened by the contacts burger. */
    private androidx.drawerlayout.widget.DrawerLayout drawer;

    /**
     * The shared sync state: true while a sync runs. Every UI element
     * that reflects it (the modal loader, the drawer's sync row)
     * subscribes through {@link #observeSync}, and {@link #setSyncing}
     * pushes the flag to all of them at once.
     */
    private volatile boolean syncing;

    /** Whether the activity is in the foreground: the background fill runs only then. */
    private volatile boolean foreground;

    /** Whether a step of the background fill is queued or running. */
    private volatile boolean filling;

    /** The fill's and the scroll's session pools, by account; touched on the io thread only. */
    private final Map<String, MailPool<MailSession>> fillPools = new HashMap<>();

    /** Whether the fill is done or failed for the loop under way; on the io thread. */
    private boolean fillStopped;

    /** The body step's plan for the loop under way, null outside one; on the io thread. */
    private MailBodies.Run bodies;

    /** Whether the body step plans again at its next step: a pass landed, a setting changed. */
    private volatile boolean replanBodies;

    /** Bodies left to download, by account, while the body step runs: the drawer pill's. */
    private final Map<String, Integer> bodiesLeft = new java.util.concurrent.ConcurrentHashMap<>();

    private final List<java.util.function.Consumer<Boolean>> syncObservers = new ArrayList<>();

    /** The modal sync dialog, up while the syncing flag is (showSyncDialog). */
    private AlertDialog syncDialog;

    /** The pool's loader and bridge grouping (built in onCreate). */
    ContactPool pool;

    /** The contacts screen: list, adapter, search and selection. */
    private ContactsList contactsList;

    /** Sync to re-run once the contacts permission is granted, if any. */
    private Runnable afterContactsPermission;

    /** The editor's working state, replaced blank when it leaves. */
    EditSession edit = new EditSession();

    /** The screen currently shown: a flipper panel, or an overlay. */
    int screen = PANEL_ROOT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        drawer = findViewById(R.id.drawer);
        applyEdgeToEdge();

        store = new SecureStore(this);
        pimdir = new PimdirDb(this);
        base = new CardStore(this, pimdir);
        contacts = new PimdirContacts(pimdir);
        pool = new ContactPool(base, contacts, accounts);
        contactsList = new ContactsList(this);
        events = new EventStore(this, pimdir);
        calendarList = new CalendarList(this, events);
        mail = new MailStore(this, pimdir);
        mailList = new MailList(this, mail);
        eventView = new EventView(this);
        messageView = new MessageView(this);
        compose = new MessageCompose(this);
        runner = new SyncRunner(this, base, pimdir, store, client, syncObserver());
        // NOTE: the two flows reference each other (grants land back in
        // the wizard), so one side binds late.
        oauth = new OauthFlow(this);
        onboarding = new OnboardingFlow(this, oauth);
        oauth.onConnected = onboarding::connect;
        oauth.onAborted = onboarding::abortAuthSteps;
        flipper = findViewById(R.id.flipper);
        authFlipper = findViewById(R.id.auth_flipper);
        form = new ContactForm(this);
        form.setOnRender(this::updateSaveEnabled);

        setUpScreens();
        setUpEmailPanel();
        contactsList.setUp();
        calendarList.setUp();
        mailList.setUp();
        setUpContactPanel();
        setUpHomePanel();
        filterPage.setUp();
        deletedPage.setUp();

        // The selection's close and select-all buttons serve whichever
        // list is selecting.
        findViewById(R.id.selection_close)
                .setOnClickListener(
                        view -> {
                            if (screen == PANEL_MAIL) {
                                mailList.exitSelection();
                            } else {
                                contactsList.exitSelection();
                            }
                        });
        findViewById(R.id.selection_all)
                .setOnClickListener(
                        view -> {
                            if (screen == PANEL_MAIL) {
                                mailList.toggleSelectAll();
                            } else {
                                contactsList.toggleSelectAll();
                            }
                        });

        setUpFab(R.id.fab);
        findViewById(R.id.fab).setOnClickListener(view -> onFabClick());
        findViewById(R.id.fab_extended).setOnClickListener(view -> onFabClick());
        ((android.widget.ImageView) findViewById(R.id.fab_extended_icon))
                .setImageTintList(ColorStateList.valueOf(accentContrast()));
        ((TextView) findViewById(R.id.fab_extended_label)).setTextColor(accentContrast());
        ((TextView) findViewById(R.id.bar_send)).setTextColor(accentContrast());
        ((TextView) findViewById(R.id.bar_send))
                .setCompoundDrawableTintList(ColorStateList.valueOf(accentContrast()));
        findViewById(R.id.bar_send).setOnClickListener(view -> compose.send());
        findViewById(R.id.bar_back).setOnClickListener(view -> onBarBack());
        findViewById(R.id.bar_menu).setOnClickListener(view -> openAccountsDrawer());
        for (int panel : Domains.PANELS) {
            findViewById(Domains.buttonOf(panel)).setOnClickListener(view -> switchDomain(panel));
        }
        ((TextView) findViewById(R.id.nav_mail_badge)).setTextColor(accentContrast());
        findViewById(R.id.bar_filter).setOnClickListener(view -> openFilter());

        // The modal dialog binds once here and covers every sync entry
        // point through the shared syncing flag.
        observeSync(this::showSyncDialog);

        // NOTE: the built-in local book is always present and
        // subscribed, so the app opens usable with no account:
        // onboarding is never forced.
        base.ensureLocalAddressbook(LocalBook.URL, LocalBook.ACCOUNT, LocalBook.ID);
        new PimdirCollections(pimdir, this)
                .ensure(
                        LocalBook.URL,
                        LocalBook.ACCOUNT,
                        PimdirSummary.CONTACT,
                        getString(R.string.local_book));
        accounts.add(LocalBook.account());
        accounts.addAll(store.loadAll());

        // NOTE: sync is manual only; an install upgraded from a build
        // that scheduled background sync still holds its periodic jobs
        // and their interval choices, dropped here.
        getSystemService(JobScheduler.class).cancelAll();
        deleteSharedPreferences("pimalaya.background");

        goHome();

        // NOTE: a summary is written, never derived, so a store filled by a
        // writer that spelled it differently keeps that spelling until
        // something rewrites it, and no sync will: the bodies have not
        // changed. Costs one query over the summaries when there is nothing
        // to do.
        io.execute(
                () -> {
                    if (contacts.repairSummaries() > 0) {
                        postAlive(this::reloadContacts);
                    }
                });

        // NOTE: adb-only hooks, so syncs can be driven headlessly:
        // am start ... --ez syncRemote true / --ez syncLocal true
        if (getIntent().getBooleanExtra("syncRemote", false)) {
            syncRemote();
        } else if (getIntent().getBooleanExtra("syncLocal", false)) {
            syncLocal();
        }

        // NOTE: an OAuth redirect lands here rather than in onNewIntent
        // when the process died while the user was in the browser (the
        // redirect recreates the activity from scratch).
        oauth.handleRedirect(getIntent());

        // A fresh start with no real account opens the connection flow
        // right away, skipped while an OAuth redirect is processing.
        if (savedInstanceState == null && getIntent().getData() == null && !hasRealAccount()) {
            startAuth();
        }
    }

    /**
     * Enters the connection flow: the auth sheet fades in over the
     * current screen while the drawer it came from slides shut.
     * Cancelling lands on the contacts root, finishing too.
     */
    private void startAuth() {
        openAuthFlow();
        if (drawer.isDrawerOpen(android.view.Gravity.START)) {
            drawer.closeDrawer(android.view.Gravity.START);
        }
    }

    /** Resets the connection flow to its first step and shows it. */
    private void openAuthFlow() {
        onboarding.open();
    }

    /** Navigates the auth flow forward: the step slides in from the right. */
    void showAuth(int step) {
        showAuth(step, false);
    }

    /** Navigates the auth flow back: the step slides in from the left. */
    private void showAuthBack(int step) {
        showAuth(step, true);
    }

    /**
     * Shows an auth step under the persistent bar: only the inner step
     * view transitions. Entering the flow from outside jumps the inner
     * flipper without animation (the outer panel fade is the
     * transition).
     */
    private void showAuth(int step, boolean back) {
        boolean inside = screen == PANEL_AUTH;
        if (inside) {
            hideKeyboard();
            authFlipper.setInAnimation(this, back ? R.anim.slide_in_left : R.anim.slide_in_right);
            authFlipper.setOutAnimation(
                    this, back ? R.anim.slide_out_right : R.anim.slide_out_left);
        } else {
            authFlipper.setInAnimation(null);
            authFlipper.setOutAnimation(null);
        }

        authFlipper.setDisplayedChild(step);
        if (inside) {
            applyChrome(PANEL_AUTH);
        } else {
            openOverlay(PANEL_AUTH);
        }
    }

    /**
     * The auth bar per step: the step's title (the first step greets
     * with Welcome on a fresh install and reads Add account once an
     * account already exists), no back arrow on the welcome step when
     * no account is stored (nothing to go back to), and the cross only
     * when one is (the flow is escape-free otherwise).
     */
    private void applyAuthChrome() {
        int step = authFlipper.getDisplayedChild();
        // NOTE: a first run opens on the address alone, with nothing to
        // leave for and the page's own headline in place of a title; the
        // domain and result steps carry a round back button of their own.
        boolean bare =
                (step == STEP_EMAIL && !hasRealAccount())
                        || step == STEP_DOMAIN
                        || step == STEP_RESULT;
        findViewById(R.id.auth_bar).setVisibility(bare ? View.GONE : View.VISIBLE);
        TextView title = findViewById(R.id.auth_title);
        if (step == STEP_EMAIL) {
            title.setText(hasRealAccount() ? R.string.add_account : R.string.auth_step_email);
        } else if (step == STEP_CONFIG) {
            title.setText(R.string.auth_step_config);
        } else if (step == STEP_BOOKS) {
            title.setText(R.string.auth_step_books);
        } else {
            // NOTE: the domain and result steps carry their own headline.
            title.setText("");
        }
    }

    /** The auth flow's displayed step. */
    int authStep() {
        return authFlipper.getDisplayedChild();
    }

    /** The auth bar's back arrow, per step. */
    private void authBack() {
        if (authBusy) {
            return;
        }
        int step = authFlipper.getDisplayedChild();
        if (step == STEP_RESULT) {
            onboarding.abortAuthSteps();
        } else if (step == STEP_BOOKS) {
            // NOTE: nothing persists before the selection confirms, so
            // the books step steps back like any other.
            showAuthBack(STEP_CONFIG);
        } else if (step == STEP_CONFIG) {
            showAuthBack(STEP_DOMAIN);
        } else if (step == STEP_DOMAIN) {
            showAuthBack(STEP_EMAIL);
        } else {
            cancelAuth();
        }
    }

    /**
     * Leaves the auth flow without finishing: the sheet slides out onto
     * the root (the drawer stays closed).
     */
    private void cancelAuth() {
        closeOverlay(PANEL_AUTH);
        screen = PANEL_ROOT;
        applyChrome(PANEL_ROOT);
    }

    /**
     * Closes the auth sheet onto one of the domain screens, for a connection
     * that finishes somewhere other than the root.
     */
    void leaveOnboarding(int panel) {
        closeOverlay(PANEL_AUTH);
        screen = panel;
        applyChrome(panel);
        if (drawer.isDrawerOpen(android.view.Gravity.START)) {
            drawer.closeDrawer(android.view.Gravity.START);
        }
        reloadHome();
    }

    /** The account entry matching an email, or null. */
    AccountEntry accountFor(String email) {
        for (AccountEntry entry : accounts) {
            if (entry.email.equals(email)) {
                return entry;
            }
        }
        return null;
    }

    /** Whether any real (non-local) account is configured. */
    private boolean hasRealAccount() {
        for (AccountEntry entry : accounts) {
            if (!LocalBook.is(entry.email)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gates the editor's validate FAB: always live in a plain edit, live
     * in a merge only once every diverging field has been reviewed.
     */
    private void updateSaveEnabled() {
        if (screen != PANEL_CONTACT) {
            return;
        }

        android.widget.ImageButton fab = findViewById(R.id.fab);
        if (form.conflictResolved()) {
            fab.setBackgroundTintList(null);
            fab.setImageResource(R.drawable.ic_check);
            fab.setImageTintList(android.content.res.ColorStateList.valueOf(accentContrast()));
            fab.setContentDescription(getString(R.string.contact_save));
            setFabEnabled(R.id.fab, true);
            return;
        }

        // Diverging rows await review: the disc turns the error tone at
        // full strength, so the state reads as a signal not a dimmed save.
        fab.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        resolveColor(android.R.attr.colorError)));
        fab.setImageResource(R.drawable.ic_diverged);
        fab.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        resolveColor(android.R.attr.textColorPrimary)));
        fab.setContentDescription(getString(R.string.contact_diverged_pending));
        fab.setEnabled(false);
        fab.setAlpha(1f);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        // NOTE: shutdownNow interrupts, but a blocking accept() only
        // wakes on close; without this the OAuth loopback listener would
        // outlive the activity by up to its 300s timeout.
        oauth.closeLoopback();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        oauth.handleRedirect(intent);
    }

    @Override
    public void onBackPressed() {
        // The sync loader is modal: back waits with the rest.
        if (syncing) {
            return;
        }
        // The account settings overlay sits above the open drawer, so
        // back peels it off first.
        if (screen == PANEL_ACCOUNT) {
            accountSettings.leave();
            return;
        }
        if (screen == PANEL_FILTER) {
            filterPage.leave();
            return;
        }
        if (screen == PANEL_DELETED) {
            deletedPage.leave();
            return;
        }
        if (drawer.isDrawerOpen(android.view.Gravity.START)) {
            drawer.closeDrawer(android.view.Gravity.START);
            return;
        }
        if (screen == PANEL_CONTACTS && contactsList.isSelectionMode()) {
            contactsList.exitSelection();
            return;
        }
        if (screen == PANEL_MAIL && mailList.isSelectionMode()) {
            mailList.exitSelection();
            return;
        }

        Screen entry = screens.get(screen);
        if (entry != null && entry.systemBack != null) {
            entry.systemBack.run();
        } else {
            super.onBackPressed();
        }
    }

    /** The app's root: the merged mail list across every account. */
    private void goHome() {
        goDomain(PANEL_ROOT);
    }

    /**
     * Lands on one domain's list with everything behind it settled: any
     * contacts search or selection dropped, the contacts rebuilt (they
     * back the merged view whichever list shows), the drawer shut.
     */
    private void goDomain(int panel) {
        contactsList.closeSearch();
        contactsList.exitSelection();

        reloadContacts();

        // Landing on a list is always a return: the auth sheet slides
        // back out onto the list left underneath.
        if (screen == PANEL_AUTH) {
            closeOverlay(PANEL_AUTH);
            screen = panel;
            applyChrome(panel);
        } else {
            showBack(panel);
        }

        if (drawer.isDrawerOpen(android.view.Gravity.START)) {
            drawer.closeDrawer(android.view.Gravity.START);
        }
        reloadHome();
    }

    /**
     * Refreshes the drawer and slides it over the list (which stays
     * put). Reached from the bar's burger.
     */
    void openAccountsDrawer() {
        reloadHome();
        drawer.openDrawer(android.view.Gravity.START);
    }

    private void setUpEmailPanel() {
        EditText email = findViewById(R.id.email_input);
        findViewById(R.id.auth_back).setOnClickListener(view -> authBack());
        findViewById(R.id.domain_back).setOnClickListener(view -> authBack());
        findViewById(R.id.result_back).setOnClickListener(view -> authBack());
        findViewById(R.id.auth_cancel).setOnClickListener(view -> cancelAuth());

        onboarding.bind();

        // Continue stays disabled until the field holds something
        // plausible (an email, a server, or a connection URI).
        email.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        if (screen == PANEL_AUTH
                                && authFlipper.getDisplayedChild() == STEP_EMAIL) {
                            setFabEnabled(R.id.email_continue, onboarding.emailSubmittable());
                        }
                    }
                });
    }

    /**
     * Toggles an auth continue FAB between its arrow and an on-disc
     * loader, the button disabled while loading.
     */
    void setAuthLoading(int buttonId, int progressId, boolean loading) {
        View button = findViewById(buttonId);
        if (button instanceof android.widget.ImageButton) {
            setFabEnabled(buttonId, !loading);
            ((android.widget.ImageButton) button).setImageAlpha(loading ? 0 : 255);
        } else {
            // NOTE: a pill keeps its full tone and its label, the loader
            // turning at its end in the label's colour.
            button.setEnabled(!loading);
            button.setAlpha(1f);
            // NOTE: and nothing else on the step answers until it is done,
            // the way back included.
            authBusy = loading;
            int[] controls =
                    buttonId == R.id.email_continue
                            ? new int[] {R.id.email_input, R.id.email_advanced}
                            : new int[] {
                                R.id.domain_back,
                                R.id.domain_advanced,
                                R.id.domain_password,
                                R.id.domain_password_toggle,
                                R.id.domain_container
                            };
            for (int control : controls) {
                enableTree(findViewById(control), !loading);
            }
            ((android.widget.ProgressBar) findViewById(progressId))
                    .setIndeterminateTintList(
                            android.content.res.ColorStateList.valueOf(accentContrast()));
        }
        findViewById(progressId).setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    /** Enables or disables a view and everything inside it. */
    private static void enableTree(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                enableTree(group.getChildAt(index), enabled);
            }
        }
    }

    /** Enables or disables an auth continue FAB, dimming its disc. */
    void setFabEnabled(int id, boolean enabled) {
        View fab = findViewById(id);
        fab.setEnabled(enabled);
        fab.setAlpha(enabled ? 1f : 0.4f);
    }

    /**
     * Styles a floating action button: its icon tinted to contrast the
     * accent disc's luminance.
     */
    private void setUpFab(int id) {
        android.widget.ImageButton fab = findViewById(id);
        fab.setImageTintList(android.content.res.ColorStateList.valueOf(accentContrast()));
    }

    /** Black or white, whichever reads on the accent colour. */
    int accentContrast() {
        int accent = resolveColor(android.R.attr.colorAccent);
        return android.graphics.Color.luminance(accent) > 0.5f
                ? android.graphics.Color.BLACK
                : android.graphics.Color.WHITE;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the browser without a redirect (a cancelled
        // grant) must not leave the config Continue stuck on its loader;
        // a real redirect re-enters loading right after.
        if (flipper != null
                && screen == PANEL_AUTH
                && authFlipper.getDisplayedChild() == STEP_DOMAIN) {
            onboarding.resetConfigContinue();
        }

        // NOTE: the background fill stops when the app leaves the
        // foreground and picks up where its floors reached on return.
        foreground = true;
        if (mailList != null) {
            mailList.retryOlder();
        }
        fillMail();
    }

    @Override
    protected void onPause() {
        foreground = false;
        super.onPause();
    }

    private void setUpHomePanel() {
        // Sync slides the drawer shut on its way in, so the modal dialog
        // and its outcome toasts land over the refreshed contacts list.
        findViewById(R.id.drawer_close)
                .setOnClickListener(view -> drawer.closeDrawer(Gravity.START));
        findViewById(R.id.drawer_sync)
                .setOnClickListener(
                        view -> {
                            drawer.closeDrawer(Gravity.START);
                            syncAll();
                        });
        findViewById(R.id.drawer_add).setOnClickListener(view -> startAuth());
        findViewById(R.id.drawer_deleted).setOnClickListener(view -> deletedPage.open());
        findViewById(R.id.drawer_about).setOnClickListener(view -> showAbout());

        findViewById(R.id.account_back).setOnClickListener(view -> accountSettings.leave());
        findViewById(R.id.account_delete)
                .setOnClickListener(view -> accountSettings.confirmDeleteCurrent());

        // NOTE: the settings overlay carries its own save FAB, the
        // shared one drawing under the drawer the overlay covers.
        android.widget.ImageButton accountFab = findViewById(R.id.account_fab);
        accountFab.setImageTintList(
                android.content.res.ColorStateList.valueOf(accentContrast()));
        accountFab.setOnClickListener(view -> accountSettings.save());

        // While a sync runs the row goes inert; the modal dialog carries
        // the only spinner.
        observeSync(
                active -> {
                    View row = findViewById(R.id.drawer_sync);
                    row.setEnabled(!active);
                    row.setAlpha(active ? 0.5f : 1f);
                });
    }

    /** The app's version name, or empty when the package is unreadable. */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            return "";
        }
    }

    /** The About dialog: version and pitch, a Website link and a bug report. */
    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(getString(R.string.about_body, appVersion()))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(
                        R.string.about_website, (dialog, which) -> openUrl("https://pimalaya.org"))
                .setNegativeButton(
                        R.string.report_bug,
                        (dialog, which) ->
                                openUrl("https://github.com/pimalaya/android/issues"))
                .show();
    }

    /** Opens a URL in the browser. */
    private void openUrl(String url) {
        startActivity(
                new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
    }

    /**
     * Rebuilds the drawer: one card per connected account (the local book
     * stays hidden), saying what it covers, and whether it takes part and
     * when it last synced. Tapping a card opens its
     * settings screen: activation, cadence, the per-addressbook advanced
     * switches and deletion, so the drawer itself stays a plain list.
     *
     * <p>No mailboxes: the lists are one merged view over every
     * collection, which the filter narrows, so a drawer entry opening
     * one collection would be a second way of looking at the same thing.
     */
    void reloadHome() {
        // With no real account the drawer shows an empty state, and its
        // sync row is hidden.
        boolean hasAccount = hasRealAccount();
        findViewById(R.id.drawer_sync).setVisibility(hasAccount ? View.VISIBLE : View.GONE);
        findViewById(R.id.drawer_empty).setVisibility(hasAccount ? View.GONE : View.VISIBLE);

        LinearLayout container = findViewById(R.id.home_container);
        container.removeAllViews();
        for (AccountEntry account : store.loadAll()) {
            if (!LocalBook.is(account.email)) {
                container.addView(accountCard(account));
            }
        }
    }

    /**
     * One drawer account card: the disc, the address, what it covers,
     * and the pill saying whether it takes part and when it last synced.
     */
    private View accountCard(AccountEntry account) {
        String email = account.email;

        TextView avatar = new TextView(this);
        avatar.setText(Avatar.letter(email));
        avatar.setBackground(Avatar.circle(this, email));
        avatar.setGravity(Gravity.CENTER);
        avatar.setTextColor(android.graphics.Color.WHITE);
        avatar.setTextSize(18);

        TextView address = new TextView(this);
        address.setText(email);
        address.setTextColor(resolveColor(android.R.attr.textColorPrimary));
        address.setTextSize(16);
        address.setTypeface(null, android.graphics.Typeface.BOLD);
        address.setSingleLine(true);
        address.setEllipsize(android.text.TextUtils.TruncateAt.END);

        List<String> covered = new ArrayList<>();
        for (PimDomain domain : PimDomain.values()) {
            if (account.covers(domain)) {
                covered.add(getString(domain.label));
            }
        }
        TextView domains = new TextView(this);
        domains.setText(android.text.TextUtils.join(" · ", covered));
        domains.setTextColor(resolveColor(android.R.attr.textColorSecondary));
        domains.setTextSize(14);
        domains.setSingleLine(true);
        domains.setEllipsize(android.text.TextUtils.TruncateAt.END);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(address);
        text.addView(domains);

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.HORIZONTAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.addView(avatar, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout.LayoutParams textParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginStart(dp(12));
        identity.addView(text, textParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.drawer_card);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.addView(identity);
        LinearLayout.LayoutParams pillParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
        pillParams.topMargin = dp(12);
        card.addView(
                syncPill(
                        AccountActivation.enabled(this, email),
                        SyncStamps.at(this, email),
                        bodiesLeft.get(email)),
                pillParams);
        card.setOnClickListener(view -> openAccountSettings(email));

        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(8);
        card.setLayoutParams(cardParams);
        return card;
    }

    /**
     * The pill saying where an account stands: deactivated in the plain
     * tone, else how many bodies its offline setting still downloads
     * ({@code left}, null outside the body step) or when it last synced on an
     * accent tint, or that it never has in the plain tone.
     */
    private View syncPill(boolean enabled, long stamp, Integer left) {
        boolean downloading = enabled && left != null && left > 0;
        boolean synced = downloading || (enabled && stamp > 0);
        int color =
                synced
                        ? resolveColor(android.R.attr.colorAccent)
                        : resolveColor(android.R.attr.textColorSecondary);

        android.widget.ImageView glyph = new android.widget.ImageView(this);
        glyph.setImageResource(R.drawable.ic_sync);
        glyph.setImageTintList(ColorStateList.valueOf(color));

        TextView label = new TextView(this);
        if (!enabled) {
            label.setText(R.string.drawer_deactivated);
        } else if (downloading) {
            label.setText(
                    getResources().getQuantityString(R.plurals.drawer_downloading, left, left));
        } else if (synced) {
            label.setText(
                    getString(
                            R.string.drawer_synced,
                            android.text.format.DateUtils.getRelativeTimeSpanString(
                                    stamp,
                                    System.currentTimeMillis(),
                                    android.text.format.DateUtils.MINUTE_IN_MILLIS)));
        } else {
            label.setText(R.string.drawer_never_synced);
        }
        label.setTextColor(color);
        label.setTextSize(13);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setSingleLine(true);

        android.graphics.drawable.GradientDrawable tint =
                new android.graphics.drawable.GradientDrawable();
        tint.setCornerRadius(dp(15));
        tint.setColor((color & 0x00ffffff) | 0x24000000);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(8), 0, dp(12), 0);
        pill.setBackground(tint);
        pill.addView(glyph, new LinearLayout.LayoutParams(dp(16), dp(16)));
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMarginStart(dp(8));
        pill.addView(label, labelParams);
        return pill;
    }

    /**
     * Opens one account's settings screen (delegated to
     * {@link AccountSettings}): the staged switches load from the store,
     * the screen fades in over the drawer it came from, which stays open
     * underneath for the return. The simple pair (activate, cadence) fans
     * out onto every addressbook; Advanced unfolds the per-book sections.
     */
    private void openAccountSettings(String email) {
        accountSettings.open(email);
    }

    /** Runs the pending contacts-sync retry once the permission lands. */
    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request == REQUEST_CONTACTS && hasContactsPermission()) {
            Runnable retry = afterContactsPermission;
            afterContactsPermission = null;
            if (retry != null) {
                retry.run();
            }
        }
    }

    boolean hasContactsPermission() {
        return checkSelfPermission(Manifest.permission.WRITE_CONTACTS)
                        == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_CONTACTS)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /** True while the merged contacts list is the shown screen (the
     *  shared bar is the contacts list's only when this holds). */
    boolean onContactsScreen() {
        return screen == PANEL_CONTACTS;
    }

    /**
     * The runner's hooks into this activity's presentation: the loader
     * dialog's detail line, and the in-memory account cache a token
     * refresh must keep current (mutated on the main thread the drawer
     * reads it from).
     */
    private SyncRunner.Observer syncObserver() {
        return new SyncRunner.Observer() {
            @Override
            public void step(PimDomain domain, int stage, int count) {
                syncStep(domain, stage, count);
            }

            @Override
            public void account(String email) {
                syncTitle(R.string.contacts_title, email);
            }

            @Override
            public void accountRefreshed(AccountEntry updated) {
                main.post(
                        () -> {
                            accounts.removeIf(entry -> entry.email.equals(updated.email));
                            accounts.add(updated);
                        });
            }
        };
    }

    /** Pushes the sync flag to every subscribed element (the modal
     *  loader, the drawer's inert sync row). */
    private void setSyncing(boolean value) {
        syncing = value;
        for (java.util.function.Consumer<Boolean> observer : syncObservers) {
            observer.accept(value);
        }
    }

    /**
     * Shows or hides the modal sync dialog: non-cancelable (no outside
     * tap, no back), so the wait is explicit instead of an ambiguous
     * spinner, while the screen behind stays fully visible. The title
     * carries the domain, the detail the current engine step, and the
     * screen stays on for the duration.
     */
    private void showSyncDialog(boolean active) {
        if (!active) {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (syncDialog != null) {
                syncDialog.dismiss();
                syncDialog = null;
            }
            return;
        }

        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View content = getLayoutInflater().inflate(R.layout.dialog_sync, null);
        // NOTE: both lines from the first frame, never the title alone. A
        // pass has a round trip or two to make before it can name what it
        // is on, and a dialog that shows one line for that long is a
        // different dialog, laid out differently, that then jumps.
        ((TextView) content.findViewById(R.id.sync_dialog_title)).setText(syncHeading());
        ((TextView) content.findViewById(R.id.sync_dialog_detail))
                .setText(R.string.sync_overlay_preparing);
        syncDialog =
                new AlertDialog.Builder(this)
                        .setView(content)
                        .setCancelable(false)
                        .show();
    }

    /**
     * The domain the sync dialog names, and the account a pass is on when
     * it has reached one: the title is which of the three a pass is on and
     * for whom, the detail line what it is doing there.
     */
    private volatile int syncDomain = R.string.contacts_title;

    private volatile String syncAccount;

    /** Names the domain a pass moved on to (callable off the main thread). */
    private void syncTitle(int domain) {
        syncTitle(domain, null);
    }

    /**
     * Names the domain and the account a pass moved on to (callable off the
     * main thread), so a pass over several accounts says whose it is on.
     */
    private void syncTitle(int domain, String accountEmail) {
        syncDomain = domain;
        syncAccount = accountEmail;
        String heading = syncHeading();
        main.post(
                () -> {
                    if (syncDialog != null) {
                        ((TextView) syncDialog.findViewById(R.id.sync_dialog_title))
                                .setText(heading);
                    }
                });
    }

    /** The sync dialog's title: the account, when known, then the domain. */
    private String syncHeading() {
        String domain = getString(syncDomain);
        String account = syncAccount;
        return account == null ? domain : getString(R.string.sync_heading, account, domain);
    }

    /**
     * Sets the loader's step line from an engine stage of a domain (any
     * thread), in that domain's words: events while the agenda syncs,
     * messages while the mail does.
     */
    private void syncStep(PimDomain domain, int stage, int count) {
        String text = SyncSteps.text(getResources(), domain, stage, count);
        if (text != null) {
            syncDetail(text);
        }
    }

    /**
     * Sets the loader's step line to a count of a whole (any thread): how
     * many of an account's mailboxes have landed, where they land side by
     * side and no one engine's step says how far the pass is.
     */
    private void syncProgress(int string, int done, int total) {
        syncDetail(getString(string, done, total));
    }

    /** Sets the loader's step line (any thread). */
    private void syncDetail(String text) {
        main.post(
                () -> {
                    if (syncDialog != null) {
                        ((TextView) syncDialog.findViewById(R.id.sync_dialog_detail))
                                .setText(text);
                    }
                });
    }

    /**
     * Subscribes an element to the sync state and hands it the current
     * value straight away, so it starts consistent.
     */
    private void observeSync(java.util.function.Consumer<Boolean> observer) {
        syncObservers.add(observer);
        observer.accept(syncing);
    }

    /**
     * Store-to-remote spoke: per addressbook, fetches the remote into
     * the store, pushes the staged local changes, and re-fetches the
     * pushed state. The phone is not touched; that is the local sync.
     * The shared syncing state drives the modal loader over whatever is
     * on screen.
     */
    void syncRemote() {
        syncTitle(R.string.contacts_title);
        setSyncing(true);

        io.execute(
                () -> {
                    SyncRunner.Outcome outcome = runner.syncRemote();
                    postAlive(
                            () -> {
                                setSyncing(false);
                                reloadContacts();
                                reportSync(outcome);
                            });
                });
    }

    /**
     * Surfaces a sync outcome: an error dialog, or one report toast per
     * axis: a Local toast (cards in, out and changed against the phone's
     * Contacts app) first when any synced book mirrors there, then the
     * Remote toast, carrying the pending-conflicts line when contacts
     * wait for manual resolution.
     */
    private void reportSync(SyncRunner.Outcome outcome) {
        if (outcome.failure != null) {
            // NOTE: the last sync's store keeps failing addressbooks usable.
            showError(outcome.failure, R.string.sync_failed);
            return;
        }

        // NOTE: toasts queue, so the local report shows first and the
        // remote one takes its place.
        if (outcome.local) {
            toast(
                    getString(
                            R.string.sync_line_local,
                            outcome.localIn.size(),
                            outcome.localOut.size(),
                            outcome.localChanged.size()));
        }

        StringBuilder message =
                new StringBuilder(
                        getString(
                                R.string.sync_line_remote,
                                outcome.remoteIn.size(),
                                outcome.remoteOut.size(),
                                outcome.remoteChanged.size()));
        if (outcome.conflicts > 0) {
            message.append('\n')
                    .append(getString(R.string.sync_conflicts_pending, outcome.conflicts));
        }
        toast(message.toString());
    }

    /**
     * The mail list's pull: the mail of the accounts and mailboxes the
     * filter shows, into the merged list.
     *
     * <p>The endpoint is the one the account was connected with, which is what
     * the mail domain of the connection flow exists to establish. It used to be
     * re-discovered on every refresh from a contacts account's address, because
     * there was no way to connect a mail account at all; that guessed at both
     * the server and the credentials, and worked only where the two domains
     * happened to share them.
     */
    void syncMail() {
        syncTitle(R.string.mail_title);
        setSyncing(true);
        io.execute(
                () -> {
                    MailPass pass = mailPass(filterOf(PimDomain.MAIL));
                    postAlive(
                            () -> {
                                setSyncing(false);
                                mailList.reload();
                                reportMail(pass);
                                if (pass.failure != null) {
                                    showError(pass.failure, R.string.sync_failed);
                                } else {
                                    mailList.retryOlder();
                                }
                                fillMail();
                            });
                });
    }

    /** What one mail pass over every account came to. */
    private static final class MailPass {
        Exception failure;
        int sent;
    }

    /**
     * Every mail account's outbox drained and mailboxes synced, on the
     * calling thread: those the scope takes.
     */
    private MailPass mailPass(SyncScope scope) {
        syncTitle(R.string.mail_title);
        MailPass pass = new MailPass();
        for (AccountEntry account : accountsFor(PimDomain.MAIL)) {
            if (!scope.account(account.email)) {
                continue;
            }
            syncTitle(R.string.mail_title, account.email);
            // One connection for the account's whole pass: the drain, the
            // walk and every marker the reader moved go out on it rather
            // than on one apiece.
            try (MailSession session = openMail(account)) {
                // NOTE: the outbox first, so a message sent a moment ago is
                // already in the sent mailbox by the time the walk beside it
                // lists one.
                try {
                    if (scope.collection(account.email, mail.outboxOf(account.email))) {
                        pass.sent += drainOutbox(account, session);
                    }
                } catch (Exception error) {
                    Log.w("pimalaya", "outbox drain failed: " + account.email, error);
                    if (pass.failure == null) {
                        pass.failure = error;
                    }
                }

                Exception error = fetchMail(account, session, scope);
                if (pass.failure == null) {
                    pass.failure = error;
                }
            } catch (Exception error) {
                Log.w("pimalaya", "mail sync failed: " + account.email, error);
                if (pass.failure == null) {
                    pass.failure = error;
                }
            }
        }
        return pass;
    }

    /** Says how many queued messages a pass sent, when it sent any. */
    private void reportMail(MailPass pass) {
        if (pass.sent > 0) {
            toast(getResources().getQuantityString(R.plurals.outbox_sent, pass.sent, pass.sent));
        }
    }

    /**
     * Hands over everything waiting in one account's outbox, on the
     * calling thread, answering how many went out.
     *
     * <p>One at a time, in the append order the queue owes them, and the
     * row goes only once the submission has been accepted: the message
     * has left, so acknowledging it is what finishes the action (STORAGE
     * section 15.5). A submission is therefore at-least-once, and a drain
     * that dies between the handover and the acknowledgement sends the
     * message twice, which is the trade against losing it.
     *
     * <p>A failure has two answers. A message the server refused for good
     * is parked carrying what it said, and the drain carries on to the
     * ones behind it: nothing will ever make that message acceptable, and
     * leaving it queued would offer it again on every sync for ever.
     * Anything else counts an attempt and stops the drain where it is,
     * since the usual cause is that there is no network and the next
     * message would fail too.
     */
    private int drainOutbox(AccountEntry account, MailSession session) throws Exception {
        int sent = 0;
        for (MailStore.Outgoing waiting : mail.outgoing(account.email)) {
            String filed;
            try {
                filed = client.submitMessage(session, waiting.source);
            } catch (SubmissionRefused refused) {
                Log.w("pimalaya", "submission refused: " + account.email, refused);
                mail.parkOutgoing(waiting.id, refused.getMessage());
                continue;
            } catch (Exception error) {
                mail.retryOutgoing(waiting.id);
                throw error;
            }
            if (filed != null) {
                mail.aliasSentCopy(waiting.messageId, filed);
            }
            mail.acknowledge(waiting.id);
            sent += 1;
        }
        return sent;
    }

    /**
     * Opens one account's mail connection, renewing its access token once
     * if the server refuses the sign-in.
     *
     * <p>The refresh belongs here rather than around each verb, because
     * the session <em>is</em> the sign-in: a token that expired is a
     * connection that cannot be opened, and one already open outlives the
     * token that opened it.
     */
    MailSession openMail(AccountEntry account) {
        try {
            return PimalayaClient.openMail(account.server(PimDomain.MAIL));
        } catch (Exception refused) {
            AccountCredential credential = account.credential(PimDomain.MAIL);
            if (!SyncRunner.expiredToken(refused) || !credential.renewable()) {
                throw refused instanceof RuntimeException
                        ? (RuntimeException) refused
                        : new IllegalStateException(refused);
            }
            return PimalayaClient.openMail(
                    runner.refreshed(account, PimDomain.MAIL).server(PimDomain.MAIL));
        }
    }

    /** The stored accounts connected for one domain. */
    List<AccountEntry> accountsFor(PimDomain domain) {
        List<AccountEntry> matching = new ArrayList<>();
        for (AccountEntry entry : store.loadAll()) {
            if (entry.covers(domain)) {
                matching.add(entry);
            }
        }
        return matching;
    }

    /**
     * The agenda's pull: the calendars the filter shows, and their events.
     *
     * <p>Read-only and whole-collection: CalDAV's ctag and sync-token
     * rounds are what an incremental pass would use, and neither is
     * wired yet, so a refresh relists. One report carries a whole
     * calendar's objects, so this is one round trip per calendar.
     */
    void syncCalendars() {
        syncTitle(R.string.calendar_title);
        setSyncing(true);
        io.execute(
                () -> {
                    Exception failure = calendarPass(filterOf(PimDomain.CALENDAR));
                    postAlive(
                            () -> {
                                setSyncing(false);
                                calendarList.reload();
                                if (failure != null) {
                                    showError(failure, R.string.sync_failed);
                                }
                            });
                });
    }

    /**
     * Every calendar account synced, on the calling thread: those the
     * scope takes. Answers the first failure.
     */
    private Exception calendarPass(SyncScope scope) {
        syncTitle(R.string.calendar_title);
        Exception failure = null;
        // NOTE: the calendar accounts, rather than the contacts accounts that
        // happened to be CalDAV-shaped. That filter was the closest thing to a
        // calendar account the app had before the connection flow could make
        // one, and it walked a CardDAV home looking for calendars.
        for (AccountEntry account : accountsFor(PimDomain.CALENDAR)) {
            if (!scope.account(account.email)) {
                continue;
            }
            Exception error = fetchCalendars(account, scope);
            if (failure == null) {
                failure = error;
            }
        }
        return failure;
    }

    /**
     * One account's mail reconciled with its server, on the calling
     * thread. Answers what went wrong, or null.
     *
     * <p>One walk, then one engine pass per mailbox off it: the walk is
     * what authenticates and what says which mailboxes there are, and
     * what a pass does instead of replacing is reconcile, which is what
     * lets a staged marker or a staged delete survive it.
     *
     * <p>The mailboxes then run side by side on the account's pool
     * ({@link MailPool}), in the pass's order, the inbox first: the wait is
     * the network's, mailbox after mailbox, and a few sessions wait on a few
     * mailboxes at once. The caller's session is the pool's first, so the
     * walk, the drain before it and the first worker share one connection.
     * One mailbox failing leaves the others running; the first failure is
     * what the pass reports.
     */
    private Exception fetchMail(AccountEntry account, MailSession session, SyncScope scope) {
        syncTitle(R.string.mail_title, account.email);
        String accountId = accountIdOf(account.email);
        List<String> collections = new ArrayList<>();
        try {
            // NOTE: the step at once. Listing the mailboxes is a round trip,
            // and a dialog over a blank line for the length of one reads as
            // a dialog that has not started.
            syncStep(PimDomain.MAIL, PimdirEngine.Progress.STAGE_SERVER, 0);

            List<Mailbox> mailboxes =
                    new MailEngine(pimdir, client, session, accountId).mailboxes();
            mail.replaceMailboxes(account.email, mailboxes);

            // NOTE: the inbox first, then the sent mail, rather than in the
            // order the server lists them: a first pass lists one chunk of
            // each, and the inbox is what the list has to show first.
            for (Mailbox mailbox : MailEngine.ordered(mailboxes)) {
                // NOTE: a pass scoped to the filter skips what it hides. The
                // roster above is still read, so the filter keeps offering it.
                String collection = mail.collectionOf(account.email, mailbox.name);
                if (!scope.collection(account.email, collection)) {
                    continue;
                }
                collections.add(collection);
            }
        } catch (Exception error) {
            Log.w("pimalaya", "mail sync failed: " + account.email, error);
            return error;
        }

        MailPool.Outcome outcome;
        try (MailPool<MailSession> pool =
                new MailPool<>(
                        MailPool.sizeOf(account.server(PimDomain.MAIL)),
                        session,
                        () -> openMail(account))) {
            outcome =
                    pool.run(
                            account.email,
                            collections,
                            worker -> new MailEngine(pimdir, client, worker, accountId),
                            MailEngine::sync,
                            (done, total) ->
                                    syncProgress(R.string.sync_step_mailboxes, done, total));
        }
        for (MailPool.Failure failure : outcome.failures) {
            Log.w("pimalaya", "mail sync failed: " + failure.collection, failure.error);
        }
        if (outcome.failure() == null) {
            SyncStamps.mark(this, account.email);
        }
        return outcome.failure();
    }

    /**
     * One account's calendars reconciled with its server, on the calling
     * thread. Answers what went wrong, or null; a calendar whose pass
     * fails leaves the ones beside it alone.
     */
    private Exception fetchCalendars(AccountEntry account, SyncScope scope) {
        syncTitle(R.string.calendar_title, account.email);
        // NOTE: one session for the whole account, so the listing and every
        // event round after it share the token a refresh may have replaced
        // part-way; the listing's transport is then the first calendar
        // worker's.
        SyncRunner.Session session = runner.session(account, PimDomain.CALENDAR);
        try (Transport transport = new Transport()) {
            try {
                // NOTE: as the mail pass does, and for the same reason: the
                // calendar listing is a discovery walk, and it is the
                // slowest round trip of the pass.
                syncStep(PimDomain.CALENDAR, PimdirEngine.Progress.STAGE_SERVER, 0);
                events.replaceCalendars(
                        account.email,
                        session.call(server -> client.listCalendars(transport, server)));
            } catch (Exception error) {
                Log.w("pimalaya", "calendar list failed: " + account.email, error);
                return error;
            }

            List<String> calendars = new ArrayList<>();
            for (EventStore.StoredCalendar calendar : events.loadCalendars()) {
                if (!calendar.accountEmail.equals(account.email)) {
                    continue;
                }
                if (!scope.collection(account.email, calendar.id)) {
                    continue;
                }
                calendars.add(calendar.id);
            }

            // NOTE: the calendars side by side, as the mailboxes are, the
            // listing's transport the first worker's: the wait was the
            // network's, one calendar after another.
            String accountId = accountIdOf(account.email);
            Exception failure;
            try (CalendarPool<Transport> pool =
                    new CalendarPool<>(CalendarPool.SIZE, transport, Transport::new)) {
                CalendarPool.Outcome outcome =
                        pool.run(
                                account.email,
                                calendars,
                                (worker, collection, remote) ->
                                        session.call(
                                                server -> {
                                                    CalendarEngine engine =
                                                            new CalendarEngine(
                                                                    pimdir,
                                                                    client,
                                                                    worker,
                                                                    server,
                                                                    accountId);
                                                    engine.progress = this::syncStep;
                                                    try {
                                                        engine.sync(collection);
                                                    } finally {
                                                        remote.addAndGet(engine.remoteSoFar());
                                                    }
                                                    return null;
                                                }));
                for (CalendarPool.Failure failed : outcome.failures) {
                    Log.w("pimalaya", "calendar sync failed: " + failed.collection, failed.error);
                }
                failure = outcome.failure();
            }
            if (failure == null) {
                SyncStamps.mark(this, account.email);
            }
            return failure;
        }
    }

    /** The store id one account's collections are namespaced under. */
    private String accountIdOf(String email) {
        if (accountIds == null) {
            accountIds = new PimdirAccount(this);
        }
        return accountIds.idOf(email);
    }

    /** The account-to-store-id map, opened the first time one is asked for. */
    private PimdirAccount accountIds;

    /**
     * A mail driver that only stages: no account, so it services the
     * storage yields a mutation makes and can reach no server.
     *
     * <p>Which is what every action in the reader and the composer needs.
     * Staging is a disk write, and the push it derives is the next sync's.
     */
    MailEngine mailEngine(String email) {
        return new MailEngine(pimdir, client, null, accountIdOf(email));
    }

    /** A calendar driver that only stages, on the same terms. */
    CalendarEngine calendarEngine(String email) {
        return new CalendarEngine(pimdir, client, null, null, accountIdOf(email));
    }

    /**
     * A freshly connected account: each domain it covers owes its first sync,
     * and the flow lands on the first of them, whose tab runs it.
     *
     * <p>One domain at a time, each the first time its tab is reached, rather
     * than all of them behind one dialog: a first sync of every mailbox's
     * whole metadata, then every book, then every calendar, kept the user
     * waiting on what they had not asked to see yet. The landing tab, mail
     * when the account has it, syncs the newest chunk of every mailbox, the
     * inbox first; the rest of the mail fills in behind it
     * ({@link #fillMail}), and contacts and calendars sync when their tab is
     * opened ({@link #firstSyncIfOwed}).
     */
    void syncConnected(AccountEntry account) {
        for (PimDomain domain : PimDomain.values()) {
            if (account.covers(domain)) {
                FirstSync.owe(this, account.email, domain);
            }
        }
        main.post(() -> goDomain(landingPanel(account)));
    }

    /** The domain a list panel shows, null for any other screen. */
    private static PimDomain domainOf(int panel) {
        switch (panel) {
            case PANEL_MAIL:
                return PimDomain.MAIL;
            case PANEL_CONTACTS:
                return PimDomain.CONTACTS;
            case PANEL_CALENDAR:
                return PimDomain.CALENDAR;
            default:
                return null;
        }
    }

    /**
     * Runs the first sync a domain's tab owes, the first time it is reached
     * after an account was connected: that domain alone, behind the modal
     * dialog. Nothing when no account owes it, or while a sync runs.
     */
    private void firstSyncIfOwed(int panel) {
        PimDomain domain = domainOf(panel);
        if (domain == null || screen != panel || syncing || isFinishing()) {
            return;
        }
        List<AccountEntry> owing = new ArrayList<>();
        for (String email : FirstSync.owing(this, domain)) {
            for (AccountEntry account : accountsFor(domain)) {
                // NOTE: an account that is off keeps owing it.
                if (account.email.equals(email) && AccountActivation.enabled(this, email)) {
                    owing.add(account);
                }
            }
        }
        if (owing.isEmpty()) {
            return;
        }

        switch (domain) {
            case MAIL:
                firstMail(owing);
                break;
            case CONTACTS:
                firstContacts(owing);
                break;
            case CALENDAR:
                firstCalendars(owing);
                break;
        }
    }

    /**
     * The mail tab's first sync: every owing account's mailboxes, a chunk of
     * the newest {@link MailEngine#FIRST_CHUNK} each, the inbox first. The
     * dialog waits for those chunks alone; the background fill takes the
     * rest once it closes.
     */
    private void firstMail(List<AccountEntry> owing) {
        syncTitle(R.string.mail_title);
        setSyncing(true);
        io.execute(
                () -> {
                    syncTitle(R.string.mail_title);
                    Exception failure = null;
                    for (AccountEntry account : owing) {
                        Exception error;
                        try (MailSession session = openMail(account)) {
                            error = fetchMail(account, session, SyncScope.all(this));
                        } catch (Exception opened) {
                            error = opened;
                        }
                        if (error == null) {
                            FirstSync.paid(this, account.email, PimDomain.MAIL);
                        } else if (failure == null) {
                            failure = error;
                        }
                    }
                    Exception outcome = failure;
                    postAlive(
                            () -> {
                                setSyncing(false);
                                mailList.reload();
                                if (outcome != null) {
                                    showError(outcome, R.string.sync_failed);
                                }
                                fillMail();
                            });
                });
    }

    /**
     * The contacts tab's first sync: the remote pass over every subscribed
     * book, the books the owing accounts just subscribed among them.
     */
    private void firstContacts(List<AccountEntry> owing) {
        syncTitle(R.string.contacts_title);
        setSyncing(true);
        io.execute(
                () -> {
                    SyncRunner.Outcome outcome = runner.syncRemote();
                    if (outcome.failure == null) {
                        for (AccountEntry account : owing) {
                            FirstSync.paid(this, account.email, PimDomain.CONTACTS);
                        }
                    }
                    postAlive(
                            () -> {
                                setSyncing(false);
                                reloadContacts();
                                reportSync(outcome);
                            });
                });
    }

    /** The calendar tab's first sync: every owing account's calendars. */
    private void firstCalendars(List<AccountEntry> owing) {
        syncTitle(R.string.calendar_title);
        setSyncing(true);
        io.execute(
                () -> {
                    syncTitle(R.string.calendar_title);
                    Exception failure = null;
                    for (AccountEntry account : owing) {
                        Exception error = fetchCalendars(account, SyncScope.all(this));
                        if (error == null) {
                            FirstSync.paid(this, account.email, PimDomain.CALENDAR);
                        } else if (failure == null) {
                            failure = error;
                        }
                    }
                    Exception outcome = failure;
                    postAlive(
                            () -> {
                                setSyncing(false);
                                calendarList.reload();
                                if (outcome != null) {
                                    showError(outcome, R.string.sync_failed);
                                }
                            });
                });
    }

    /**
     * Starts the background fill when it is allowed and not running: every
     * mailbox widened a chunk of {@link MailEngine#FILL_CHUNK} messages at a
     * time toward its account's bound, the inbox and the sent mail first, no
     * dialog, each page landing in the list as a pass's do.
     *
     * <p>One chunk per task on the io thread, so a pull or a widening the
     * user asks for waits one chunk at most. It stops when the app leaves the
     * foreground, the network is metered or gone, another sync runs, or a
     * chunk fails, and nothing is kept but the floors the store covers: the
     * next start, on return or after a pass, resumes from them.
     *
     * <p>Each step also downloads the bodies an account's offline setting
     * asks for ({@link MailBodies}), on the network rule of its own: never
     * periodic work, and nothing once the app leaves the foreground. A start
     * while the loop runs replans them, which is how a pass or a changed
     * setting reaches a loop under way.
     */
    void fillMail() {
        replanBodies = true;
        if (filling || !foreground || syncing || !online()) {
            return;
        }
        filling = true;
        io.execute(this::fillStep);
    }

    /** One step of the background fill and the body step, on the io thread, and the next queued. */
    private void fillStep() {
        MailFill.Step step = fillStopped ? MailFill.Step.DONE : MailFill.step(fillHost());
        Log.d("pimalaya", "mail fill: " + step);
        if (step == MailFill.Step.DONE || step == MailFill.Step.FAILED) {
            fillStopped = true;
        }

        // NOTE: replanned when asked, and when the plan is spent while the
        // fill still lists more; the plan is otherwise walked once a run.
        if (bodies == null || replanBodies || (bodies.drained() && step == MailFill.Step.AGAIN)) {
            replanBodies = false;
            bodies = planBodies();
        }
        MailBodies.Step body = MailBodies.step(bodies, bodyHost());
        Log.d("pimalaya", "mail bodies: " + body);

        if (step == MailFill.Step.AGAIN || body == MailBodies.Step.AGAIN) {
            io.execute(this::fillStep);
            return;
        }
        closeFillPools();
        bodies = null;
        fillStopped = false;
        filling = false;
        if (!bodiesLeft.isEmpty()) {
            bodiesLeft.clear();
            postAlive(this::refreshDrawer);
        }
    }

    /** The fill step's reach: what it may do now, the mailboxes, the pools. */
    private MailFill.Host fillHost() {
        return new MailFill.Host() {
            @Override
            public boolean allowed() {
                return fillAllowed();
            }

            @Override
            public List<MailStore.Edge> edges() {
                // NOTE: an account that is off syncs nothing, the
                // background fill included.
                List<MailStore.Edge> edges = mail.edges();
                edges.removeIf(
                        edge ->
                                !AccountActivation.enabled(
                                        MainActivity.this, edge.accountEmail));
                return edges;
            }

            @Override
            public int room(String accountEmail) {
                AccountEntry account = mailAccount(accountEmail);
                return account == null
                        ? 1
                        : MailPool.sizeOf(account.server(PimDomain.MAIL));
            }

            @Override
            public void widen(MailStore.Edge edge) throws Exception {
                widen(List.of(edge));
            }

            @Override
            public void widen(List<MailStore.Edge> edges) throws Exception {
                Exception failure =
                        widenAll(edges, MailEngine.FILL_CHUNK, "mail fill stopped: ");
                if (failure != null) {
                    throw failure;
                }
            }
        };
    }

    /**
     * What the body step downloads this run, by account: every account that
     * is on and whose setting downloads bodies, the mailboxes the mail list
     * shows first, each newest first within its bound. Walked on the io
     * thread.
     */
    private MailBodies.Run planBodies() {
        MailBodies.Run run = new MailBodies.Run();
        // NOTE: read afresh from what was kept, off the main thread.
        MergedFilter shown = MergedFilter.of(this, PimDomain.MAIL);
        for (AccountEntry account : accountsFor(PimDomain.MAIL)) {
            String email = account.email;
            String id = accountIdOf(email);
            if (!AccountActivation.enabled(this, email) || !MailOffline.downloadsAny(this, id)) {
                continue;
            }
            List<String> first = new ArrayList<>();
            List<String> then = new ArrayList<>();
            for (PimdirCollections.Stored stored : mail.loadMailboxes(List.of(email))) {
                if (MailOffline.downloads(this, id, stored.id)) {
                    (shown.accepts(email, stored.id) ? first : then).add(stored.id);
                }
            }
            java.util.function.Function<String, String> boundOf =
                    collection -> MailScope.sinceOf(this, id, collection);
            List<MailBodies.Row> wanted =
                    new ArrayList<>(MailBodies.wanted(mail.bodyRows(first), boundOf));
            wanted.addAll(MailBodies.wanted(mail.bodyRows(then), boundOf));
            run.plan(email, wanted);
            if (wanted.isEmpty()) {
                bodiesLeft.remove(email);
            } else {
                bodiesLeft.put(email, wanted.size());
            }
        }
        postAlive(this::refreshDrawer);
        return run;
    }

    /** The body step's reach: the network rule, the pools, the progress. */
    private MailBodies.Host bodyHost() {
        return new MailBodies.Host() {
            @Override
            public boolean allowed(String accountEmail) {
                return bodiesAllowed(accountEmail);
            }

            @Override
            public void download(String accountEmail, List<MailBodies.Row> rows)
                    throws Exception {
                downloadBodies(accountEmail, rows);
            }

            @Override
            public void progress(String accountEmail, int left) {
                if (left > 0) {
                    bodiesLeft.put(accountEmail, left);
                } else {
                    bodiesLeft.remove(accountEmail);
                }
                postAlive(MainActivity.this::refreshDrawer);
            }
        };
    }

    /**
     * Whether an account's bodies may download now: the app in the
     * foreground, no other sync, a network that is there, and unmetered
     * unless the account allows a metered one.
     */
    private boolean bodiesAllowed(String accountEmail) {
        if (!foreground || syncing || !AccountActivation.enabled(this, accountEmail)) {
            return false;
        }
        android.net.ConnectivityManager connectivity =
                getSystemService(android.net.ConnectivityManager.class);
        return connectivity != null
                && MailBodies.networkAllows(
                        connectivity.getActiveNetwork() != null,
                        connectivity.isActiveNetworkMetered(),
                        MailOffline.metered(this, accountIdOf(accountEmail)));
    }

    /**
     * Downloads some of an account's bodies on its fill pool, a mailbox's
     * rows split across the pool's sessions, blocking until all are in. A
     * failure closes the pool, so the next run opens fresh sessions (and
     * renews an expired token on the way).
     */
    private void downloadBodies(String accountEmail, List<MailBodies.Row> rows) throws Exception {
        AccountEntry account = mailAccount(accountEmail);
        if (account == null) {
            return;
        }
        MailPool<MailSession> pool = fillPool(account);

        Map<String, List<MailBodies.Row>> byCollection = new java.util.LinkedHashMap<>();
        for (MailBodies.Row row : rows) {
            byCollection.computeIfAbsent(row.collection, collection -> new ArrayList<>()).add(row);
        }
        List<String> keys = new ArrayList<>();
        Map<String, List<MailBodies.Row>> jobs = new HashMap<>();
        for (List<MailBodies.Row> held : byCollection.values()) {
            int slice = Math.max(1, (held.size() + pool.size() - 1) / pool.size());
            for (int from = 0; from < held.size(); from += slice) {
                String key = Integer.toString(keys.size());
                keys.add(key);
                jobs.put(key, held.subList(from, Math.min(held.size(), from + slice)));
            }
        }

        String accountId = accountIdOf(account.email);
        MailPool.Outcome outcome =
                pool.run(
                        "bodies " + account.email,
                        keys,
                        session -> new MailEngine(pimdir, client, session, accountId),
                        (engine, key) -> {
                            List<MailBodies.Row> job = jobs.get(key);
                            engine.download(job.get(0).collection, job);
                        },
                        null);
        if (outcome.failure() != null) {
            fillPools.remove(account.email);
            pool.close();
            throw outcome.failure();
        }
    }

    /** Redraws the drawer's account cards when it is open, for their pills. */
    private void refreshDrawer() {
        if (drawer != null && drawer.isDrawerOpen(Gravity.START)) {
            reloadHome();
        }
    }

    /** The mail account of an address, null when it is gone. */
    private AccountEntry mailAccount(String email) {
        for (AccountEntry candidate : accountsFor(PimDomain.MAIL)) {
            if (candidate.email.equals(email)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Widens mailboxes by one chunk each, on the io thread: each account's
     * on its pool ({@link MailPool}), side by side and in the order given,
     * every account at once, on connections the fill keeps open between its
     * steps. Answers the first failure, every one logged under
     * {@code failed}; one mailbox failing leaves the others going.
     */
    private Exception widenAll(List<MailStore.Edge> edges, int count, String failed) {
        Map<String, List<String>> byAccount = new java.util.LinkedHashMap<>();
        for (MailStore.Edge edge : edges) {
            byAccount.computeIfAbsent(edge.accountEmail, email -> new ArrayList<>())
                    .add(edge.collection);
        }

        List<java.util.concurrent.Callable<MailPool.Outcome>> runs = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byAccount.entrySet()) {
            AccountEntry account = mailAccount(entry.getKey());
            if (account == null) {
                continue;
            }
            MailPool<MailSession> held = fillPool(account);
            String accountId = accountIdOf(account.email);
            runs.add(
                    () ->
                            held.run(
                                    account.email,
                                    entry.getValue(),
                                    session -> new MailEngine(pimdir, client, session, accountId),
                                    (engine, collection) -> engine.widen(collection, count),
                                    null));
        }

        Exception first = null;
        for (MailPool.Outcome outcome : MailPool.together(runs)) {
            for (MailPool.Failure failure : outcome.failures) {
                Log.w("pimalaya", failed + failure.collection, failure.error);
            }
            if (first == null) {
                first = outcome.failure();
            }
        }
        return first;
    }

    /** An account's pool for the fill, the scroll and the body step; on the io thread. */
    private MailPool<MailSession> fillPool(AccountEntry account) {
        MailPool<MailSession> pool = fillPools.get(account.email);
        if (pool == null) {
            pool =
                    new MailPool<>(
                            MailPool.sizeOf(account.server(PimDomain.MAIL)),
                            null,
                            () -> openMail(account));
            fillPools.put(account.email, pool);
        }
        return pool;
    }

    /** Closes the connections the fill and the scroll kept; on the io thread. */
    private void closeFillPools() {
        for (MailPool<MailSession> pool : fillPools.values()) {
            pool.close();
        }
        fillPools.clear();
    }

    /**
     * Whether the background fill may run now: the app in the foreground, no
     * other sync, and a network that is there and not metered.
     */
    private boolean fillAllowed() {
        if (!foreground || syncing) {
            return false;
        }
        android.net.ConnectivityManager connectivity =
                getSystemService(android.net.ConnectivityManager.class);
        return connectivity != null
                && connectivity.getActiveNetwork() != null
                && !connectivity.isActiveNetworkMetered();
    }

    /** Whether a network is there at all, for a widening the user scrolled to. */
    boolean online() {
        android.net.ConnectivityManager connectivity =
                getSystemService(android.net.ConnectivityManager.class);
        return connectivity != null && connectivity.getActiveNetwork() != null;
    }

    /**
     * Widens the mailboxes a scroll reached the end of: every shown mailbox
     * whose floor limits the list, by one chunk of
     * {@link MailEngine#FIRST_CHUNK} each, side by side, on the io thread,
     * then tells {@code done} on the main thread whether every one went
     * through.
     */
    void widenMail(java.util.function.Predicate<String> shown, java.util.function.Consumer<Boolean> done) {
        io.execute(
                () -> {
                    Exception failure =
                            widenAll(
                                    MailFill.limiting(mail.edges(), shown),
                                    MailEngine.FIRST_CHUNK,
                                    "older mail failed: ");
                    if (!filling) {
                        closeFillPools();
                    }
                    boolean outcome = failure == null;
                    postAlive(() -> done.accept(outcome));
                });
    }

    /** The list a freshly connected account lands on: its first domain. */
    private int landingPanel(AccountEntry account) {
        if (account.covers(PimDomain.MAIL)) {
            return PANEL_MAIL;
        }
        return account.covers(PimDomain.CONTACTS) ? PANEL_CONTACTS : PANEL_CALENDAR;
    }

    /**
     * The contacts list's pull: the addressbooks the filter shows,
     * reconciled with their servers, then the phone's own pass. The
     * drawer's sync is the one that takes every domain and everything.
     */
    void syncContacts() {
        if (!phoneSyncedBooks().isEmpty() && !ensureContactsPermission(this::syncContacts)) {
            return;
        }

        syncTitle(R.string.contacts_title);
        setSyncing(true);
        io.execute(
                () -> {
                    SyncRunner.Outcome outcome = runner.syncRemote(filterOf(PimDomain.CONTACTS));
                    OfflineEngine.Report report = new OfflineEngine.Report();
                    Exception failure = runner.syncLocal(report);
                    outcome.absorb(report);
                    outcome.local |= !runner.phoneSyncedBooks().isEmpty();
                    if (outcome.failure == null) {
                        outcome.failure = failure;
                    }
                    postAlive(
                            () -> {
                                setSyncing(false);
                                reloadContacts();
                                reportSync(outcome);
                            });
                });
    }

    /**
     * Syncs every domain in turn, contacts then mail then calendars, the
     * dialog naming each as it goes: the drawer's sync, which takes every
     * collection of every account that is on whatever the filters hide,
     * where a list's pull takes its own domain within its filter.
     */
    void syncAll() {
        // NOTE: only the phone passes need the contacts permission;
        // reconciling the Android accounts runs regardless.
        if (!phoneSyncedBooks().isEmpty() && !ensureContactsPermission(this::syncAll)) {
            return;
        }

        syncTitle(R.string.contacts_title);
        setSyncing(true);
        io.execute(
                () -> {
                    SyncRunner.Outcome outcome = runner.syncRemote();
                    OfflineEngine.Report report = new OfflineEngine.Report();
                    Exception failure = runner.syncLocal(report);
                    outcome.absorb(report);
                    outcome.local |= !runner.phoneSyncedBooks().isEmpty();
                    if (outcome.failure == null) {
                        outcome.failure = failure;
                    }

                    MailPass sent = mailPass(SyncScope.all(this));
                    Exception calendars = calendarPass(SyncScope.all(this));
                    Exception other = sent.failure != null ? sent.failure : calendars;
                    postAlive(
                            () -> {
                                setSyncing(false);
                                reloadContacts();
                                mailList.reload();
                                calendarList.reload();
                                reportSync(outcome);
                                reportMail(sent);
                                fillMail();
                                // NOTE: one error dialog, the contacts one first.
                                if (other != null && outcome.failure == null) {
                                    showError(other, R.string.sync_failed);
                                }
                            });
                });
    }

    /**
     * The phone spoke alone, in-process: reconciles the per-addressbook
     * Android accounts, then runs the two-way phone engine pass per
     * subscribed book right here, behind the same spinner as the remote
     * sync (SyncService keeps serving the syncs the OS schedules on its
     * own). Needs the contacts
     * permission, requested on first use; the full sync's own phone
     * passes stay silently off until this ran once.
     */
    private void syncLocal() {
        if (!ensureContactsPermission(this::syncLocal)) {
            return;
        }

        syncTitle(R.string.contacts_title);
        setSyncing(true);
        io.execute(
                () -> {
                    OfflineEngine.Report report = new OfflineEngine.Report();
                    Exception failure = runner.syncLocal(report);
                    postAlive(
                            () -> {
                                setSyncing(false);
                                if (failure != null) {
                                    showError(failure, R.string.accounts_failed);
                                } else {
                                    toast(
                                            getString(
                                                    R.string.sync_line_local,
                                                    report.localIn.size(),
                                                    report.localOut.size(),
                                                    report.localChanged.size()));
                                    reloadContacts();
                                }
                            });
                });
    }

    /** The subscribed addressbooks set to mirror into the phone. */
    List<BookEntry> phoneSyncedBooks() {
        return runner.phoneSyncedBooks();
    }

    /**
     * Ensures the contacts permission before a phone-touching sync;
     * when it must be requested, remembers `retry` to run once granted
     * and returns false.
     */
    private boolean ensureContactsPermission(Runnable retry) {
        if (hasContactsPermission()) {
            return true;
        }
        afterContactsPermission = retry;
        requestPermissions(
                new String[] {
                    Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                },
                REQUEST_CONTACTS);
        return false;
    }

    /**
     * Opens a merged row in the editor: one form for the whole contact
     * (docs/merged-view.md), the union of the replicas' documents with
     * per-field alternative chips when they diverge. Saving fans the
     * form out onto every replica.
     */
    void openGroup(Group group) {
        if (group.conflicted()) {
            openConflict(group);
        } else {
            openMerged(group.replicas);
        }
    }

    /**
     * Opens the resolution form on a both-sides-edited conflict: the
     * bridge three-way merges the stored base, the local edit and the
     * captured remote (the newer side by REV pre-filled, both offered as
     * chips), and the editor loads it in conflict mode. A conflict with
     * nothing genuinely colliding (only list edits, which merge cleanly)
     * resolves straight away without a prompt.
     */
    private void openConflict(Group group) {
        Entry replica = null;
        for (Entry entry : group.replicas) {
            if (entry.conflicted) {
                replica = entry;
                break;
            }
        }
        if (replica == null) {
            openMerged(group.replicas);
            return;
        }

        String handle =
                CardStore.rowHandle(replica.card.uri, replica.card.id);
        JSONObject bodies;
        JSONObject resolution;
        try {
            bodies = new PimdirStorage(pimdir).loadConflict(replica.book.url, handle);
            if (bodies == null) {
                // NOTE: flagged but its remote is not captured yet (the
                // capturing sync has not run); edit it plainly.
                openMerged(group.replicas);
                return;
            }
            resolution =
                    Cards.mergeConflictForm(
                            bodies.optString("base"),
                            bodies.optString("local"),
                            bodies.optString("remote"));
        } catch (Exception error) {
            showError(error, R.string.contact_open_failed);
            return;
        }

        if (resolution.optBoolean("resolved")) {
            // Nothing needs the user: stage the clean merge and clear the
            // conflict. The toast is the tap's only visible outcome, so
            // without it the vanishing flag reads as a bug.
            try {
                new OfflineEngine(base, pimdir, client, null, null, null)
                        .mutateEdit(replica.book.url, handle, resolution.optString("vcard"));
            } catch (Exception error) {
                showError(error, R.string.save_failed);
                return;
            }
            toast(getString(R.string.conflict_auto_resolved));
            reloadContacts();
            return;
        }

        JSONObject alternatives = resolution.optJSONObject("alternatives");
        edit = new EditSession();
        edit.book = replica.book;
        edit.accountEmail = replica.accountEmail;
        edit.card = replica.card;
        edit.replicas = new ArrayList<>(java.util.Collections.singletonList(replica));
        edit.resolvingConflict = true;
        edit.vcard = resolution.optString("vcard");
        edit.title = replica.displayName();

        org.json.JSONArray changed = resolution.optJSONArray("changed");
        if (changed == null) {
            changed = new org.json.JSONArray();
        }
        form.load(resolution.optJSONObject("model"), alternatives, changed);
        show(PANEL_CONTACT);
    }

    /** Prompts for a vCard file to import. */
    void importContacts() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (request == REQUEST_IMPORT) {
            vcfTransfer.importFile(data.getData());
        } else if (request == REQUEST_EXPORT) {
            vcfTransfer.exportFile(data.getData());
        }
    }

    /** Prompts for a destination file, then exports the active view as a
     *  single vCard file. */
    void exportContacts() {
        if (contactsList.visibleGroups().isEmpty()) {
            toast(getString(R.string.export_empty));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/vcard");
        intent.putExtra(Intent.EXTRA_TITLE, "contacts.vcf");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    /** The active contacts view, for the vCard export snapshot. */
    List<Group> visibleGroups() {
        return contactsList.visibleGroups();
    }

    /**
     * Starts a new contact in the default address book of the account in
     * view, asking only when there is none ({@link DefaultCollection}). With
     * no account the contact is created unattached (the hidden local book);
     * the local book is never offered as an explicit target.
     */
    private void addContact() {
        Map<String, BookEntry> byUrl = new HashMap<>();
        BookEntry local = null;
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            if (LocalBook.is(entry.accountEmail)) {
                local = entry;
            } else {
                byUrl.put(entry.book.url, entry);
            }
        }

        // With no real account the contact is born unattached, in the
        // hidden local book.
        if (byUrl.isEmpty()) {
            if (local != null) {
                openNewContact(local.book, local.accountEmail);
            }
            return;
        }

        List<PimdirCollections.Stored> books = new ArrayList<>();
        for (PimdirCollections.Stored book : collectionsOf(PimDomain.CONTACTS)) {
            if (byUrl.containsKey(book.id)) {
                books.add(book);
            }
        }
        target(
                PimDomain.CONTACTS,
                PimdirSummary.CONTACT,
                R.string.save_target_title,
                R.string.contacts_no_book,
                books,
                book -> openNewContact(byUrl.get(book.id).book, book.accountEmail));
    }

    /**
     * Starts a new calendar entry in the default calendar of the account
     * in view, asking only when there is none, exactly as a new contact
     * picks its address book. A read-only calendar is never offered.
     */
    private void composeEvent() {
        Map<String, EventStore.StoredCalendar> byId = new HashMap<>();
        for (EventStore.StoredCalendar calendar : events.loadCalendars()) {
            byId.put(calendar.id, calendar);
        }
        target(
                PimDomain.CALENDAR,
                PimdirSummary.CALENDAR,
                R.string.event_target_title,
                R.string.event_no_calendar,
                collectionsOf(PimDomain.CALENDAR),
                calendar -> eventView.compose(byId.get(calendar.id)));
    }

    /**
     * Opens a new item of a domain in its target collection: the default
     * when one is plain, else the one the user picks among the writable
     * ones, the default preselected.
     */
    private void target(
            PimDomain domain,
            String kind,
            int title,
            int none,
            List<PimdirCollections.Stored> collections,
            java.util.function.Consumer<PimdirCollections.Stored> open) {
        DefaultCollection.Choice choice =
                DefaultCollection.choice(this, kind, collections, filterOf(domain)::accepts);
        if (choice.direct != null) {
            open.accept(choice.direct);
            return;
        }
        if (choice.offered.isEmpty()) {
            toast(getString(none));
            return;
        }

        CharSequence[] labels = new CharSequence[choice.offered.size()];
        for (int index = 0; index < labels.length; index++) {
            PimdirCollections.Stored collection = choice.offered.get(index);
            labels[index] = bookLabel(collection.name, collection.accountEmail);
        }
        int[] picked = {choice.preselected};
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(
                        labels, choice.preselected, (dialog, which) -> picked[0] = which)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> {
                            if (picked[0] >= 0) {
                                open.accept(choice.offered.get(picked[0]));
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The subscribed addressbooks the group does not already live in. */
    private List<BookEntry> booksOutside(Group group) {
        java.util.Set<String> current = new java.util.HashSet<>();
        for (Entry entry : group.replicas) {
            current.add(entry.book.url);
        }

        List<BookEntry> books = new ArrayList<>();
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            if (!current.contains(entry.book.url)) {
                books.add(entry);
            }
        }
        return books;
    }

    /** One two-line label per addressbook of a picker dialog. */
    CharSequence[] bookLabels(List<BookEntry> books) {
        CharSequence[] labels = new CharSequence[books.size()];
        for (int index = 0; index < books.size(); index++) {
            BookEntry entry = books.get(index);
            // NOTE: the local book has no account email, so it shows on
            // one line.
            labels[index] =
                    LocalBook.is(entry.accountEmail)
                            ? getString(R.string.local_book)
                            : bookLabel(entry.book.name, entry.accountEmail);
        }
        return labels;
    }

    /**
     * A two-line addressbook label: the name at full size over the
     * account email, diminished and secondary (same styling as the
     * auth config options), so long names and emails never wrap into
     * one unreadable line.
     */
    CharSequence bookLabel(String name, String email) {
        android.text.SpannableString label = new android.text.SpannableString(name + "\n" + email);
        int start = name.length() + 1;
        label.setSpan(
                new android.text.style.RelativeSizeSpan(0.8f),
                start,
                label.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(
                new android.text.style.ForegroundColorSpan(
                        resolveColor(android.R.attr.textColorSecondary)),
                start,
                label.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }

    /** Stages a copied card into the target addressbook. */
    void createCopy(BookEntry target, AccountEntry account, String id, String vcard) {
        contacts.save(target.book.url, new Card(id, null, null, vcard));
    }

    /** The survivor chooser over any replica list, then the merge. */
    void mergeReplicas(List<Entry> replicas) {
        if (pool.distinctRefs(replicas).size() < 2) {
            return;
        }

        // The first replica of each addressbook is its candidate survivor.
        Map<String, Entry> byBook = new java.util.LinkedHashMap<>();
        for (Entry entry : replicas) {
            byBook.putIfAbsent(entry.book.url, entry);
        }

        List<Entry> candidates = new ArrayList<>(byBook.values());
        if (candidates.size() == 1) {
            performMerge(replicas, candidates.get(0));
            return;
        }

        CharSequence[] labels = new CharSequence[candidates.size()];
        for (int index = 0; index < candidates.size(); index++) {
            Entry entry = candidates.get(index);
            labels[index] = bookLabel(entry.book.name, entry.accountEmail);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.merge_target_title)
                .setItems(
                        labels,
                        (dialog, which) -> performMerge(replicas, candidates.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Merges into the survivor: a straight absorb when every card
     * already carries the same content (the cards of one merged
     * contact, nothing to reconcile), the merge form otherwise.
     */
    private void performMerge(List<Entry> replicas, Entry survivor) {
        java.util.Set<String> hashes = new java.util.HashSet<>();
        for (Entry entry : replicas) {
            hashes.add(entry.hash);
        }

        if (hashes.size() == 1) {
            mergeDirect(replicas, survivor);
        } else {
            openMergeForm(replicas, survivor);
        }
    }

    /** Absorbs identical cards into the survivor, no form needed. */
    private void mergeDirect(List<Entry> replicas, Entry survivor) {
        contactsList.exitSelection();

        String survivorRef = pool.replicaRef(survivor);
        java.util.Set<String> removed = new java.util.HashSet<>();
        for (Entry entry : replicas) {
            String ref = pool.replicaRef(entry);
            if (ref.equals(survivorRef) || !removed.add(ref)) {
                continue;
            }
            contacts.stageDelete(entry.book.url, entry.card.id);
        }

        toast(getString(R.string.merge_done));
        reloadContacts();
    }

    /** Opens the merge form with a surviving replica armed. */
    private void openMergeForm(List<Entry> replicas, Entry survivor) {
        contactsList.exitSelection();
        if (openMerged(replicas)) {
            edit.mergeSurvivor = survivor;
        }
    }

    /** Opens the edit form on a fresh card in the target addressbook. */
    private void openNewContact(Addressbook book, String accountEmail) {
        edit = new EditSession();
        edit.book = book;
        edit.accountEmail = accountEmail;
        edit.vcard = newVcard();
        edit.title = getString(R.string.contact_new);
        edit.advancedAvailable = true;

        form.load(null, null, null);
        show(PANEL_CONTACT);
    }

    /**
     * Opens the edit form on a merged contact: the single document when
     * the replicas agree, their union with per-field alternative chips
     * when they diverge. Saving stages the form onto every replica.
     * Returns false when the documents could not be read.
     */
    boolean openMerged(List<Entry> replicas) {
        Entry primary = replicas.get(0);
        edit = new EditSession();
        edit.book = primary.book;
        edit.accountEmail = primary.accountEmail;
        edit.card = primary.card;
        edit.replicas = new ArrayList<>(replicas);

        // The distinct documents behind the group, one per normalized
        // hash: one means a plain edit, several go through the union merge.
        List<String> docs = new ArrayList<>();
        java.util.Set<String> hashes = new java.util.HashSet<>();
        for (Entry entry : replicas) {
            if (hashes.add(entry.hash)) {
                docs.add(entry.card.vcard);
            }
        }

        JSONObject model;
        JSONObject alternatives = null;
        org.json.JSONArray changed = null;
        try {
            if (docs.size() == 1) {
                edit.vcard = primary.card.vcard;
                model = Cards.projectCard(primary.card);
            } else {
                JSONObject merged = Cards.mergeCards(docs);
                edit.vcard = merged.optString("vcard");
                model = merged.optJSONObject("model");
                alternatives = merged.optJSONObject("alternatives");
                // NOTE: a non-null changed set turns on the form's
                // conflict mode: only the disagreeing fields show.
                changed = merged.optJSONArray("changed");
                if (changed == null) {
                    changed = new org.json.JSONArray();
                }
            }
        } catch (Exception error) {
            showError(error, R.string.contact_open_failed);
            return false;
        }

        edit.title = primary.displayName();
        // NOTE: raw lines cannot fan out to several documents, so the
        // advanced editor only opens on single-card contacts.
        edit.advancedAvailable = pool.distinctRefs(replicas).size() == 1;

        form.load(model, alternatives, changed);
        show(PANEL_CONTACT);
        return true;
    }

    private void setUpContactPanel() {
        findViewById(R.id.contact_books).setOnClickListener(view -> manageBooks());
        // NOTE: the add-field button is the bar's, not the contact
        // editor's: the calendar entry page raises the same one, since
        // both are the same page with the same gesture. Which one it
        // adds to follows the screen showing it.
        findViewById(R.id.contact_add_field)
                .setOnClickListener(
                        view -> {
                            if (screen == PANEL_EVENT_VIEW) {
                                eventView.addField();
                            } else {
                                form.addField();
                            }
                        });
        findViewById(R.id.bar_delete).setOnClickListener(view -> eventView.confirmDelete());
    }

    /**
     * Leaves the editor without saving. The list still reloads: the
     * addressbooks dialog stages copies and removals it must reflect.
     * An unsaved Merge is abandoned.
     */
    private void closeContact() {
        edit = new EditSession();
        reloadContacts();
        showBack(PANEL_CONTACTS);
    }

    /**
     * Opens the raw-property editor on the working document (delegated to
     * {@link AdvancedEditor}). Only offered on single-card contacts (and
     * new ones): raw lines cannot fan out to several physical documents.
     */
    private void openAdvanced() {
        advancedEditor.open();
    }

    /**
     * The addressbooks dialog of the open contact, the one placement
     * gesture: checked means the contact exists there, through any of
     * its cards. Only real addressbooks are listed; unchecking every one
     * is allowed and leaves the contact unattached, living in the hidden
     * local book (it shows muted in the list, Delete is still the way to
     * remove it). The dialog only records the desired state (reopening
     * shows it); everything applies on SAVE: a staged membership when the
     * contact already has a card on that account-level backend, a copied
     * card sharing the vCard UID anywhere else, a membership removal or
     * the card's staged delete on uncheck.
     */
    private void manageBooks() {
        if (edit.replicas.isEmpty()) {
            return;
        }

        Map<String, Entry> replicaByBook = new HashMap<>();
        for (Entry entry : edit.replicas) {
            replicaByBook.put(entry.book.url, entry);
        }

        // NOTE: the hidden local book is not listed; it is the fallback
        // home when nothing is checked.
        List<BookEntry> books = new ArrayList<>();
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            if (!LocalBook.is(entry.accountEmail)) {
                books.add(entry);
            }
        }
        boolean[] checked = new boolean[books.size()];
        for (int index = 0; index < books.size(); index++) {
            String url = books.get(index).book.url;
            Boolean pending = edit.pendingBookState == null ? null : edit.pendingBookState.get(url);
            checked[index] = pending != null ? pending : replicaByBook.containsKey(url);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.manage_books_title)
                .setMultiChoiceItems(
                        bookLabels(books),
                        checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> {
                            edit.pendingBookState = new HashMap<>();
                            boolean anyChecked = false;
                            for (int index = 0; index < books.size(); index++) {
                                edit.pendingBookState.put(books.get(index).book.url, checked[index]);
                                anyChecked |= checked[index];
                            }
                            // Nothing checked leaves the contact unattached,
                            // kept only in the hidden local book.
                            edit.pendingBookState.put(LocalBook.URL, !anyChecked);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Stages the edited contact in the base and returns to the list
     * (delegated to {@link ContactWriter}). The form model applies to
     * every replica's own document (docs/merged-view.md), fanning out
     * onto every replica, conflict and merge branches included.
     */
    private void saveContact() {
        writer.save();
    }

    /** Rebuilds the contacts list from the base (staged edits included). */
    void reloadContacts() {
        contactsList.reload();
    }

    private static String newVcard() {
        return "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:" + UUID.randomUUID() + "\r\nEND:VCARD\r\n";
    }

    /**
     * The bridge's index of a vCard for one-off lookups (name, uid);
     * the hot paths read the same index from the store's columns
     * instead. Empty on a parse failure.
     */
    JSONObject cardIndex(String vcard) {
        try {
            return Cards.indexCard(vcard);
        } catch (Exception error) {
            Log.w("pimalaya", "card index failed", error);
            return new JSONObject();
        }
    }

    /**
     * The list chrome for one domain: the burger onto the drawer, the
     * domain's name (once its large title scrolls away), the filter
     * every list shares, and the bottom navigation with this domain on
     * its indicator. The domain's own actions come after, from its
     * screen.
     */
    private void showDomainBar(int panel) {
        for (int target : Domains.PANELS) {
            boolean selected = target == panel;
            LinearLayout item = findViewById(Domains.buttonOf(target));
            android.widget.FrameLayout indicator = (android.widget.FrameLayout) item.getChildAt(0);
            indicator.setBackgroundResource(selected ? R.drawable.nav_indicator : 0);
            ((android.widget.ImageView) indicator.getChildAt(0))
                    .setImageTintList(
                            ColorStateList.valueOf(
                                    ui.resolveColor(
                                            selected
                                                    ? android.R.attr.textColorPrimary
                                                    : android.R.attr.textColorSecondary)));
            TextView label = (TextView) item.getChildAt(1);
            label.setTextColor(
                    ui.resolveColor(
                            selected
                                    ? android.R.attr.textColorPrimary
                                    : android.R.attr.textColorSecondary));
            label.setTypeface(
                    null,
                    selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        findViewById(R.id.bottom_nav).setVisibility(View.VISIBLE);

        showDomainTitle(panel);
        findViewById(R.id.bar_menu).setVisibility(View.VISIBLE);
        showFilterButton(panel);
    }

    /** Retitles the bar, for a page whose own edits change its title. */
    void updateBarTitle(String title) {
        ((TextView) findViewById(R.id.bar_title)).setText(title);
    }

    /**
     * Titles the bar with a list domain's name, shown only once the
     * list's own large title has scrolled away.
     */
    void showDomainTitle(int panel) {
        TextView title = findViewById(R.id.bar_title);
        title.setText(Domains.titleOf(panel));
        title.setVisibility(View.VISIBLE);
        title.animate().cancel();
        title.setAlpha(headerOf(panel).scrolled() ? 1f : 0f);
    }

    /** Fades the bar's title in or out as a large title leaves or returns. */
    void showBarTitle(boolean shown) {
        if (screen == PANEL_CONTACTS && contactsList.isSelectionMode()
                || screen == PANEL_MAIL && mailList.isSelectionMode()) {
            return;
        }
        findViewById(R.id.bar_title).animate().alpha(shown ? 1f : 0f).setDuration(150);
    }

    /** The large title heading one list. */
    private ListHeader headerOf(int panel) {
        if (panel == PANEL_MAIL) {
            return mailList.header();
        }
        return panel == PANEL_CALENDAR ? calendarList.header() : contactsList.header();
    }

    /** Raises the extended FAB as a list screen's add button. */
    private void listFab(int label, int icon) {
        ((TextView) findViewById(R.id.fab_extended_label)).setText(label);
        ((android.widget.ImageView) findViewById(R.id.fab_extended_icon)).setImageResource(icon);
        View fab = findViewById(R.id.fab_extended);
        fab.setContentDescription(getString(label));
        fab.setVisibility(View.VISIBLE);
        findViewById(R.id.fab).setVisibility(View.GONE);

        // NOTE: unfolded at once, a new screen's button saying what it
        // adds before the list is scrolled.
        if (fabFold != null) {
            fabFold.cancel();
        }
        fabFolded = false;
        View text = findViewById(R.id.fab_extended_label);
        text.setVisibility(View.VISIBLE);
        text.setAlpha(1f);
        android.view.ViewGroup.LayoutParams params = fab.getLayoutParams();
        params.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        fab.setLayoutParams(params);
    }

    /** Whether the extended FAB stands folded to its glyph. */
    private boolean fabFolded;

    /** The running fold, cancelled by the next one. */
    private android.animation.ValueAnimator fabFold;

    /**
     * Folds the extended FAB to its square glyph, or unfolds its label:
     * the width animates between the two while the label fades, so the
     * button itself shrinks rather than only losing its text.
     */
    void foldFab(boolean folded) {
        if (folded == fabFolded) {
            return;
        }
        fabFolded = folded;
        View fab = findViewById(R.id.fab_extended);
        View label = findViewById(R.id.fab_extended_label);
        if (fabFold != null) {
            fabFold.cancel();
        }

        // NOTE: measured with the label in, which is the unfolded width
        // whatever the button stands at now.
        label.setVisibility(View.VISIBLE);
        fab.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(dimen(R.dimen.fab), View.MeasureSpec.EXACTLY));
        int unfolded = fab.getMeasuredWidth();
        int square = dimen(R.dimen.fab);
        int from = fab.getWidth() > 0 ? fab.getWidth() : folded ? unfolded : square;

        android.view.ViewGroup.LayoutParams params = fab.getLayoutParams();
        fabFold = android.animation.ValueAnimator.ofInt(from, folded ? square : unfolded);
        fabFold.setDuration(220);
        fabFold.setInterpolator(new android.view.animation.DecelerateInterpolator());
        fabFold.addUpdateListener(
                animation -> {
                    params.width = (int) animation.getAnimatedValue();
                    fab.setLayoutParams(params);
                });
        fabFold.addListener(
                new android.animation.AnimatorListenerAdapter() {
                    private boolean cancelled;

                    @Override
                    public void onAnimationCancel(android.animation.Animator animation) {
                        cancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        if (cancelled) {
                            return;
                        }
                        label.setVisibility(folded ? View.GONE : View.VISIBLE);
                        params.width =
                                folded ? square : android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                        fab.setLayoutParams(params);
                    }
                });
        label.animate().cancel();
        label.animate().alpha(folded ? 0f : 1f).setDuration(folded ? 120 : 220);
        fabFold.start();
    }

    /** The mail item's unread count, hidden at zero. */
    void showMailBadge(int unread) {
        TextView badge = findViewById(R.id.nav_mail_badge);
        badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
        badge.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
    }

    /** A list's add button, inert while the list works (an import, an export). */
    void setListBusy(boolean busy) {
        View add = findViewById(R.id.fab_extended);
        add.setEnabled(!busy);
        add.setAlpha(busy ? 0.4f : 1f);
    }

    /** The composer's send button, inert while the message is written out. */
    void setSending(boolean sending) {
        View send = findViewById(R.id.bar_send);
        send.setEnabled(!sending);
        send.setAlpha(sending ? 0.4f : 1f);
    }

    /** The filter, on every list screen, accented while it bites. */
    private void showFilterButton(int panel) {
        PimDomain domain = domainOf(panel);
        android.widget.ImageButton button = findViewById(R.id.bar_filter);
        button.setVisibility(View.VISIBLE);
        button.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        domain != null && filterOf(domain).isActive()
                                ? ui.resolveColor(android.R.attr.colorAccent)
                                : ui.resolveColor(android.R.attr.textColorPrimary)));
    }

    /**
     * Switches to another domain. A lateral move from the bottom
     * navigation, so it has no direction to slide in and swaps in place.
     */
    private void switchDomain(int target) {
        if (target != screen) {
            showInstant(target);
        }
    }

    /**
     * One popup entry carrying its own action.
     *
     * <p>Text-only: the framework popup renders forced icons flush against
     * their labels on some Android releases.
     */
    static void item(android.widget.PopupMenu menu, int label, Runnable action) {
        menu.getMenu()
                .add(label)
                .setOnMenuItemClickListener(
                        entry -> {
                            action.run();
                            return true;
                        });
    }

    /** Opens the filter page of the domain on screen. */
    private void openFilter() {
        filterPage.open(domainOf(screen) == null ? PimDomain.CONTACTS : domainOf(screen));
    }

    /** One domain's filter, as it was last left. */
    MergedFilter filterOf(PimDomain domain) {
        return filters.computeIfAbsent(domain, key -> MergedFilter.of(this, key));
    }

    /** A filter changed: the lists and the bar's filter icon follow. */
    void filterChanged() {
        contactsList.reRender();
        calendarList.reload();
        mailList.reload();
    }

    /**
     * One domain's collections as the store holds them, every account's:
     * the mailboxes, the calendars, or the subscribed address books (the
     * on-device one among them).
     */
    List<PimdirCollections.Stored> collectionsOf(PimDomain domain) {
        PimdirCollections collections = new PimdirCollections(pimdir, this);
        switch (domain) {
            case MAIL:
                return collections.list(PimdirSummary.MAIL);
            case CALENDAR:
                return collections.list(PimdirSummary.CALENDAR);
            default:
                java.util.Set<String> subscribed = new java.util.HashSet<>();
                for (BookEntry book : base.loadSubscribedAddressbooks()) {
                    subscribed.add(book.book.url);
                }
                List<PimdirCollections.Stored> books = new ArrayList<>();
                for (PimdirCollections.Stored book : collections.list(PimdirSummary.CONTACT)) {
                    if (subscribed.contains(book.id)) {
                        books.add(book);
                    }
                }
                return books;
        }
    }

    /**
     * What the filter page lists for a domain: every account that is on and
     * covers it, each with its collections, the outbox among a mail
     * account's and the on-device book as an account of its own. An account
     * holding none is left out, having nothing to tick.
     */
    List<FilterPage.Account> filterRoster(PimDomain domain) {
        List<String> emails = new ArrayList<>();
        for (AccountEntry account : accounts) {
            if (account.covers(domain) && AccountActivation.enabled(this, account.email)) {
                emails.add(account.email);
            }
        }
        List<PimdirCollections.Stored> collections =
                domain == PimDomain.MAIL
                        ? mail.loadMailboxes(emails)
                        : collectionsOf(domain);

        List<FilterPage.Account> roster = new ArrayList<>();
        for (String email : emails) {
            List<PimdirCollections.Stored> own = new ArrayList<>();
            for (PimdirCollections.Stored collection : collections) {
                if (collection.accountEmail.equals(email)) {
                    own.add(collection);
                }
            }
            if (!own.isEmpty()) {
                String label = LocalBook.is(email) ? getString(R.string.local_book) : email;
                roster.add(new FilterPage.Account(email, label, own));
            }
        }
        return roster;
    }

    /**
     * Drops what the filters, the defaults and the account switch held of an
     * account being deleted, before its collections go.
     */
    void forgetViews(String email) {
        for (PimDomain domain : PimDomain.values()) {
            List<PimdirCollections.Stored> collections =
                    domain == PimDomain.MAIL
                            ? mail.loadMailboxes(List.of(email))
                            : collectionsOf(domain);
            List<String> ids = new ArrayList<>();
            for (PimdirCollections.Stored collection : collections) {
                if (collection.accountEmail.equals(email)) {
                    ids.add(collection.id);
                }
            }
            filterOf(domain).forget(email, ids);
        }
        DefaultCollection.forget(this, email);
        AccountActivation.set(this, email, true);
    }

    /**
     * The default collection of every account a domain's filter shows: what
     * the Default chip narrows a list to ({@link DefaultCollection}).
     */
    java.util.Set<String> shownDefaults(PimDomain domain) {
        String kind = domain == PimDomain.CALENDAR ? PimdirSummary.CALENDAR : PimdirSummary.CONTACT;
        List<PimdirCollections.Stored> collections = collectionsOf(domain);
        java.util.Set<String> emails = new java.util.LinkedHashSet<>();
        for (PimdirCollections.Stored collection : collections) {
            emails.add(collection.accountEmail);
        }
        java.util.Set<String> defaults = new java.util.HashSet<>();
        for (String email : emails) {
            PimdirCollections.Stored found = DefaultCollection.of(this, email, kind, collections);
            if (found != null) {
                defaults.add(found.id);
            }
        }
        return defaults;
    }

    /** Navigates forward: the panels slide in from the right. */
    void show(int panel) {
        show(panel, false);
    }

    /** Navigates back: the panels slide in from the left. */
    void showBack(int panel) {
        show(panel, true);
    }

    private void show(int panel, boolean back) {
        show(
                panel,
                back ? R.anim.slide_in_left : R.anim.slide_in_right,
                back ? R.anim.slide_out_right : R.anim.slide_out_left);
    }

    private void show(int panel, int inAnim, int outAnim) {
        flipper.setInAnimation(this, inAnim);
        flipper.setOutAnimation(this, outAnim);
        flip(panel);
    }

    /** Swaps the screen in place, for a lateral move with no direction. */
    private void showInstant(int panel) {
        flipper.setInAnimation(null);
        flipper.setOutAnimation(null);
        flip(panel);
    }

    private void flip(int panel) {
        hideKeyboard();
        flipper.setDisplayedChild(panel);
        screen = panel;
        applyChrome(panel);
    }

    /**
     * Raises a whole-frame overlay (its own bar included) over the
     * current screen with a fade and a slight zoom. What is underneath
     * stays put.
     */
    void openOverlay(int panel) {
        hideKeyboard();
        View overlay = overlayOf(panel);
        overlay.setVisibility(View.VISIBLE);
        overlay.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.fade_zoom_in));
        screen = panel;
        applyChrome(panel);
    }

    /** Fades the overlay back out (zooming slightly away), then hides it. */
    void closeOverlay(int panel) {
        hideKeyboard();
        View overlay = overlayOf(panel);
        android.view.animation.Animation exit =
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.fade_zoom_out);
        exit.setAnimationListener(
                new android.view.animation.Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(android.view.animation.Animation animation) {}

                    @Override
                    public void onAnimationRepeat(android.view.animation.Animation animation) {}

                    @Override
                    public void onAnimationEnd(android.view.animation.Animation animation) {
                        // NOTE: invisible not gone, so the view stays
                        // measured; the animation is cleared because an
                        // animated invisible view still catches touches.
                        overlay.clearAnimation();
                        overlay.setVisibility(View.INVISIBLE);
                    }
                });
        overlay.startAnimation(exit);
    }

    /** The whole-frame overlay view behind an overlay panel id. */
    private View overlayOf(int panel) {
        switch (panel) {
            case PANEL_ACCOUNT:
                return findViewById(R.id.overlay_account);
            case PANEL_FILTER:
                return findViewById(R.id.overlay_filter);
            case PANEL_DELETED:
                return findViewById(R.id.overlay_deleted);
            default:
                return findViewById(R.id.overlay_auth);
        }
    }

    /**
     * One screen's share of the persistent chrome: whether it carries
     * its own bar (the overlays do), how the shared bar and FAB
     * configure, what the FAB does, and what the two backs do. One
     * table entry per screen ({@link #setUpScreens}) replaces the four
     * parallel switches that each used to carry every screen.
     */
    private static final class Screen {
        /** True when the screen brings its own bar (the overlays). */
        boolean ownBar;

        /** Configures the shared chrome, after the common reset. */
        Runnable chrome = () -> {};

        /** The shared FAB's action. */
        Runnable fab = () -> {};

        /** The main bar's back arrow (shown by the screen's chrome). */
        Runnable barBack = () -> {};

        /** The system back; null falls through to the framework. */
        Runnable systemBack;
    }

    /** The screens by panel id (flipper children 0-3, overlays 5-6,
     *  matching the include order in activity_main.xml). */
    private final Map<Integer, Screen> screens = new HashMap<>();

    /** Raises the shared FAB as a list screen's add button. */
    private void addFab(int icon, int description) {
        android.widget.ImageButton fab = findViewById(R.id.fab);
        fab.setImageResource(icon);
        fab.setContentDescription(getString(description));
        fab.setVisibility(View.VISIBLE);
    }

    /**
     * An editor that is still a frame: a titled screen with a back arrow
     * and no FAB, leaving onto the list that opened it. It is a screen
     * rather than nothing so the navigation around it can be built and
     * used before what goes inside it exists.
     */
    private Screen frame(int title, int list) {
        Screen frame = new Screen();
        frame.chrome =
                () -> {
                    ((TextView) findViewById(R.id.bar_title)).setText(title);
                    findViewById(R.id.bar_back).setVisibility(View.VISIBLE);
                    findViewById(R.id.fab).setVisibility(View.GONE);
                };
        frame.barBack = () -> showBack(list);
        frame.systemBack = () -> showBack(list);
        return frame;
    }

    /**
     * A reader: one item shown whole, with a back arrow onto the list it
     * was opened from and a bar titled by the item itself.
     *
     * <p>The title is a supplier rather than a string, because what is
     * open changes with every row and the chrome is applied after the
     * reader has been handed its item.
     */
    private Screen reader(Supplier<String> title, int list) {
        Screen reader = new Screen();
        reader.chrome =
                () -> {
                    ((TextView) findViewById(R.id.bar_title)).setText(title.get());
                    findViewById(R.id.bar_back).setVisibility(View.VISIBLE);
                    findViewById(R.id.fab).setVisibility(View.GONE);
                };
        reader.barBack = () -> showBack(list);
        reader.systemBack = () -> showBack(list);
        return reader;
    }

    /** Fills the screen table; every entry reads like one screen's card. */
    private void setUpScreens() {
        Screen contacts = new Screen();
        contacts.chrome =
                () -> {
                    listFab(R.string.contacts_new_short, R.drawable.ic_person_add);
                    showDomainBar(PANEL_CONTACTS);
                    // NOTE: after the domain bar, which a running
                    // selection then takes over.
                    contactsList.updateSelectionUi();
                };
        contacts.fab = this::addContact;
        contacts.systemBack =
                () -> {
                    if (contactsList.isSearchOpen()) {
                        contactsList.closeSearch();
                    } else {
                        MainActivity.super.onBackPressed();
                    }
                };
        screens.put(PANEL_CONTACTS, contacts);

        // Mail and calendar share the contacts list's chrome shape: the
        // burger, the bottom navigation and an extended add button
        // opening the domain's editor.
        Screen mailScreen = new Screen();
        mailScreen.chrome =
                () -> {
                    listFab(R.string.compose_new, R.drawable.ic_pencil);
                    showDomainBar(PANEL_MAIL);
                    // NOTE: reload re-applies a running selection over
                    // the domain bar.
                    mailList.reload();
                };
        mailScreen.fab = () -> compose.open();
        screens.put(PANEL_MAIL, mailScreen);

        Screen calendar = new Screen();
        calendar.chrome =
                () -> {
                    listFab(R.string.event_new, R.drawable.ic_add);
                    showDomainBar(PANEL_CALENDAR);
                    // The window the agenda covers starts at today, so it
                    // is rebuilt on arrival rather than cached across days.
                    calendarList.reload();
                };
        calendar.fab = this::composeEvent;
        screens.put(PANEL_CALENDAR, calendar);

        // The composer is a frame with a send button in the bar and a
        // back arrow that asks before losing what was typed.
        Screen composer = new Screen();
        composer.chrome =
                () -> {
                    ((TextView) findViewById(R.id.bar_title)).setText(R.string.compose_new);
                    findViewById(R.id.bar_back).setVisibility(View.VISIBLE);
                    findViewById(R.id.bar_send).setVisibility(View.VISIBLE);
                    setSending(false);
                };
        composer.barBack = () -> compose.close();
        composer.systemBack = () -> compose.close();
        screens.put(PANEL_COMPOSE, composer);

        // The message reader: a back arrow and the message's actions,
        // untitled since the page leads with the subject itself.
        Screen message = reader(() -> "", PANEL_MAIL);
        Runnable readerChrome = message.chrome;
        message.chrome =
                () -> {
                    readerChrome.run();
                    findViewById(R.id.message_actions).setVisibility(View.VISIBLE);
                };
        screens.put(PANEL_MESSAGE, message);

        // A calendar entry's page is its form, the way a contact's is:
        // the same rows read and edited, the add-field button beside the
        // title, and the FAB to save.
        Screen entry = new Screen();
        entry.chrome =
                () -> {
                    ((TextView) findViewById(R.id.bar_title)).setText(eventView.title());
                    findViewById(R.id.bar_back).setVisibility(View.VISIBLE);
                    if (eventView.readable()) {
                        findViewById(R.id.contact_add_field).setVisibility(View.VISIBLE);
                        findViewById(R.id.bar_delete).setVisibility(View.VISIBLE);
                        addFab(R.drawable.ic_save, R.string.event_save);
                    } else {
                        findViewById(R.id.fab).setVisibility(View.GONE);
                    }
                };
        entry.fab = () -> eventView.save();
        entry.barBack = () -> showBack(PANEL_CALENDAR);
        entry.systemBack = () -> showBack(PANEL_CALENDAR);
        screens.put(PANEL_EVENT_VIEW, entry);

        Screen contact = new Screen();
        contact.chrome =
                () -> {
                    ((TextView) findViewById(R.id.bar_title)).setText(edit.title);
                    findViewById(R.id.bar_back).setVisibility(View.VISIBLE);
                    // Placing needs a real account to target, so with only
                    // the local book or while resolving a conflict it hides.
                    findViewById(R.id.contact_books)
                            .setVisibility(
                                    edit.card != null
                                                    && hasRealAccount()
                                                    && !edit.resolvingConflict
                                            ? View.VISIBLE
                                            : View.GONE);
                    findViewById(R.id.contact_add_field).setVisibility(View.VISIBLE);
                    // The FAB validates (check); updateSaveEnabled turns it
                    // into the error disc while diverging rows await review.
                    android.widget.ImageButton fab = findViewById(R.id.fab);
                    fab.setImageResource(R.drawable.ic_check);
                    fab.setContentDescription(getString(R.string.contact_save));
                    fab.setVisibility(View.VISIBLE);
                    updateSaveEnabled();
                };
        contact.fab = this::saveContact;
        contact.barBack = this::closeContact;
        contact.systemBack = this::closeContact;
        screens.put(PANEL_CONTACT, contact);

        Screen advanced = new Screen();
        advanced.chrome =
                () -> {
                    ((TextView) findViewById(R.id.bar_title)).setText(R.string.advanced_title);
                    findViewById(R.id.bar_back).setVisibility(View.VISIBLE);
                    findViewById(R.id.fab).setVisibility(View.GONE);
                };
        advanced.barBack = advancedEditor::close;
        advanced.systemBack = advancedEditor::close;
        screens.put(PANEL_ADVANCED, advanced);

        Screen source = new Screen();
        source.chrome =
                () -> {
                    ((TextView) findViewById(R.id.bar_title)).setText(R.string.advanced_source);
                    findViewById(R.id.bar_back).setVisibility(View.VISIBLE);
                    android.widget.ImageButton fab = findViewById(R.id.fab);
                    fab.setImageResource(R.drawable.ic_check);
                    fab.setContentDescription(getString(R.string.contact_save));
                    fab.setVisibility(View.VISIBLE);
                };
        source.fab = advancedEditor::applySource;
        source.barBack = () -> showBack(PANEL_ADVANCED);
        source.systemBack = () -> showBack(PANEL_ADVANCED);
        screens.put(PANEL_SOURCE, source);

        Screen auth = new Screen();
        auth.ownBar = true;
        auth.chrome =
                () -> {
                    // NOTE: the shared FAB is the books step's alone; the
                    // other steps carry their own full-width buttons.
                    int step = authFlipper.getDisplayedChild();
                    if (step == STEP_BOOKS || step == STEP_CONFIG) {
                        android.widget.ImageButton fab = findViewById(R.id.fab);
                        fab.setImageResource(R.drawable.ic_arrow_forward);
                        fab.setContentDescription(getString(R.string.email_submit));
                        fab.setVisibility(View.VISIBLE);
                        setFabEnabled(R.id.fab, onboarding.stepReady(step));
                    } else {
                        onboarding.refreshStep(step);
                    }
                    applyAuthChrome();
                };
        auth.fab = () -> onboarding.continueStep(authFlipper.getDisplayedChild());
        auth.systemBack = this::authBack;
        screens.put(PANEL_AUTH, auth);

        Screen account = new Screen();
        account.ownBar = true;
        // NOTE: the settings overlay draws above the drawer where the
        // shared FAB cannot follow, so it carries its own save FAB.
        account.chrome = () -> findViewById(R.id.fab).setVisibility(View.GONE);
        account.fab = accountSettings::save;
        screens.put(PANEL_ACCOUNT, account);

        Screen filterScreen = new Screen();
        filterScreen.ownBar = true;
        filterScreen.chrome = () -> findViewById(R.id.fab).setVisibility(View.GONE);
        screens.put(PANEL_FILTER, filterScreen);

        Screen deleted = new Screen();
        deleted.ownBar = true;
        deleted.chrome = () -> findViewById(R.id.fab).setVisibility(View.GONE);
        screens.put(PANEL_DELETED, deleted);
    }

    /**
     * Configures the persistent app bar and FAB for the screen: every
     * chrome element goes off, then the screen's own set comes back
     * (the contacts screen delegates to its selection/search state).
     */
    void applyChrome(int panel) {
        // NOTE: posted, so the tab is drawn before its first sync's dialog
        // goes up over it.
        main.post(() -> firstSyncIfOwed(panel));

        android.widget.ImageButton fab = findViewById(R.id.fab);

        // Every screen change resets the shared FAB to its plain enabled
        // disc, clearing any lingering loader, dim or conflict error tone;
        // the screen's own chrome re-applies its state after.
        fab.setImageAlpha(255);
        fab.setBackgroundTintList(null);
        fab.setVisibility(View.GONE);
        findViewById(R.id.fab_extended).setVisibility(View.GONE);
        fab.setImageTintList(android.content.res.ColorStateList.valueOf(accentContrast()));
        findViewById(R.id.fab_progress).setVisibility(View.GONE);
        setFabEnabled(R.id.fab, true);

        Screen entry = screens.get(panel);
        if (entry == null) {
            return;
        }

        // The overlays carry their own bars, so only the FAB is shared.
        if (!entry.ownBar) {
            for (int id :
                    new int[] {
                        R.id.bar_back,
                        R.id.bar_menu,
                        R.id.bar_filter,
                        R.id.contacts_birthdays,
                        R.id.contacts_duplicates,
                        R.id.contacts_transfer,
                        R.id.selection_close,
                        R.id.contacts_merge,
                        R.id.contacts_delete,
                        R.id.selection_all_slot,
                        R.id.mail_select_seen,
                        R.id.mail_select_flag,
                        R.id.mail_select_delete,
                        R.id.contact_advanced,
                        R.id.contact_books,
                        R.id.contact_add_field,
                        R.id.bar_delete,
                        R.id.message_actions,
                        R.id.bar_send,
                        R.id.bottom_nav,
                    }) {
                findViewById(id).setVisibility(View.GONE);
            }
            TextView title = findViewById(R.id.bar_title);
            title.setVisibility(View.VISIBLE);
            title.animate().cancel();
            title.setAlpha(1f);
        }

        entry.chrome.run();
    }

    /** The shared FAB's action, per screen. */
    private void onFabClick() {
        Screen entry = screens.get(screen);
        if (entry != null) {
            entry.fab.run();
        }
    }

    /** The main bar's back arrow, per screen (overlays have their own). */
    private void onBarBack() {
        Screen entry = screens.get(screen);
        if (entry != null) {
            entry.barBack.run();
        }
    }

    /** Resolves a theme colour attribute to an ARGB int. */
    int resolveColor(int attr) {
        return ui.resolveColor(attr);
    }

    /** Dismisses the soft keyboard, e.g. when leaving the edit form. */
    void hideKeyboard() {
        // NOTE: fall back to the flipper's window token when nothing
        // holds the focus (the same window either way).
        View focus = getCurrentFocus();
        View anchor = focus != null ? focus : flipper;
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(anchor.getWindowToken(), 0);
        if (focus != null) {
            focus.clearFocus();
        }
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception error) {
            return url;
        }
    }

    String message(Exception error, int fallback) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? getString(fallback) : message;
    }

    void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /**
     * Posts a UI continuation that only runs while the activity is
     * alive: the io executor's sync outcomes land here, and one
     * arriving after a rotation or a finish would raise dialogs
     * against a dead window (BadTokenException).
     */
    void postAlive(Runnable action) {
        main.post(
                () -> {
                    if (!isDestroyed()) {
                        action.run();
                    }
                });
    }

    /**
     * Shows an error in a dismissible dialog rather than a toast: the
     * full (often long, e.g. a server body) message stays on screen and
     * scrolls until acknowledged.
     */
    void showError(Exception error, int fallback) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.error_title)
                .setMessage(message(error, fallback))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    int dp(int value) {
        return ui.dp(value);
    }

    /** A dimen resource in pixels (the shared item_* metrics). */
    private int dimen(int resId) {
        return getResources().getDimensionPixelSize(resId);
    }

    /**
     * Draws under the system bars (edge-to-edge is enforced for apps
     * targeting API 35) and pushes the chrome back in with the bar
     * insets: the top inset pads the surface app bars, so the status
     * bar area takes their colour as one bar; the bottom inset lifts the
     * FAB and adds to each list's FAB clearance, so nothing hides under
     * the navigation bar or the keyboard; the side insets pad the window
     * for landscape bars and cutouts. Older devices keep the platform's
     * opaque bars and automatic content inset, so this only runs on API 35
     * and up.
     */
    private void applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return;
        }

        View root = findViewById(android.R.id.content);
        root.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    Insets bars =
                            insets.getInsets(
                                    WindowInsets.Type.systemBars()
                                            | WindowInsets.Type.displayCutout());
                    // NOTE: edge-to-edge stops adjustResize, so the
                    // keyboard inset is folded into the bottom by hand;
                    // it overlaps the navigation bar, hence max not sum.
                    Insets ime = insets.getInsets(WindowInsets.Type.ime());
                    int bottom = Math.max(bars.bottom, ime.bottom);
                    applyBarInsets(root, bars, bottom);
                    return insets;
                });
        root.requestApplyInsets();
    }

    /** Places the system-bar and keyboard insets on the chrome. */
    private void applyBarInsets(View root, Insets bars, int bottom) {
        root.setPadding(bars.left, root.getPaddingTop(), bars.right, root.getPaddingBottom());

        // NOTE: all bars carry the top inset (the drawer header too,
        // since the drawer runs under the status bar).
        // NOTE: a bar's minimum height counts its padding, so each grows
        // by the inset it takes, keeping its content the full bar height
        // under the status bar.
        for (int bar :
                new int[] {
                    R.id.app_bar,
                    R.id.drawer_header,
                    R.id.auth_bar,
                    R.id.account_bar,
                    R.id.filter_bar,
                    R.id.deleted_bar,
                }) {
            padTop(bar, bars.top);
            findViewById(bar).setMinimumHeight(dimen(R.dimen.app_bar_height) + bars.top);
        }

        // NOTE: the base is each view's designed FAB clearance, so
        // re-applying stays idempotent as the listener fires again; the
        // bottom folds in the keyboard so the FAB rides above it.
        padBottom(R.id.fab_frame, 0, bottom);
        padBottom(R.id.account_fab_frame, 0, bottom);
        // The bottom navigation takes the system bar's inset, never the
        // keyboard's, and the extended FAB rides above it.
        padBottom(R.id.bottom_nav, 0, bars.bottom);
        android.view.ViewGroup.MarginLayoutParams extended =
                (android.view.ViewGroup.MarginLayoutParams)
                        findViewById(R.id.fab_extended).getLayoutParams();
        extended.bottomMargin = dimen(R.dimen.fab_above_nav) + bars.bottom;
        findViewById(R.id.fab_extended).setLayoutParams(extended);
        // The drawer's fixed bottom band takes the inset; the list above
        // it needs none.
        padBottom(R.id.drawer_actions, 8, bottom);
        padBottom(R.id.config_container, 88, bottom);
        padBottom(R.id.books_container, 88, bottom);
        padBottom(R.id.advanced_container, 24, bottom);
        padBottom(R.id.filter_content, 24, bottom);
        padBottom(R.id.deleted_list, 24, bottom);
        padBottom(R.id.source_input, 16, bottom);
        padBottom(R.id.email_actions, 16, bottom);
        padBottom(R.id.domain_actions, 16, bottom);
        padBottom(R.id.result_actions, 16, bottom);
        padBottom(R.id.message_view_replies, 12, bottom);
    }

    private void padTop(int id, int top) {
        View v = findViewById(id);
        v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
    }

    private void padBottom(int id, int baseDp, int inset) {
        View v = findViewById(id);
        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), dp(baseDp) + inset);
    }
}
