package org.pimalaya.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.pimalaya.R;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.LocalBook;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SyncScope;
import org.pimalaya.contact.BookEntry;
import org.pimalaya.contact.CardStore;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirEngine;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.SyncDock;

/**
 * The sync dock's model of a pass: the account, domain and step it is on,
 * its plan and its bar. The passes report into it from any thread; it
 * draws on the dock on the main thread.
 */
final class SyncProgress implements SyncPasses.Progress {
    private final Host host;

    private final CardStore base;

    /** The passes a plan reads the accounts and collections of. */
    private final Supplier<SyncPasses> passes;

    /** The dock, built after the passes. */
    private final Supplier<SyncDock> dock;

    /** Whether a pass runs, the dock drawn only then. */
    private final BooleanSupplier syncing;

    /** Keeps the account cache current after a token refresh. */
    private final Consumer<AccountEntry> renewed;

    SyncProgress(
            Host host,
            CardStore base,
            Supplier<SyncPasses> passes,
            Supplier<SyncDock> dock,
            BooleanSupplier syncing,
            Consumer<AccountEntry> renewed) {
        this.host = host;
        this.base = base;
        this.passes = passes;
        this.dock = dock;
        this.syncing = syncing;
        this.renewed = renewed;
    }

    @Override
    public void step(PimDomain domain, int stage, int count) {
        syncStep(domain, stage, count);
    }

    @Override
    public void advance(PimDomain domain, int stage, int done, int total) {
        syncAdvance(domain, stage, done, total);
    }

    @Override
    public void account(String email) {
        syncAccount(email);
    }

    @Override
    public void collections(PimDomain domain, int done, int total) {
        syncCollections(domain, done, total);
    }

    @Override
    public void accountRefreshed(AccountEntry updated) {
        renewed.accept(updated);
    }

    /** A pass starts on the dock, the last one's lines gone (main thread). */
    void started() {
        synchronized (passState) {
            syncLine = "";
            syncDetail = null;
        }
        dock.get().started();
        dock.get().log(host.getString(R.string.sync_line_starting));
        renderSyncDock();
    }

    /** The pass downloads older mail (Load more), said straight away (main thread). */
    void fill() {
        synchronized (passState) {
            syncFill = true;
        }
        renderSyncDock();
    }

    /**
     * Draws the pass on the dock (main thread): the account, or that the
     * pass is starting before it reaches one, then the domain and the step,
     * how far it is through its accounts, and a row per account for the
     * sheet.
     */
    private void renderSyncDock() {
        if (!syncing.getAsBoolean()) {
            return;
        }
        SyncDock.Pass pass = new SyncDock.Pass();
        synchronized (passState) {
            pass.title = syncAccount == null ? host.getString(R.string.sync_line_starting) : syncAccount;
            // NOTE: the domain only where the pass runs several (the
            // drawer's), a list's own pass being its tab's domain already.
            String domain = syncFill ? host.getString(R.string.mail_filling) : syncLine;
            Set<String> domains = new HashSet<>();
            for (String section : syncPlan.keySet()) {
                domains.add(section.substring(0, section.indexOf('\n')));
            }
            if (syncDetail != null && !syncFill && domains.size() <= 1) {
                pass.line = syncDetail;
            } else if (!domain.isEmpty()) {
                pass.line =
                        syncDetail == null
                                ? domain
                                : host.getString(R.string.sync_line_account, domain, syncDetail);
            }
            // NOTE: indeterminate until something is counted: a first Gmail
            // chunk can take seconds before its first mailbox lands.
            pass.done = syncShown;
            pass.total = syncShown > 0 ? 1000 : 0;
            pass.download = syncFill;
            int of = syncPlan.size();
            if (of > 1 && !syncReached.isEmpty()) {
                pass.count =
                        host.getString(R.string.sync_dock_count, Math.min(syncReached.size(), of), of);
            }
            pass.rows = syncRows(pass.line);
        }
        dock.get().pass(pass);
    }

