package org.pimalaya.sync;

import android.Manifest;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.pimalaya.account.AccountActivation;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.LocalBook;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.pimalaya.account.SyncScope;
import org.pimalaya.contact.ContactEngine;
import org.pimalaya.mail.MailStore;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;

/**
 * Each account's background interval and mail notifications, and their job.
 *
 * <p>One periodic job ({@link Owner} names it), scheduled while any account
 * takes part. A run takes the accounts whose interval passed since any last
 * sync, so one just pulled in the app waits a full interval again.
 */
public final class BackgroundCheck {
    /** The application naming the job that runs the check. */
    public interface Owner {
        Class<? extends JobService> backgroundJob();
    }

    private static final String PREFS = "background-check";

    /** The intervals offered, in minutes, 0 for off; the default is 15. */
    public static final int[] INTERVALS = {0, 15, 30, 60, 120};

    private static final int DEFAULT = 15;

    /**
     * How early a run may take an account.
     *
     * <p>The job's period drifts by minutes: a run just short of an account's
     * interval would otherwise push it a whole period later.
     */
    private static final long SLACK = 5 * 60 * 1000L;

    /** The accounts whose new mail notifies. */
    private static final String NOTIFY = "notify";

    /** The background job's id with the scheduler. */
    public static final int JOB = 1;

    /** The period asked for; Android allows no less. */
    private static final long PERIOD = 15 * 60 * 1000L;

    private BackgroundCheck() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The account's background interval in minutes, 0 when off. */
    public static int interval(Context context, String email) {
        return prefs(context).getInt("interval:" + email, DEFAULT);
    }

    /** Sets the account's background interval in minutes, 0 for off. */
    public static void setInterval(Context context, String email, int minutes) {
        prefs(context).edit().putInt("interval:" + email, minutes).apply();
        schedule(context);
    }

    /** Whether the account syncs in the background at all. */
    public static boolean syncs(Context context, String email) {
        return interval(context, email) > 0;
    }

    /** Whether the account's interval has passed since it last synced. */
    public static boolean due(Context context, String email) {
        int minutes = interval(context, email);
        long elapsed = System.currentTimeMillis() - SyncStamps.at(context, email);
        return minutes > 0 && elapsed >= minutes * 60 * 1000L - SLACK;
    }

    /** Whether the account's new mail notifies; off until turned on. */
    public static boolean notifies(Context context, String email) {
        return prefs(context).getStringSet(NOTIFY, Set.of()).contains(email);
    }

    /**
     * Turns the account's new-mail notifications on or off.
     *
     * <p>On also syncs it in the background (at the default interval when off):
     * the background run is what notifies.
     */
    public static void setNotifies(Context context, String email, boolean on) {
        put(context, NOTIFY, email, on);
        if (on && !syncs(context, email)) {
            setInterval(context, email, DEFAULT);
        }
    }

    /**
     * The notification permissions {@code sdk} wants that {@code has} lacks.
     *
     * <p>Posting a notification is a runtime permission from Android 13.
     */
    public static String[] missing(int sdk, Predicate<String> has) {
        String permission = Manifest.permission.POST_NOTIFICATIONS;
        return sdk >= 33 && !has.test(permission) ? new String[] {permission} : new String[0];
    }

    /** Whether the app may notify. */
    public static boolean permitted(Context context) {
        Predicate<String> has =
                permission ->
                        context.checkSelfPermission(permission)
                                == PackageManager.PERMISSION_GRANTED;
        return missing(Build.VERSION.SDK_INT, has).length == 0;
    }

    /** Drops a removed account's switches. */
    public static void forget(Context context, String email) {
        prefs(context).edit().remove("interval:" + email).apply();
        put(context, NOTIFY, email, false);
        schedule(context);
    }

    private static void put(Context context, String key, String email, boolean member) {
        Set<String> emails = new HashSet<>(prefs(context).getStringSet(key, Set.of()));
        if (member) {
            emails.add(email);
        } else {
            emails.remove(email);
        }
        prefs(context).edit().putStringSet(key, emails).apply();
    }

