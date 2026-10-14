package org.pimalaya;

import android.app.AlertDialog;
import android.app.job.JobScheduler;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
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
import android.widget.ViewFlipper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.pimalaya.account.AccountActivation;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.LocalBook;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.pimalaya.calendar.CalendarList;
import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.calendar.EventView;
import org.pimalaya.client.Clients;
import org.pimalaya.contact.CardStore;
import org.pimalaya.contact.ContactEditor;
import org.pimalaya.contact.ContactForm;
import org.pimalaya.contact.ContactParts;
import org.pimalaya.contact.ContactPool;
import org.pimalaya.contact.ContactsList;
import org.pimalaya.contact.Group;
import org.pimalaya.contact.PimdirContacts;
import org.pimalaya.contact.VcfTransfer;
import org.pimalaya.file.FileActions;
import org.pimalaya.file.FileStore;
import org.pimalaya.file.FilesList;
import org.pimalaya.file.OpenedCopies;
import org.pimalaya.filter.FilterPage;
import org.pimalaya.filter.Filters;
import org.pimalaya.link.LinkShell;
import org.pimalaya.link.LinkedSection;
import org.pimalaya.mail.MailList;
import org.pimalaya.mail.MailParts;
import org.pimalaya.mail.MailStore;
import org.pimalaya.mail.MailWindow;
import org.pimalaya.mail.MessageCompose;
import org.pimalaya.mail.MessageView;
import org.pimalaya.phone.Accounts;
import org.pimalaya.phone.PhoneMirror;
import org.pimalaya.pimdir.ItemLinks;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.setup.AccountSettings;
import org.pimalaya.setup.OauthFlow;
import org.pimalaya.setup.OnboardingFlow;
import org.pimalaya.setup.SetupShell;
import org.pimalaya.sync.BackgroundCheck;
import org.pimalaya.sync.FirstSync;
import org.pimalaya.sync.SyncCoordinator;
import org.pimalaya.sync.SyncPasses;
import org.pimalaya.sync.SyncStamps;
import org.pimalaya.trash.DeletedItemsPage;
import org.pimalaya.ui.Avatar;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.ListHeader;
import org.pimalaya.ui.Panels;
import org.pimalaya.ui.SyncDock;
import org.pimalaya.ui.SyncPill;

/**
 * The single activity, building every screen and walking them in a flipper.
 *
 * <p>A fresh install opens the connection flow; every launch after lands on
 * the merged mail list, the bottom navigation switching tabs. Accounts live
 * behind the drawer; syncs run from there or from a list's pull-down.
 */
