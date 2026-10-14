package org.pimalaya;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.util.List;
import org.pimalaya.account.SecureStore;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.Clients;
import org.pimalaya.contact.CardStore;
import org.pimalaya.mail.MailStore;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.sync.BackgroundCheck;
import org.pimalaya.sync.SyncPasses;

/**
 * The background check: syncs every account taking part, notifies new mail.
 *
 * <p>New is the unread inbox messages present after this run's mail pass and
 * not before ({@link BackgroundCheck}): anything wider would fire for every
 * older message a fill or a widened bound listed in between.
 */
public class BackgroundJob extends JobService {
    /** At most this many notifications per account per run, the newest. */
    private static final int BURST = 5;

    private volatile boolean stopped;

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(
                        () -> {
                            try {
                                run();
                            } catch (Exception error) {
                                Log.w("pimalaya", "background check failed", error);
                            } finally {
                                jobFinished(params, false);
                            }
                        },
                        "background-check")
                .start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped = true;
        return false;
    }

    private void run() {
        AppState state = App.of(this).state;
        // NOTE: the app on screen syncs on the user's word, and its lists
        // would not know what a run wrote under them.
        if (state.onScreen || !App.of(this).pimdir().locks().takeSync()) {
            return;
        }
        state.backgroundRunning = true;
        try {
            check(this);
            state.backgroundRan = System.currentTimeMillis();
        } finally {
            state.backgroundRunning = false;
            App.of(this).pimdir().locks().releaseSync();
            Runnable ended = state.onBackgroundEnd;
            if (ended != null) {
                ended.run();
            }
        }
    }

    private void check(Context context) {
        PimdirDb pimdir = App.of(context).pimdir();
        CardStore base = App.of(context).cards();
        SecureStore store = new SecureStore(context);
        MailStore mail = new MailStore(context, pimdir);
        EventStore events = new EventStore(context, pimdir);
        Clients clients = Clients.real();
        SyncPasses passes =
                new SyncPasses(
                        context,
                        new SyncPasses.Parts(pimdir, base, store, mail, events, clients),
                        SyncPasses.HEADLESS);

        BackgroundCheck.run(
                context, passes, () -> stopped, (email, arrived) -> notify(context, email, arrived));
    }

    /** Notifies the newest {@link #BURST} arrivals under an account summary. */
    private static void notify(
            Context context, String email, List<MailStore.StoredMessage> arrived) {
        if (arrived.isEmpty() || !BackgroundCheck.permitted(context)) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String channel = "mail-" + email;
        manager.createNotificationChannel(
                new NotificationChannel(channel, email, NotificationManager.IMPORTANCE_DEFAULT));

        Intent launch =
                context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        PendingIntent open =
                PendingIntent.getActivity(
                        context,
                        0,
                        launch,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.InboxStyle lines = new Notification.InboxStyle();
        for (MailStore.StoredMessage message :
                arrived.subList(0, Math.min(BURST, arrived.size()))) {
            String sender = sender(message);
            lines.addLine(sender + "  " + message.subject);
            manager.notify(
                    (message.collection + "/" + message.id).hashCode(),
                    new Notification.Builder(context, channel)
                            .setSmallIcon(R.drawable.ic_domain_mail)
                            .setContentTitle(sender)
                            .setContentText(message.subject)
                            .setSubText(email)
                            .setWhen(message.stamp)
                            .setShowWhen(true)
                            .setGroup(email)
                            .setAutoCancel(true)
                            .setContentIntent(open)
                            .build());
        }
        String count =
                context.getResources()
                        .getQuantityString(
                                R.plurals.notify_new_mail, arrived.size(), arrived.size());
        lines.setSummaryText(email);
        manager.notify(
                email.hashCode(),
                new Notification.Builder(context, channel)
                        .setSmallIcon(R.drawable.ic_domain_mail)
                        .setContentTitle(count)
                        .setSubText(email)
                        .setStyle(lines)
                        .setGroup(email)
                        .setGroupSummary(true)
                        .setAutoCancel(true)
                        .setContentIntent(open)
                        .build());
    }

    private static String sender(MailStore.StoredMessage message) {
        return message.fromName == null || message.fromName.isEmpty()
                ? message.fromAddress
                : message.fromName;
    }
}