    /**
     * Schedules the job while an account takes part, cancels it otherwise.
     *
     * <p>Scheduling again keeps the job's place, its period unchanged.
     */
    public static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        boolean wanted = false;
        for (AccountEntry account : new SecureStore(context).loadAll()) {
            if (!LocalBook.is(account.email) && syncs(context, account.email)) {
                wanted = true;
            }
        }
        if (!wanted) {
            jobs.cancel(JOB);
            return;
        }
        if (jobs.getPendingJob(JOB) != null) {
            return;
        }
        // NOTE: a context outside the app (the e2e harness) names no job.
        Context app = context.getApplicationContext();
        if (!(app instanceof Owner)) {
            return;
        }
        jobs.schedule(
                new JobInfo.Builder(
                                JOB, new ComponentName(context, ((Owner) app).backgroundJob()))
                        .setPeriodic(PERIOD)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .build());
    }

    /**
     * One background run, headless, over every account taking part.
     *
     * <p>Hands {@code notify} each notifying account's new mail as soon as its
     * mail pass ends. {@code stopped} is asked between domains, the job having
     * been told to stop.
     */
    public static void run(
            Context context,
            SyncPasses passes,
            BooleanSupplier stopped,
            BiConsumer<String, List<MailStore.StoredMessage>> notify) {
        PimdirDb pimdir = passes.parts.pimdir();
        SecureStore store = passes.parts.accounts();
        MailStore mail = passes.parts.mail();

        // NOTE: which accounts are due, read once: a pass stamps its
        // account, which would make it not due for the next domain.
        Set<String> due = new HashSet<>();
        for (AccountEntry account : store.loadAll()) {
            if (due(context, account.email)) {
                due.add(account.email);
            }
        }
        if (due.isEmpty()) {
            return;
        }

        passes.syncRemote(scope(context, PimDomain.CONTACTS, due));
        // NOTE: the last net under the phone's own triggers (a pass after
        // each write, Android's upload sync, the app's return), for every
        // mirrored book whether its account is due or not.
        Exception failure = passes.syncPhone(new ContactEngine.Report());
        if (failure != null) {
            Log.w("pimalaya", "background phone sync failed", failure);
        }
        // NOTE: the same net for every calendar the phone shows, which is
        // also what moves the window of a series listed instance by instance.
        passes.syncPhoneCalendars();
        if (stopped.getAsBoolean()) {
            return;
        }

        SyncScope mailScope = scope(context, PimDomain.MAIL, due);
        PimdirCollections collections = new PimdirCollections(pimdir, context);
        List<String> notified = new ArrayList<>();
        for (AccountEntry account : passes.accountsFor(PimDomain.MAIL)) {
            if (mailScope.account(account.email)
                    && notifies(context, account.email)) {
                notified.add(account.email);
            }
        }
        Map<String, Map<String, MailStore.StoredMessage>> before = new LinkedHashMap<>();
        Map<String, String> floors = new HashMap<>();
        for (String email : notified) {
            before.put(email, unreadInbox(mail, collections, email));
            for (String inbox : inboxes(collections, email)) {
                floors.put(inbox, mail.coverage(inbox).since);
            }
        }
        // NOTE: a run lists no mail on a metered network, the outbox going
        // out all the same; the next run on Wi-Fi brings what arrived.
        android.net.ConnectivityManager connectivity =
                context.getSystemService(android.net.ConnectivityManager.class);
        boolean listing = connectivity != null && !connectivity.isActiveNetworkMetered();
        SyncPasses.MailPass pass = passes.mailPass(mailScope, listing);
        if (pass.failure != null) {
            Log.w("pimalaya", "background mail sync failed", pass.failure);
        }
        for (AccountEntry account : passes.accountsFor(PimDomain.MAIL)) {
            if (!notified.contains(account.email)) {
                continue;
            }
            List<MailStore.StoredMessage> arrived =
                    arrived(
                            before.get(account.email),
                            unreadInbox(mail, collections, account.email),
                            floors);
            // NOTE: the bodies before the notification, so tapping it opens
            // a list whose new rows are already downloaded.
            if (listing && !arrived.isEmpty()) {
                passes.arrivedBodies(account, arrived);
            }
            notify.accept(account.email, arrived);
        }
        if (stopped.getAsBoolean()) {
            return;
        }

        Exception calendars = passes.calendarPass(scope(context, PimDomain.CALENDAR, due));
        if (calendars != null) {
            Log.w("pimalaya", "background calendar sync failed", calendars);
        }
    }

    /**
     * The accounts a run takes for one domain: on, due, and past first sync.
     *
     * <p>The first sync of a domain is the app's, never a background run's.
     */
    private static SyncScope scope(Context context, PimDomain domain, Set<String> due) {
        List<String> owing = FirstSync.owing(context, domain);
        // NOTE: a mailbox the mail filter unticks neither shows nor syncs.
        MergedFilter mail = domain == PimDomain.MAIL ? MergedFilter.of(context, domain) : null;
        return new SyncScope() {
            @Override
            public boolean account(String email) {
                return AccountActivation.enabled(context, email)
                        && due.contains(email)
                        && !owing.contains(email);
            }

            @Override
            public boolean collection(String email, String collection) {
                return account(email) && (mail == null || mail.chosen(collection));
            }
        };
    }

    /**
     * What a run notifies, newest first: unread inbox mail new since the pass.
     *
     * <p>In {@code now} not {@code before}, dated on or after the inbox's floor
     * before the pass ({@code floors}, null for none): an older band an
     * interrupted fill left lies below it and never notifies.
     */
    static List<MailStore.StoredMessage> arrived(
            Map<String, MailStore.StoredMessage> before,
            Map<String, MailStore.StoredMessage> now,
            Map<String, String> floors) {
        List<MailStore.StoredMessage> arrived = new ArrayList<>();
        for (Map.Entry<String, MailStore.StoredMessage> entry : now.entrySet()) {
            MailStore.StoredMessage message = entry.getValue();
            String floor = floors.get(message.collection);
            if (!before.containsKey(entry.getKey())
                    && (floor == null || message.sortKey.compareTo(floor) >= 0)) {
                arrived.add(message);
            }
        }
        return arrived;
    }

    /** An account's inboxes, by collection id. */
    private static List<String> inboxes(PimdirCollections collections, String email) {
        List<String> inbox = new ArrayList<>();
        for (PimdirCollections.Stored mailbox : collections.list(PimdirSummary.MAIL)) {
            if (email.equals(mailbox.accountEmail) && "inbox".equals(mailbox.role)) {
                inbox.add(mailbox.id);
            }
        }
        return inbox;
    }

    /**
     * An account's unread inbox messages in its window, newest first.
     *
     * <p>Keyed by collection and id.
     */
    private static Map<String, MailStore.StoredMessage> unreadInbox(
            MailStore mail, PimdirCollections collections, String email) {
        List<String> inbox = inboxes(collections, email);
        Map<String, MailStore.StoredMessage> unread = new LinkedHashMap<>();
        if (inbox.isEmpty()) {
            return unread;
        }
        for (MailStore.StoredMessage message :
                mail.all(
                        new MailStore.Query(inbox, 0, null, null, List.of())
                                .since(mail.windowOf(email)))) {
            if (!message.deleted()) {
                unread.put(message.collection + "/" + message.id, message);
            }
        }
        return unread;
    }
}
