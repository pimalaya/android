package org.pimalaya;

import android.Manifest;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * How often each account syncs in the background and whether its new mail
 * notifies, as its settings say, and the periodic job that does it
 * ({@link BackgroundJob}), scheduled while any account takes part.
 *
 * <p>One job for every account, at the shortest period Android allows: a
 * run takes the accounts whose own interval has passed since they last
 * synced, by any sync, so an account just pulled in the app waits its full
 * interval again.
 */
final class BackgroundCheck {
    private static final String PREFS = "background-check";

    /** The intervals offered, in minutes, 0 for off; the default is 15. */
    static final int[] INTERVALS = {0, 15, 30, 60, 120};

    private static final int DEFAULT = 15;

    /**
     * How early a run may take an account: the job's own period drifts by
     * minutes, and a run landing just short of an account's interval would
     * otherwise push it a whole period later.
     */
    private static final long SLACK = 5 * 60 * 1000L;

    /** The accounts whose new mail notifies. */
    private static final String NOTIFY = "notify";

    static final int JOB = 1;

    /** The period asked for; Android allows no less. */
    private static final long PERIOD = 15 * 60 * 1000L;

    private BackgroundCheck() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The account's background interval in minutes, 0 when off. */
    static int interval(Context context, String email) {
        return prefs(context).getInt("interval:" + email, DEFAULT);
    }

    static void setInterval(Context context, String email, int minutes) {
        prefs(context).edit().putInt("interval:" + email, minutes).apply();
        schedule(context);
    }

    /** Whether the account syncs in the background at all. */
    static boolean syncs(Context context, String email) {
        return interval(context, email) > 0;
    }

    /** Whether the account's interval has passed since it last synced. */
    static boolean due(Context context, String email) {
        int minutes = interval(context, email);
        long elapsed = System.currentTimeMillis() - SyncStamps.at(context, email);
        return minutes > 0 && elapsed >= minutes * 60 * 1000L - SLACK;
    }

    /** Whether the account's new mail notifies; off until turned on. */
    static boolean notifies(Context context, String email) {
        return prefs(context).getStringSet(NOTIFY, Set.of()).contains(email);
    }

    /**
     * Turns the account's new-mail notifications on or off. On, the account
     * syncs in the background too, at the default interval when it was off:
     * the background run is what notifies.
     */
    static void setNotifies(Context context, String email, boolean on) {
        put(context, NOTIFY, email, on);
        // NOTE: the watermark goes, so the next run seeds it afresh from the
        // unread mail already there rather than notifying it.
        prefs(context).edit().remove("notified:" + email).apply();
        if (on && !syncs(context, email)) {
            setInterval(context, email, DEFAULT);
        }
    }

    /**
     * The sort key of the newest message a run notified for the account,
     * kept no later than the run, null before any (the next run seeds it):
     * nothing at or below it notifies again.
     */
    static String notifiedUpTo(Context context, String email) {
        return prefs(context).getString("notified:" + email, null);
    }

    static void setNotifiedUpTo(Context context, String email, String sortKey) {
        prefs(context).edit().putString("notified:" + email, sortKey).apply();
    }

    /**
     * The permissions notifying takes on the given SDK, those {@code has}
     * holds left out: posting one is a runtime permission from Android 13.
     */
    static String[] missing(int sdk, Predicate<String> has) {
        String permission = Manifest.permission.POST_NOTIFICATIONS;
        return sdk >= 33 && !has.test(permission) ? new String[] {permission} : new String[0];
    }

    /** Whether the app may notify. */
    static boolean permitted(Context context) {
        Predicate<String> has =
                permission ->
                        context.checkSelfPermission(permission)
                                == PackageManager.PERMISSION_GRANTED;
        return missing(Build.VERSION.SDK_INT, has).length == 0;
    }

    /** Drops a removed account's switches. */
    static void forget(Context context, String email) {
        prefs(context)
                .edit()
                .remove("interval:" + email)
                .remove("notified:" + email)
                .apply();
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
    static void schedule(Context context) {
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
        jobs.schedule(
                new JobInfo.Builder(JOB, new ComponentName(context, BackgroundJob.class))
                        .setPeriodic(PERIOD)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .build());
    }
}
