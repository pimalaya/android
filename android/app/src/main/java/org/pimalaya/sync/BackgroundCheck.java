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
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.LocalBook;
import org.pimalaya.account.SecureStore;

/**
 * How often each account syncs in the background and whether its new mail
 * notifies, as its settings say, and the periodic job that does it
 * (the one the application names, {@link Owner}), scheduled while any
 * account takes part.
 *
 * <p>One job for every account, at the shortest period Android allows: a
 * run takes the accounts whose own interval has passed since they last
 * synced, by any sync, so an account just pulled in the app waits its full
 * interval again.
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
     * How early a run may take an account: the job's own period drifts by
     * minutes, and a run landing just short of an account's interval would
     * otherwise push it a whole period later.
     */
    private static final long SLACK = 5 * 60 * 1000L;

    /** The accounts whose new mail notifies. */
    private static final String NOTIFY = "notify";

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
     * Turns the account's new-mail notifications on or off. On, the account
     * syncs in the background too, at the default interval when it was off:
     * the background run is what notifies.
     */
    public static void setNotifies(Context context, String email, boolean on) {
        put(context, NOTIFY, email, on);
        if (on && !syncs(context, email)) {
            setInterval(context, email, DEFAULT);
        }
    }

    /**
     * The permissions notifying takes on the given SDK, those {@code has}
     * holds left out: posting one is a runtime permission from Android 13.
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
     * Scheduling again keeps the job's place, its period unchanged.
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
}