    /**
     * The pass's sections as the sheet lists them (holding the pass's
     * state): those passed, done, the one under way saying {@code running},
     * then those to come. A pass over several domains names each row's.
     */
    private List<SyncDock.Row> syncRows(String running) {
        Set<String> domains = new HashSet<>();
        List<String> sections = new ArrayList<>(syncReached);
        for (String section : syncPlan.keySet()) {
            domains.add(section.substring(0, section.indexOf('\n')));
            if (!sections.contains(section)) {
                sections.add(section);
            }
        }
        List<SyncDock.Row> rows = new ArrayList<>();
        for (String section : sections) {
            // NOTE: a count before the pass reached an account opens a
            // section of none, which no row stands for.
            if (section.endsWith("\n" + null)) {
                continue;
            }
            int cut = section.indexOf('\n');
            SyncDock.Row row = new SyncDock.Row();
            row.account = section.substring(cut + 1);
            if (section.equals(syncSection)) {
                row.state = SyncDock.Row.RUNNING;
                row.line = running;
            } else {
                row.state =
                        syncReached.contains(section) ? SyncDock.Row.DONE : SyncDock.Row.NEXT;
                String state =
                        host.getString(
                                row.state == SyncDock.Row.DONE
                                        ? R.string.sync_dock_done
                                        : R.string.sync_dock_next);
                row.line =
                        domains.size() > 1
                                ? host.getString(
                                        R.string.sync_line_account,
                                        host.getString(PimDomain.byId(section.substring(0, cut)).label),
                                        state)
                                : state;
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Keeps the step a pass stands at for the dock's sheet, after the
     * account it is on (holding the pass's state).
     */
    private void syncLog(String detail) {
        String line =
                syncAccount == null
                        ? detail
                        : host.getString(R.string.sync_line_account, syncAccount, detail);
        host.postAlive(() -> dock.get().log(line));
    }

    /** Guards the dock's state, which the pass's workers set side by side. */
    private final Object passState = new Object();

    /** The account a pass is on, null until it reaches one. */
    private String syncAccount;

    /** The domain the pass is on, said in the dock's line. */
    private String syncLine = "";

    /** The engine's step the pass is on, after the domain; null for none yet. */
    private String syncDetail;

    /** Whether the pass downloads older mail (Load more). */
    private boolean syncFill;

    /** Whether the pass takes mail steps, whose counts are not mailboxes. */
    private boolean syncStepping;

    /**
     * The pass's sections, one per domain and account it runs, each weighing
     * the collections stored for it, one at least: the bar's whole is their
     * sum, so it fills once over the pass rather than once per account. In
     * the order they were planned, the sheet's order for those to come.
     */
    private final Map<String, Integer> syncPlan = new LinkedHashMap<>();

    /** The sections the pass reached, in order, the last one under way. */
    private final List<String> syncReached = new ArrayList<>();

    private int syncPlanned;

    /** What the sections the pass left behind weigh. */
    private int syncPassed;

    /** The section under way, null before the first. */
    private String syncSection;

    /**
     * How many of the section's collections have landed, 0 of 0 until the
     * pass knows how many there are.
     */
    private int syncLanded;

    private int syncLandedOf;

    /** How much of the collection under way its counted steps have filled. */
    private double syncShare;

    /**
     * The bar in thousandths, which only ever grows over a pass: a step or a
     * section starting at nothing holds it rather than emptying it.
     */
    private int syncShown;

    /** Forgets the last pass's plan and bar (main thread, before a pass). */
    void resetSyncPlan() {
        synchronized (passState) {
            syncPlan.clear();
            syncPlanned = 0;
            syncPassed = 0;
            syncSection = null;
            syncLanded = 0;
            syncLandedOf = 0;
            syncShare = 0;
            syncShown = 0;
            syncReached.clear();
            syncDetail = null;
            syncFill = false;
            syncStepping = false;
        }
    }

    /**
     * Sizes a domain's part of the pass before it runs (any thread): one
     * section per account the pass takes, {@code only} narrowing them, each
     * weighing the collections stored for it within the scope.
     */
    void planSync(PimDomain domain, SyncScope scope, Collection<String> only) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        if (domain == PimDomain.CONTACTS) {
            for (BookEntry book : base.loadSubscribedAddressbooks()) {
                if (!LocalBook.is(book.accountEmail)) {
                    weights.put(book.accountEmail, 0);
                }
            }
        } else {
            for (AccountEntry account : passes.get().accountsFor(domain)) {
                weights.put(account.email, 0);
            }
        }
        weights.keySet()
                .removeIf(email -> !scope.account(email) || (only != null && !only.contains(email)));
        for (PimdirCollections.Stored stored : passes.get().collectionsOf(domain)) {
            Integer weight = weights.get(stored.accountEmail);
            if (weight != null && scope.collection(stored.accountEmail, stored.id)) {
                weights.put(stored.accountEmail, weight + 1);
            }
        }
        synchronized (passState) {
            for (Map.Entry<String, Integer> entry : weights.entrySet()) {
                int weight = Math.max(1, entry.getValue());
                syncPlan.put(sectionOf(domain, entry.getKey()), weight);
                syncPlanned += weight;
            }
        }
    }

    /**
     * Sizes a pass of mail steps before it runs (any thread): one section
     * per account of {@code emails}, each weighing one, since every step
     * brings about as many bodies whatever the account holds.
     */
    void planSteps(List<String> emails) {
        synchronized (passState) {
            syncStepping = true;
            for (String email : emails) {
                if (syncPlan.put(sectionOf(PimDomain.MAIL, email), 1) == null) {
                    syncPlanned += 1;
                }
            }
        }
    }

    private static String sectionOf(PimDomain domain, String accountEmail) {
        return domain.id + "\n" + accountEmail;
    }

    /**
     * A section's weight, a section the plan missed weighing one and
     * growing the whole (holding the pass's state).
     */
    private int weightOf(String section) {
        Integer weight = syncPlan.get(section);
        if (weight == null) {
            syncPlan.put(section, 1);
            syncPlanned += 1;
            return 1;
        }
        return weight;
    }

    /** Moves the bar to where the pass stands, never back (holding the pass's state). */
    private void syncShow() {
        int section = SyncSteps.permille(syncLanded, syncLandedOf, syncShare);
        int whole =
                syncSection == null
                        ? 0
                        : SyncSteps.across(syncPassed, weightOf(syncSection), syncPlanned, section);
        syncShown = Math.max(syncShown, whole);
    }

    /**
     * Names the account a pass moved on to, null between accounts (callable
     * off the main thread), so a pass over several says whose it is on. The
     * counts start over, the bar holding what the pass came to.
     */
    void syncAccount(String accountEmail) {
        synchronized (passState) {
            syncAccount = accountEmail;
            syncLanded = 0;
            syncLandedOf = 0;
            syncShare = 0;
            syncDetail = null;
        }
        host.postAlive(this::renderSyncDock);
    }

    /**
     * Sets the dock's line from the domain an engine step runs in and the
     * step itself (any thread), keeping the step for the sheet. A step the
     * domain has no words for leaves the last one.
     */
    private void syncStep(PimDomain domain, int stage, int count) {
        String line = host.getString(SyncSteps.lineOf(domain));
        String detail = SyncSteps.text(host.getResources(), domain, stage, count);
        synchronized (passState) {
            syncLine = line;
            if (detail != null) {
                syncDetail = detail;
                syncLog(detail);
            }
        }
        host.postAlive(this::renderSyncDock);
    }

    /**
     * Fills the bar with how far a counted step is (any thread): the
     * contacts or events read so far, or written to the phone. A mail step
     * tells its download only so, its step then naming the bodies it brings.
     */
    private void syncAdvance(PimDomain domain, int stage, int done, int total) {
        String detail =
                domain == PimDomain.MAIL && stage == PimdirEngine.Progress.STAGE_DOWNLOAD
                        ? SyncSteps.text(host.getResources(), domain, stage, total)
                        : null;
        synchronized (passState) {
            if (syncStepping && detail != null && !detail.equals(syncDetail)) {
                syncDetail = detail;
                syncLog(detail);
            }
            syncShare = SyncSteps.shareOf(domain, stage, done, total);
            syncShow();
        }
        host.postAlive(this::renderSyncDock);
    }

    /**
     * Fills the bar with how many of the account's collections have landed
     * (any thread): its mailboxes, address books or calendars. The first
     * count of a domain and account opens its section, the one before it
     * passed. A count overtaken by a worker beside it is dropped. The count
     * is the dock's step, but in a mail step, whose one count is the step.
     */
    private void syncCollections(PimDomain domain, int done, int total) {
        String line = host.getString(SyncSteps.lineOf(domain));
        synchronized (passState) {
            String section = sectionOf(domain, syncAccount);
            if (!section.equals(syncSection)) {
                if (syncSection != null) {
                    syncPassed += weightOf(syncSection);
                }
                syncSection = section;
                if (!syncReached.contains(section)) {
                    syncReached.add(section);
                }
                syncLanded = 0;
                syncLandedOf = 0;
            } else if (total == syncLandedOf && done < syncLanded) {
                return;
            }
            syncLine = line;
            syncLanded = done;
            syncLandedOf = total;
            syncShare = 0;
            syncShow();
            if (!syncStepping && total > 0) {
                syncDetail = host.getString(SyncSteps.collectionsOf(domain), done, total);
                syncLog(syncDetail);
            }
        }
        host.postAlive(this::renderSyncDock);
    }
}