public class MainActivity extends Host
        implements SyncCoordinator.Screens, SetupShell, LinkShell {
    private static final int PANEL_AUTH = 20;

    /**
     * The domain the app opens on, and flows without one of their own land on.
     *
     * <p>Mail, the one people check rather than consult: an agenda or an
     * address book is looked up, an inbox is why the app was opened.
     */
    private static final int PANEL_ROOT = Panels.MAIL;

    /** Whether an auth step's main button is loading, the step frozen. */
    private boolean authBusy;

    private static final int REQUEST_MIRRORS = 1;
    private static final int REQUEST_NOTIFICATIONS = 4;

    /** The shared backend clients. */
    private final Clients clients = Clients.real();

    SecureStore store;
    CardStore base;

    /** The pimdir store every domain and every account shares. */
    PimdirDb pimdir;

    /** The contacts inside it. */
    PimdirContacts contacts;

    /** The app's foreground sync, one pass at a time. */
    SyncCoordinator sync;

    /** Every remote and phone pass, behind the sync dock. */
    SyncPasses passes;
    private ViewFlipper flipper;
    private ViewFlipper authFlipper;
    ContactForm form;

    /** Every connected account (multi-account). */
    final List<AccountEntry> accounts = new ArrayList<>();

    /** The background run this activity last reloaded after. */
    private long reloadedRun;

    /** The filter page over a list. */
    private FilterPage filterPage;

    /** The deleted items page, raised from the drawer (built in onCreate). */
    private DeletedItemsPage deletedPage;

    /** The calendar side of the store, and the agenda over it. */
    EventStore events;

    CalendarList calendarList;

    /** The reader one agenda row opens onto. */
    EventView eventView;

    /** The mail side of the store, and the merged list over it. */
    MailStore mail;

    MailList mailList;

    /** The file side of the store: attachments and their folders. */
    FileStore files;

    /** The Files list, and what a file offers wherever it shows. */
    FilesList filesList;

    FileActions fileActions;

    /** References between items, and the Linked card every item page shows. */
    ItemLinks links;

    LinkedSection linked;

    /** The reader one message row opens onto. */
    MessageView messageView;

    /** The composer the mail add button opens. */
    MessageCompose compose;

    /** The connection wizard and its OAuth grants (see onCreate wiring). */
    private OnboardingFlow onboarding;

    private OauthFlow oauth;

    /** The full-screen account settings over the drawer (built in onCreate). */
    private AccountSettings accountSettings;

    /** The accounts drawer, opened by the contacts burger. */
    private androidx.drawerlayout.widget.DrawerLayout drawer;

    /** The sync dock over the lists' bottom navigation (built in onCreate). */
    private SyncDock dock;

    /** The pool's loader and bridge grouping (built in onCreate). */
    ContactPool pool;

    /** The contacts screen: list, adapter, search and selection. */
    private ContactsList contactsList;

    /** The phone mirrors a prompt is up for, and who takes the answer. */
    private Set<PhoneMirror> askedMirrors;

    private Consumer<Set<PhoneMirror>> afterMirrors;

    /** Who takes the answer of a notifications prompt that is up. */
    private Consumer<Boolean> afterNotifications;

    /** The contact editor over the form (built in onCreate). */
    private ContactEditor editor;

    {
        screen = PANEL_ROOT;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppState state = App.of(this).state;
        reloadedRun = state.backgroundRan;
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        drawer = findViewById(R.id.drawer);
        applyEdgeToEdge();

        store = new SecureStore(this);
        pimdir = App.of(this).pimdir();
        base = App.of(this).cards();
        contacts = new PimdirContacts(pimdir);
        events = new EventStore(this, pimdir);
        mail = new MailStore(this, pimdir);
        files = new FileStore(this, pimdir);
        links = new ItemLinks(this, pimdir);
        // NOTE: the coordinator before the screens, which take it; it
        // touches none of them while built (the dock is read later).
        SyncPasses.Parts syncParts =
                new SyncPasses.Parts(pimdir, base, store, mail, events, clients);
        sync = new SyncCoordinator(this, syncParts, this);
        passes = sync.passes();
        pool = new ContactPool(base, contacts, accounts);
        ContactParts contactParts =
                new ContactParts(pimdir, base, contacts, pool, clients, accounts);
        form = new ContactForm(this);
        editor = new ContactEditor(this, contactParts, form, this::reloadContacts);
        contactsList = new ContactsList(this, contactParts, editor, this::syncContacts);
        // NOTE: a save into a folder reads the Files list again; the list
        // is built after the reader, so the callback reads the field late.
        fileActions = new FileActions(this, files, () -> filesList.reload());
        linked = new LinkedSection(this, links, mail, this);
        // NOTE: the entry page shows the Linked card, so it is built after
        // it; the agenda binds itself into the page it opens rows onto.
        CalendarParts calendarParts = new CalendarParts(pimdir, clients, events);
        eventView = new EventView(this, calendarParts, linked);
        calendarList = new CalendarList(this, calendarParts, eventView, this::syncCalendars);
        // NOTE: the reader shows the Linked card and offers the file
        // actions, so it is built after both; the list and the composer
        // bind themselves into it.
        MailParts mailParts = new MailParts(pimdir, clients, mail, files, links, store);
        messageView = new MessageView(this, mailParts, sync, fileActions, linked);
        mailList = new MailList(this, mail, sync, messageView);
        compose = new MessageCompose(this, mailParts, messageView, mailList);
        // NOTE: the Files list opens a file's message in the reader and
        // shows the Linked card, so it is built after both.
        filesList = new FilesList(this, files, fileActions, messageView, linked);
        // NOTE: Apply reloads the four lists, so the page is built after them.
        filterPage =
                new FilterPage(
                        this,
                        new Filters(this, passes, sync, mail, files, accounts),
                        this::filterChanged);
        // NOTE: the settings reload the lists and count on the mail list's
        // read executor, so the page is built after them.
        accountSettings =
                new AccountSettings(
                        this, syncParts, contacts, accounts, sync, mailList.reads(), this);
        // NOTE: a restore reloads the lists, so the page is built after them.
        deletedPage = new DeletedItemsPage(this, syncParts, passes, accounts, this::reload);
        // NOTE: the two flows reference each other (grants land back in
        // the wizard), so one side binds late.
        oauth = new OauthFlow(this, clients.account(), this);
        onboarding = new OnboardingFlow(this, syncParts, accounts, sync, this, oauth);
        oauth.onConnected = onboarding::connect;
        oauth.onAborted = onboarding::grantAborted;
        oauth.onBusy = onboarding::busy;
        flipper = findViewById(R.id.flipper);
        authFlipper = findViewById(R.id.auth_flipper);
        form.setOnRender(this::updateSaveEnabled);
        // NOTE: a card being composed or a conflict being resolved has no
        // links to show: the one is no item yet, the other not settled.
        form.setTrailing(
                sections ->
                        linked.section(
                                sections,
                                editor.edit() == null
                                                || editor.edit().card == null
                                                || editor.edit().resolvingConflict
                                        ? null
                                        : ItemLinks.ofContact(editor.edit().card.id()),
                                form::refresh));

        setUpScreens();
        setUpEmailPanel();
        contactsList.setUp();
        calendarList.setUp();
        filesList.setUp();
        if (savedInstanceState != null) {
            fileActions.restore(savedInstanceState);
            filesList.restore(savedInstanceState);
        } else {
            // NOTE: the copies handed to other apps are read while they show
            // them; a fresh start is when none is shown any more.
            io.execute(() -> OpenedCopies.clear(this));
        }
        io.execute(() -> CacheSweep.run(this, System.currentTimeMillis()));
        mailList.setUp();
        setUpContactPanel();
        setUpHomePanel();
        filterPage.setUp();
        deletedPage.setUp();

        findViewById(R.id.selection_close)
                .setOnClickListener(
                        view -> {
                            if (screen == Panels.MAIL) {
                                mailList.exitSelection();
                            } else {
                                contactsList.exitSelection();
                            }
                        });
        findViewById(R.id.selection_all)
                .setOnClickListener(
                        view -> {
                            if (screen == Panels.MAIL) {
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

        dock = new SyncDock(this);
        observeSync(sync::showSyncDock);

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
        accounts.addAll(storedAccounts());

        // NOTE: an account set up before windows existed takes one, before
        // the first list or body step reads it: once per account, a few
        // preference reads every launch after.
        List<String> owing = FirstSync.owing(this, PimDomain.MAIL);
        for (AccountEntry account : accountsFor(PimDomain.MAIL)) {
            if (!owing.contains(account.email)) {
                MailWindow.migrate(this, mail, account.email);
            }
        }

        // NOTE: an install upgraded from a build that scheduled its own
        // background sync still holds those jobs and their interval
        // choices, dropped here; the background check's job is kept.
        JobScheduler jobs = getSystemService(JobScheduler.class);
        for (android.app.job.JobInfo job : jobs.getAllPendingJobs()) {
            if (job.getId() != BackgroundCheck.JOB) {
                jobs.cancel(job.getId());
            }
        }
        deleteSharedPreferences("pimalaya.background");
        BackgroundCheck.schedule(this);
        state.onBackgroundEnd = () -> main.post(this::backgroundEnded);

        // NOTE: the phone's contacts follow the store by events: a pass after
        // each write (PhoneQueue), Android's upload sync, the app's return.
        App.of(this).startPhoneQueue();
        reconcilePhone();

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

        // NOTE: adb-only hook, debug builds only, so a sync can be driven
        // headlessly: am start ... --ez syncRemote true
        if (BuildConfig.DEBUG && getIntent().getBooleanExtra("syncRemote", false)) {
            syncRemote();
        }

        // NOTE: an OAuth redirect lands here rather than in onNewIntent
        // when the process died while the user was in the browser (the
        // redirect recreates the activity from scratch).
        oauth.handleRedirect(getIntent());

        // NOTE: an intent carrying data is an OAuth redirect being
        // processed, not a fresh start to open the connection flow on.
        if (savedInstanceState == null && getIntent().getData() == null && !hasRealAccount()) {
            startAuth();
        }
    }

    /** Enters the connection flow over the screen, closing the drawer. */
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
    @Override
    public void showAuth(int step) {
        showAuth(step, false);
    }

    /** Navigates the auth flow back: the step slides in from the left. */
    private void showAuthBack(int step) {
        showAuth(step, true);
    }

    /**
     * Shows an auth step under the persistent bar, sliding the step alone.
     *
     * <p>Entering from outside jumps without animation: the panel's fade is
     * the transition.
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

    /** The auth bar per step: hidden on a bare step, titled otherwise. */
    private void applyAuthChrome() {
        int step = authFlipper.getDisplayedChild();
        // NOTE: a first run opens on the address alone, with nothing to
        // leave for and the page's own headline in place of a title; the
        // steps after it carry a round back button of their own.
        boolean bare =
                step != OnboardingFlow.STEP_CONFIG
                        && (step != OnboardingFlow.STEP_EMAIL || !hasRealAccount());
        findViewById(R.id.auth_bar).setVisibility(bare ? View.GONE : View.VISIBLE);
        TextView title = findViewById(R.id.auth_title);
        if (step == OnboardingFlow.STEP_EMAIL) {
            title.setText(hasRealAccount() ? R.string.add_account : R.string.auth_step_email);
        } else if (step == OnboardingFlow.STEP_CONFIG) {
            title.setText(R.string.auth_step_config);
        }
    }

    /** The auth flow's displayed step. */
    @Override
    public int authStep() {
        return authFlipper.getDisplayedChild();
    }

    /** The auth bar's back arrow, per step. */
    private void authBack() {
        if (authBusy) {
            return;
        }
        // NOTE: stepping back abandons whatever grant the browser was left
        // holding, so its sealed state and verifier go with it.
        oauth.cancel();
        int step = authFlipper.getDisplayedChild();
        if (step == OnboardingFlow.STEP_RESULT || step == OnboardingFlow.STEP_SIGNIN) {
            onboarding.abortAuthSteps();
        } else if (step == OnboardingFlow.STEP_OAUTH || step == OnboardingFlow.STEP_BOOKS) {
            // NOTE: nothing persists before the selection confirms, so
            // the books step steps back like any other.
            onboarding.backToSignIn();
        } else if (step == OnboardingFlow.STEP_CONFIG) {
            showAuthBack(OnboardingFlow.STEP_DOMAIN);
        } else if (step == OnboardingFlow.STEP_DOMAIN) {
            showAuthBack(OnboardingFlow.STEP_EMAIL);
        } else {
            cancelAuth();
        }
    }

    /** Leaves the auth flow unfinished, onto the root, the drawer closed. */
    private void cancelAuth() {
        oauth.cancel();
        closeOverlay(PANEL_AUTH);
        screen = PANEL_ROOT;
        applyChrome(PANEL_ROOT);
    }

    /** Closes the auth sheet onto a domain screen other than the root. */
    void leaveOnboarding(int panel) {
        closeOverlay(PANEL_AUTH);
        screen = panel;
        applyChrome(panel);
        if (drawer.isDrawerOpen(android.view.Gravity.START)) {
            drawer.closeDrawer(android.view.Gravity.START);
        }
        reloadHome();
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

    /** Gates the contact save FAB until every diverging field is reviewed. */
    private void updateSaveEnabled() {
        if (screen == Panels.CONTACT) {
            gateSave(form.conflictResolved(), R.drawable.ic_check, R.string.contact_save);
        }
    }

    /** Gates a conflict form's save FAB: the save once ready, else an error. */
    @Override
    public void gateSave(boolean ready, int icon, int description) {
        android.widget.ImageButton fab = findViewById(R.id.fab);
        if (ready) {
            fab.setBackgroundTintList(null);
            fab.setImageResource(icon);
            fab.setImageTintList(android.content.res.ColorStateList.valueOf(accentContrast()));
            fab.setContentDescription(getString(description));
            setFabEnabled(R.id.fab, true);
            return;
        }

        // NOTE: the error tone at full strength, so rows awaiting review
        // read as a signal, not as a dimmed save.
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
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        fileActions.save(state);
        filesList.save(state);
    }

    @Override
    protected void onDestroy() {
        App.of(this).state.onBackgroundEnd = null;
        io.shutdownNow();
        interactive.shutdownNow();
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
        // NOTE: the account settings overlay sits above the open drawer, so
        // back peels it off first.
        if (screen == Panels.ACCOUNT) {
            accountSettings.leave();
            return;
        }
        if (screen == Panels.FILTER) {
            filterPage.leave();
            return;
        }
        if (screen == Panels.DELETED) {
            deletedPage.leave();
            return;
        }
        if (drawer.isDrawerOpen(android.view.Gravity.START)) {
            drawer.closeDrawer(android.view.Gravity.START);
            return;
        }
        if (screen == Panels.CONTACTS && contactsList.isSelectionMode()) {
            contactsList.exitSelection();
            return;
        }
        if (screen == Panels.MAIL && mailList.isSelectionMode()) {
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
     * Lands on one domain's list, search, selection and drawer settled.
     *
     * <p>The contacts are rebuilt whichever list shows: they back the merged
     * view.
     */
    private void goDomain(int panel) {
        contactsList.closeSearch();
        contactsList.exitSelection();

        reloadContacts();

        if (screen == PANEL_AUTH) {
            // NOTE: the list swapped in under the sheet first: the one left
            // there (say the agenda) is not the one the account lands on.
            flipper.setInAnimation(null);
            flipper.setOutAnimation(null);
            flipper.setDisplayedChild(panel);
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

    /** Refreshes the drawer and slides it over the list, from the burger. */
    void openAccountsDrawer() {
        reloadHome();
        drawer.openDrawer(android.view.Gravity.START);
    }

    private void setUpEmailPanel() {
        EditText email = findViewById(R.id.email_input);
        findViewById(R.id.auth_back).setOnClickListener(view -> authBack());
        for (int back : new int[] {R.id.domain_back, R.id.signin_back, R.id.oauth_back, R.id.books_back}) {
            findViewById(back).setOnClickListener(view -> authBack());
        }
        findViewById(R.id.result_back).setOnClickListener(view -> authBack());
        findViewById(R.id.auth_cancel).setOnClickListener(view -> cancelAuth());

        onboarding.bind();

        email.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        if (screen == PANEL_AUTH
                                && authFlipper.getDisplayedChild() == OnboardingFlow.STEP_EMAIL) {
                            setFabEnabled(R.id.email_continue, onboarding.emailSubmittable());
                        }
                    }
                });
    }

    /** Toggles an auth continue button's loader, disabled while loading. */
    @Override
    public void setAuthLoading(int buttonId, int progressId, boolean loading) {
        View button = findViewById(buttonId);
        if (button instanceof android.widget.ImageButton) {
            setFabEnabled(buttonId, !loading);
            ((android.widget.ImageButton) button).setImageAlpha(loading ? 0 : 255);
        } else {
            // NOTE: a pill dims as when disabled and keeps its label, the
            // loader turning at its end in the label's colour.
            button.setEnabled(!loading);
            button.setAlpha(loading ? 0.4f : 1f);
            // NOTE: and nothing else on the step answers until it is done,
            // the way back included.
            authBusy = loading;
            int[] controls;
            if (buttonId == R.id.email_continue) {
                controls = new int[] {R.id.email_input, R.id.email_advanced};
            } else if (buttonId == R.id.signin_continue) {
                controls = new int[] {R.id.signin_back, R.id.signin_container};
            } else if (buttonId == R.id.oauth_continue) {
                controls = new int[] {R.id.oauth_back, R.id.oauth_container};
            } else if (buttonId == R.id.books_continue) {
                controls = new int[] {R.id.books_back, R.id.books_container};
            } else {
                controls =
                        new int[] {
                            R.id.domain_back,
                            R.id.domain_advanced,
                            R.id.domain_password,
                            R.id.domain_password_toggle,
                            R.id.domain_container
                        };
            }
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
    @Override
    public void setFabEnabled(int id, boolean enabled) {
        View fab = findViewById(id);
        fab.setEnabled(enabled);
        fab.setAlpha(enabled ? 1f : 0.4f);
    }

    /** Tints a floating action button's icon to contrast the accent disc. */
    private void setUpFab(int id) {
        android.widget.ImageButton fab = findViewById(id);
        fab.setImageTintList(android.content.res.ColorStateList.valueOf(accentContrast()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // NOTE: back from the browser without a redirect (a cancelled
        // grant) must not leave Continue on its loader; a real redirect
        // re-enters loading right after.
        if (flipper != null
                && screen == PANEL_AUTH
                && authFlipper.getDisplayedChild() != OnboardingFlow.STEP_EMAIL) {
            onboarding.resetConfigContinue();
        }

        App.of(this).state.onScreen = true;
        afterBackgroundRun();
        sync.phoneReturn();
        if (App.of(this).state.backgroundRunning && !isSyncing()) {
            showBackgroundDock();
        }

        sync.resumed();
    }

    @Override
    protected void onPause() {
        sync.paused();
        App.of(this).state.onScreen = false;
        // NOTE: an account connected or removed while the app was open
        // changes whether the background check has anything to do.
        BackgroundCheck.schedule(this);
        super.onPause();
    }

    /**
     * Catches up with the background check on return.
     *
     * <p>A run may have renewed a token and written every list, and its
     * notifications are for an app that was not open.
     */
    private void afterBackgroundRun() {
        if (store == null) {
            return;
        }
        getSystemService(android.app.NotificationManager.class).cancelAll();
        long ran = App.of(this).state.backgroundRan;
        if (ran == reloadedRun || isSyncing()) {
            return;
        }
        reloadedRun = ran;
        accounts.clear();
        accounts.add(LocalBook.account());
        accounts.addAll(storedAccounts());
        reloadContacts();
        mailList.reload();
        calendarList.reload();
        reloadHome();
    }

    /**
     * Shows a background run syncing, on the dock the app's own passes use.
     *
     * <p>A pull meanwhile is turned down; this says why before it is tried.
     */
    private void showBackgroundDock() {
        mailList.redrawFooter();
        SyncDock.Pass pass = new SyncDock.Pass();
        pass.title = getString(R.string.sync_background);
        dock.pass(pass);
    }

    /**
     * A background run ended, on the main thread.
     *
     * <p>On screen, the dock goes, the lists catch up and a first sync the
     * run held back starts; off screen, the next return does it.
     */
    private void backgroundEnded() {
        if (!App.of(this).state.onScreen || isDestroyed()) {
            return;
        }
        mailList.redrawFooter();
        if (!isSyncing()) {
            dock.synced();
        }
        afterBackgroundRun();
        sync.firstSyncIfOwed(domainOf(screen));
    }

    private void setUpHomePanel() {
        // NOTE: sync slides the drawer shut on its way in, so the sync dock
        // shows over the list it refreshes.
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

        accountSettings.bind();

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
     * Rebuilds the drawer: one card per real account, opening its settings.
     *
     * <p>No mailboxes: the lists are one merged view the filter narrows, so a
     * drawer entry per collection would be a second view of the same thing.
     */
    @Override
    public void reloadHome() {
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

    /** One drawer account card: disc, address, domains and sync pill. */
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
                SyncPill.of(
                        this,
                        AccountActivation.enabled(this, email),
                        SyncStamps.at(this, email),
                        sync.bodiesLeft(email)),
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

    /** Opens an account's {@link AccountSettings} over the open drawer. */
    private void openAccountSettings(String email) {
        accountSettings.open(email);
    }

    /**
     * Asks the wanted phone mirrors' permissions in one prompt.
     *
     * <p>Hands {@code done} the granted ones on the main thread, at once when
     * all are. Called only as a mirror's switch turns on.
     */
    @Override
    public void askMirrors(Set<PhoneMirror> wanted, Consumer<Set<PhoneMirror>> done) {
        String[] missing = PhoneMirror.missing(wanted, this::permitted);
        if (missing.length == 0) {
            done.accept(PhoneMirror.granted(wanted, this::permitted));
            return;
        }
        askedMirrors = wanted;
        afterMirrors = done;
        requestPermissions(missing, REQUEST_MIRRORS);
    }

    /**
     * Asks the notifications permission where Android takes one.
     *
     * <p>Hands {@code done} whether the app may notify, on the main thread.
     * Called only as a notifications switch turns on.
     */
    @Override
    public void askNotifications(Consumer<Boolean> done) {
        String[] missing = BackgroundCheck.missing(Build.VERSION.SDK_INT, this::permitted);
        if (missing.length == 0) {
            done.accept(true);
            return;
        }
        afterNotifications = done;
        requestPermissions(missing, REQUEST_NOTIFICATIONS);
    }

    /**
     * Hands a prompt its answer.
     *
     * <p>A denial Android will not prompt for again leaves the switch off and
     * says where it can still be allowed.
     */
    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        boolean blocked =
                PermissionAnswer.blocked(
                        permissions, this::permitted, this::shouldShowRequestPermissionRationale);
        if (request == REQUEST_MIRRORS && afterMirrors != null) {
            Consumer<Set<PhoneMirror>> done = afterMirrors;
            Set<PhoneMirror> wanted = askedMirrors;
            afterMirrors = null;
            askedMirrors = null;
            done.accept(PhoneMirror.granted(wanted, this::permitted));
            if (blocked) {
                showBlocked(
                        new Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", getPackageName(), null)));
            }
        } else if (request == REQUEST_NOTIFICATIONS && afterNotifications != null) {
            Consumer<Boolean> done = afterNotifications;
            afterNotifications = null;
            done.accept(BackgroundCheck.permitted(this));
            if (blocked) {
                showBlocked(
                        new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(
                                        android.provider.Settings.EXTRA_APP_PACKAGE,
                                        getPackageName()));
            }
        }
    }

    /** Says only the settings can allow a permission now, offering them. */
    private void showBlocked(Intent settings) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.permission_blocked)
                .setPositiveButton(
                        R.string.permission_blocked_open,
                        (dialog, which) -> startActivity(settings))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean permitted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public PimDomain shown() {
        return domainOf(screen);
    }

    @Override
    public SyncDock dock() {
        return dock;
    }

    @Override
    public void syncing(boolean active) {
        mailList.redrawFooter();
        if (active) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void land(PimDomain domain) {
        switch (domain) {
            case MAIL:
                goDomain(Panels.MAIL);
                break;
            case CONTACTS:
                goDomain(Panels.CONTACTS);
                break;
            case CALENDAR:
                goDomain(Panels.CALENDAR);
                break;
        }
    }

    @Override
    public void reload(PimDomain domain) {
        switch (domain) {
            case CONTACTS:
                reloadContacts();
                break;
            case MAIL:
                mailList.reload();
                break;
            case CALENDAR:
                calendarList.reload();
                break;
        }
    }

    /** Keeps the account cache current after a renewal, on the main thread. */
    @Override
    public void renewed(AccountEntry updated) {
        main.post(
                () -> {
                    accounts.removeIf(entry -> entry.email.equals(updated.email));
                    accounts.add(updated);
                });
    }

    @Override
    public boolean backgroundRunning() {
        return App.of(this).state.backgroundRunning;
    }

    /** Whether a sync pass is running. */
    boolean isSyncing() {
        return sync.isSyncing();
    }

    private void observeSync(java.util.function.Consumer<Boolean> observer) {
        sync.observe(observer);
    }

    /** Syncs every address book ({@link SyncCoordinator#syncRemote}). */
    void syncRemote() {
        sync.syncRemote();
    }

    /** The agenda's pull ({@link SyncCoordinator#syncCalendars}). */
    void syncCalendars() {
        sync.syncCalendars();
    }

    /** The stored accounts connected for one domain. */
    List<AccountEntry> accountsFor(PimDomain domain) {
        return passes.accountsFor(domain);
    }

    /** The domain a list panel shows, null for any other screen. */
    private static PimDomain domainOf(int panel) {
        switch (panel) {
            case Panels.MAIL:
                return PimDomain.MAIL;
            case Panels.CONTACTS:
                return PimDomain.CONTACTS;
            case Panels.CALENDAR:
                return PimDomain.CALENDAR;
            default:
                return null;
        }
    }

    /** Redraws the drawer's account cards when it is open, for their pills. */
    @Override
    public void bodiesChanged() {
        if (drawer != null && drawer.isDrawerOpen(Gravity.START)) {
            reloadHome();
        }
    }

    /** The contacts list's pull ({@link SyncCoordinator#syncContacts}). */
    void syncContacts() {
        sync.syncContacts();
    }

    /** The drawer's sync of every domain ({@link SyncCoordinator#syncAll}). */
    void syncAll() {
        sync.syncAll();
    }

    /** Aligns the phone's accounts ({@link SyncCoordinator#reconcilePhone}). */
    void reconcilePhone() {
        sync.reconcilePhone();
    }

    @Override
    public long contactsIngested() {
        return App.of(this).state.contactsIngested;
    }

    @Override
    public long calendarsIngested() {
        return App.of(this).state.calendarsIngested;
    }

    /** Opens a message in the reader ({@link MessageView#open}). */
    @Override
    public void openMessage(MailStore.StoredMessage message) {
        messageView.open(message);
    }

    /** Opens the merged row holding this card key; false when none does. */
    @Override
    public boolean openContact(String linkId) {
        Group group = contactsList.groupOf(linkId);
        if (group == null) {
            return false;
        }
        editor.openGroup(group);
        return true;
    }

    /** Opens a calendar resource on its page ({@link EventView#openStored}). */
    @Override
    public void openEvent(String collection, String linkId, Runnable gone) {
        eventView.openStored(collection, linkId, gone);
    }

    /** Opens a file's sheet ({@link FilesList#openFile}). */
    @Override
    public void openFile(String collection, String linkId) {
        filesList.openFile(collection, linkId);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (request == VcfTransfer.REQUEST_IMPORT) {
            contactsList.transfer().importFile(data.getData());
        } else if (request == VcfTransfer.REQUEST_EXPORT) {
            contactsList.transfer().exportFile(data.getData());
        } else if (request == FilesList.REQUEST_IMPORT) {
            filesList.imported(data.getData());
        } else if (request == FileActions.REQUEST_EXPORT) {
            fileActions.exported(data.getData());
        }
    }

    private void setUpContactPanel() {
        findViewById(R.id.contact_books).setOnClickListener(view -> editor.manageBooks());
        // NOTE: the add-field button is the bar's, not the contact
        // editor's: the calendar entry page raises the same one, since
        // both are the same page with the same gesture. Which one it
        // adds to follows the screen showing it.
        findViewById(R.id.contact_add_field)
                .setOnClickListener(
                        view -> {
                            if (screen == Panels.EVENT_VIEW) {
                                eventView.addField();
                            } else {
                                form.addField();
                            }
                        });
        findViewById(R.id.bar_delete).setOnClickListener(view -> eventView.confirmDelete());
    }

    /** Rebuilds the contacts list from the base (staged edits included). */
    void reloadContacts() {
        contactsList.reload();
    }

    /**
     * The list chrome for one domain: burger, title, filter, dock and nav.
     *
     * <p>The title shows once the large title scrolls away; the domain's own
     * actions come after, from its screen.
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
        dock.onList(true);
    }

    /** Retitles the bar, for a page whose own edits change its title. */
    @Override
    public void updateBarTitle(String title) {
        ((TextView) findViewById(R.id.bar_title)).setText(title);
    }

    /** The bar's title for a list, shown once its large title scrolls away. */
    @Override
    public void showDomainTitle(int panel) {
        TextView title = findViewById(R.id.bar_title);
        title.setText(Domains.titleOf(panel));
        title.setVisibility(View.VISIBLE);
        title.animate().cancel();
        title.setAlpha(headerOf(panel).scrolled() ? 1f : 0f);
    }

    /** Fades the bar's title in or out as a large title leaves or returns. */
    @Override
    public void showBarTitle(boolean shown) {
        if (screen == Panels.CONTACTS && contactsList.isSelectionMode()
                || screen == Panels.MAIL && mailList.isSelectionMode()) {
            return;
        }
        findViewById(R.id.bar_title).animate().alpha(shown ? 1f : 0f).setDuration(150);
    }

    @Override
    public int titleOf(int panel) {
        return Domains.titleOf(panel);
    }

    /** The large title heading one list. */
    @Override
    public ListHeader headerOf(int panel) {
        if (panel == Panels.MAIL) {
            return mailList.header();
        }
        if (panel == Panels.FILES) {
            return filesList.header();
        }
        return panel == Panels.CALENDAR ? calendarList.header() : contactsList.header();
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
     * Folds the extended FAB to its glyph, or unfolds its label.
     *
     * <p>The width animates while the label fades: the button shrinks rather
     * than only losing its text.
     */
    @Override
    public void foldFab(boolean folded) {
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
    @Override
    public void showMailBadge(int unread) {
        TextView badge = findViewById(R.id.nav_mail_badge);
        badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
        badge.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
    }

    /** A list's add button, inert while the list imports or exports. */
    @Override
    public void setListBusy(boolean busy) {
        View add = findViewById(R.id.fab_extended);
        add.setEnabled(!busy);
        add.setAlpha(busy ? 0.4f : 1f);
    }

    /** The composer's send button, inert while the message is written out. */
    @Override
    public void setSending(boolean sending) {
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
                        (panel == Panels.FILES
                                        ? MergedFilter.files(this).isActive()
                                        : domain != null && MergedFilter.of(this, domain).isActive())
                                ? ui.resolveColor(android.R.attr.colorAccent)
                                : ui.resolveColor(android.R.attr.textColorPrimary)));
    }

    /** Switches domain from the bottom navigation: a lateral move, no slide. */
    private void switchDomain(int target) {
        if (target != screen) {
            showInstant(target);
        }
    }

    /** Opens the filter page of the domain on screen. */
    private void openFilter() {
        if (screen == Panels.FILES) {
            filterPage.openFiles();
            return;
        }
        filterPage.open(domainOf(screen) == null ? PimDomain.CONTACTS : domainOf(screen));
    }

    /** A filter changed: the lists and the bar's filter icon follow. */
    @Override
    public void filterChanged() {
        contactsList.reRender();
        calendarList.reload();
        mailList.reload();
        filesList.reload();
    }

    /** Navigates forward: the panels slide in from the right. */
    @Override
    public void show(int panel) {
        show(panel, false);
    }

    /** Navigates back: the panels slide in from the left. */
    @Override
    public void showBack(int panel) {
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

    /** Raises a whole-frame overlay, its own bar included, over the screen. */
    @Override
    public void openOverlay(int panel) {
        hideKeyboard();
        View overlay = overlayOf(panel);
        overlay.setVisibility(View.VISIBLE);
        overlay.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.fade_zoom_in));
        screen = panel;
        applyChrome(panel);
    }

    /** Fades the overlay back out (zooming slightly away), then hides it. */
    @Override
    public void closeOverlay(int panel) {
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
            case Panels.ACCOUNT:
                return findViewById(R.id.overlay_account);
            case Panels.FILTER:
                return findViewById(R.id.overlay_filter);
            case Panels.DELETED:
                return findViewById(R.id.overlay_deleted);
            default:
                return findViewById(R.id.overlay_auth);
        }
    }

    /**
     * One screen's share of the persistent chrome: bars, FAB and backs.
     *
     * <p>One table entry per screen ({@link #setUpScreens}).
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

    /** The screens by panel id, as activity_main.xml includes them. */
    private final Map<Integer, Screen> screens = new HashMap<>();

    /** Raises the shared FAB as a list screen's add button. */
    private void addFab(int icon, int description) {
        android.widget.ImageButton fab = findViewById(R.id.fab);
        fab.setImageResource(icon);
        fab.setContentDescription(getString(description));
        fab.setVisibility(View.VISIBLE);
    }

    /**
     * An editor that is still a frame: titled, a back arrow, no FAB.
     *
     * <p>A screen rather than nothing, so the navigation around it works
     * before what goes inside it exists.
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
     * A reader: one item whole, titled by it, a back arrow onto its list.
     *
     * <p>The title is a supplier: the chrome is applied after the reader is
     * handed its item.
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
                    showDomainBar(Panels.CONTACTS);
                    // NOTE: after the domain bar, which a running
                    // selection then takes over.
                    contactsList.updateSelectionUi();
                };
        contacts.fab = () -> editor.addContact(MergedFilter.of(this, PimDomain.CONTACTS));
        contacts.systemBack =
                () -> {
                    if (contactsList.isSearchOpen()) {
                        contactsList.closeSearch();
                    } else {
                        MainActivity.super.onBackPressed();
                    }
                };
        screens.put(Panels.CONTACTS, contacts);

        Screen mailScreen = new Screen();
        mailScreen.chrome =
                () -> {
                    listFab(R.string.compose_new, R.drawable.ic_pencil);
                    showDomainBar(Panels.MAIL);
                    // NOTE: reload re-applies a running selection over
                    // the domain bar.
                    mailList.reload();
                };
        mailScreen.fab = () -> compose.open();
        screens.put(Panels.MAIL, mailScreen);

        Screen calendar = new Screen();
        calendar.chrome =
                () -> {
                    listFab(R.string.event_new, R.drawable.ic_add);
                    showDomainBar(Panels.CALENDAR);
                    // NOTE: the agenda's window starts at today, so it is
                    // rebuilt on arrival rather than cached across days.
                    calendarList.reload();
                };
        calendar.fab = eventView::composeNew;
        screens.put(Panels.CALENDAR, calendar);

        Screen filesScreen = new Screen();
        filesScreen.chrome =
                () -> {
                    listFab(R.string.attachment_new_folder, R.drawable.ic_add);
                    showDomainBar(Panels.FILES);
                    filesList.reload();
                };
        filesScreen.fab = () -> filesList.newFolder();
        screens.put(Panels.FILES, filesScreen);

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
        screens.put(Panels.COMPOSE, composer);

        // NOTE: the message reader is untitled: the page leads with the
        // subject itself.
        Screen message = reader(() -> "", Panels.MAIL);
        Runnable readerChrome = message.chrome;
        message.chrome =
                () -> {
                    readerChrome.run();
                    findViewById(R.id.message_actions).setVisibility(View.VISIBLE);
                };
        screens.put(Panels.MESSAGE, message);

        Screen entry = new Screen();
        entry.chrome =
                () -> {
                    ((TextView) findViewById(R.id.bar_title)).setText(eventView.title());
                    findViewById(R.id.bar_back).setVisibility(View.VISIBLE);
                    if (eventView.editable()) {
                        findViewById(R.id.contact_add_field).setVisibility(View.VISIBLE);
                        findViewById(R.id.bar_delete).setVisibility(View.VISIBLE);
                        addFab(R.drawable.ic_save, R.string.event_save);
                        eventView.gate();
                    } else {
                        findViewById(R.id.fab).setVisibility(View.GONE);
                    }
                };
        entry.fab = () -> eventView.save();
        entry.barBack = () -> showBack(Panels.CALENDAR);
        entry.systemBack = () -> showBack(Panels.CALENDAR);
        screens.put(Panels.EVENT_VIEW, entry);

        Screen contact = new Screen();
        contact.chrome =
                () -> {
                    ((TextView) findViewById(R.id.bar_title)).setText(editor.edit().title);
                    findViewById(R.id.bar_back).setVisibility(View.VISIBLE);
                    // NOTE: placing needs a real account to target, so with
                    // only the local book or while resolving a conflict it
                    // hides.
                    findViewById(R.id.contact_books)
                            .setVisibility(
                                    editor.edit().card != null
                                                    && hasRealAccount()
                                                    && !editor.edit().resolvingConflict
                                            ? View.VISIBLE
                                            : View.GONE);
                    findViewById(R.id.contact_add_field).setVisibility(View.VISIBLE);
                    android.widget.ImageButton fab = findViewById(R.id.fab);
                    fab.setImageResource(R.drawable.ic_check);
                    fab.setContentDescription(getString(R.string.contact_save));
                    fab.setVisibility(View.VISIBLE);
                    updateSaveEnabled();
                };
        contact.fab = editor::save;
        contact.barBack = editor::close;
        contact.systemBack = editor::close;
        screens.put(Panels.CONTACT, contact);

        Screen auth = new Screen();
        auth.ownBar = true;
        auth.chrome =
                () -> {
                    // NOTE: no shared FAB here: every step carries its own
                    // full-width button.
                    onboarding.refreshStep(authFlipper.getDisplayedChild());
                    applyAuthChrome();
                };
        auth.systemBack = this::authBack;
        screens.put(PANEL_AUTH, auth);

        Screen account = new Screen();
        account.ownBar = true;
        // NOTE: every setting applies when changed, so no FAB.
        account.chrome = () -> findViewById(R.id.fab).setVisibility(View.GONE);
        screens.put(Panels.ACCOUNT, account);

        Screen filterScreen = new Screen();
        filterScreen.ownBar = true;
        filterScreen.chrome = () -> findViewById(R.id.fab).setVisibility(View.GONE);
        screens.put(Panels.FILTER, filterScreen);

        Screen deleted = new Screen();
        deleted.ownBar = true;
        deleted.chrome = () -> findViewById(R.id.fab).setVisibility(View.GONE);
        screens.put(Panels.DELETED, deleted);
    }

    /** Configures the bar and FAB for a screen: all off, then its own set. */
    @Override
    public void applyChrome(int panel) {
        // NOTE: posted, so the tab is drawn before its first sync's dialog
        // goes up over it.
        main.post(() -> sync.firstSyncIfOwed(domainOf(panel)));

        android.widget.ImageButton fab = findViewById(R.id.fab);

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

        // NOTE: the overlays carry their own bars, so only the FAB is shared.
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
                        R.id.contact_books,
                        R.id.contact_add_field,
                        R.id.bar_delete,
                        R.id.message_actions,
                        R.id.bar_send,
                        R.id.bottom_nav,
                    }) {
                findViewById(id).setVisibility(View.GONE);
            }
            dock.onList(false);
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

    /** Dismisses the soft keyboard, e.g. when leaving the edit form. */
    @Override
    public void hideKeyboard() {
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

    /**
     * The stored accounts; none, said in a toast, when the Keystore fails.
     *
     * <p>A launch never fails on them. When they were dropped for good (copied
     * from another phone without its key), the user is asked once to re-add.
     */
    private List<AccountEntry> storedAccounts() {
        List<AccountEntry> stored;
        try {
            stored = store.loadAll();
        } catch (IllegalStateException unreadable) {
            Log.w("pimalaya", "accounts not loaded", unreadable);
            toast(getString(R.string.accounts_unreadable));
            return new ArrayList<>();
        }
        if (store.takeLost()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.accounts_lost_title)
                    .setMessage(R.string.accounts_lost)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
        return stored;
    }

    /** A dimen resource in pixels (the shared item_* metrics). */
    private int dimen(int resId) {
        return getResources().getDimensionPixelSize(resId);
    }

    /**
     * Draws under the system bars from API 35, where edge-to-edge is enforced.
     *
     * <p>The insets push the chrome back in: the top pads the app bars, the
     * bottom lifts the FAB and list clearances above navigation and keyboard,
     * the sides pad for landscape bars and cutouts.
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
        // since the drawer runs under the status bar, and the round back
        // button's row of the onboarding steps that hide the auth bar).
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
                    R.id.domain_bar,
                    R.id.signin_bar,
                    R.id.oauth_bar,
                    R.id.books_bar,
                    R.id.result_bar,
                }) {
            padTop(bar, bars.top);
            findViewById(bar).setMinimumHeight(dimen(R.dimen.app_bar_height) + bars.top);
        }

        // NOTE: the base is each view's designed FAB clearance, so
        // re-applying stays idempotent as the listener fires again; the
        // bottom folds in the keyboard so the FAB rides above it.
        padBottom(R.id.fab_frame, 0, bottom);
        padBottom(R.id.account_page, 24, bottom);
        // NOTE: the bottom navigation takes the system bar's inset, never
        // the keyboard's, and the extended FAB rides above it.
        padBottom(R.id.bottom_nav, 0, bars.bottom);
        android.view.ViewGroup.MarginLayoutParams extended =
                (android.view.ViewGroup.MarginLayoutParams)
                        findViewById(R.id.fab_extended).getLayoutParams();
        extended.bottomMargin = dimen(R.dimen.fab_above_nav) + bars.bottom;
        findViewById(R.id.fab_extended).setLayoutParams(extended);
        // NOTE: the drawer's fixed bottom band takes the inset; the list
        // above it needs none.
        padBottom(R.id.drawer_actions, 8, bottom);
        padBottom(R.id.config_container, 88, bottom);
        padBottom(R.id.filter_actions, 16, bottom);
        padBottom(R.id.deleted_list, 24, bottom);
        padBottom(R.id.email_actions, 16, bottom);
        padBottom(R.id.domain_actions, 16, bottom);
        padBottom(R.id.result_actions, 16, bottom);
        padBottom(R.id.signin_actions, 16, bottom);
        padBottom(R.id.oauth_actions, 16, bottom);
        padBottom(R.id.books_actions, 16, bottom);
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
