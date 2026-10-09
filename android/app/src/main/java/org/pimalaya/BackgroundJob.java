package org.pimalaya;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pimalaya.client.PimalayaClient;

/**
 * The background check ({@link BackgroundCheck}): syncs every account
 * taking part, headless, and notifies the new mail the run brought.
 *
 * <p>New is what this run added: the unread inbox messages present after
 * its mail pass and not before. A notification keyed on anything wider,
 * such as the messages not seen at the previous run, would fire for every
 * older message a fill or a widened bound listed in between.
 */
public class BackgroundJob extends JobService {
    /** At most this many notifications per account per run, the newest. */
    private static final int BURST = 5;

    /** When a run last synced, for the app to reload what it wrote. */
    static volatile long ran;

    /** Whether a run is syncing, for the app's strip. */
    static volatile boolean running;

    /** Called on the run's thread when it ends; the app's, while it lives. */
    static volatile Runnable onEnd;

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
        // NOTE: the app on screen syncs on the user's word, and its lists
        // would not know what a run wrote under them.
        if (MainActivity.onScreen || !SyncLock.take()) {
            return;
        }
        running = true;
        try {
            check(this);
            ran = System.currentTimeMillis();
        } finally {
            running = false;
            SyncLock.release();
            Runnable ended = onEnd;
            if (ended != null) {
                ended.run();
            }
        }
    }

    private void check(Context context) {
        PimdirDb pimdir = new PimdirDb(context);
        CardStore base = new CardStore(context, pimdir);
        SecureStore store = new SecureStore(context);
        MailStore mail = new MailStore(context, pimdir);
        EventStore events = new EventStore(context, pimdir);
        PimalayaClient client = new PimalayaClient();
        SyncRunner runner = new SyncRunner(context, base, pimdir, store, client, null);
        RemotePass remote =
                new RemotePass(
                        context, pimdir, client, store, mail, events, runner, RemotePass.HEADLESS);

        // NOTE: which accounts are due, read once: a pass stamps its
        // account, which would make it not due for the next domain.
        Set<String> due = new HashSet<>();
        for (AccountEntry account : store.loadAll()) {
            if (BackgroundCheck.due(context, account.email)) {
                due.add(account.email);
            }
        }
        if (due.isEmpty()) {
            return;
        }

        runner.syncRemote(scope(context, PimDomain.CONTACTS, due));
        // NOTE: the last net under the phone's own triggers (a pass after
        // each write, Android's upload sync, the app's return), for every
        // mirrored book whether its account is due or not.
        Exception failure = runner.syncPhone(new OfflineEngine.Report());
        if (failure != null) {
            Log.w("pimalaya", "background phone sync failed", failure);
        }
        if (stopped) {
            return;
        }

        SyncScope mailScope = scope(context, PimDomain.MAIL, due);
        PimdirCollections collections = new PimdirCollections(pimdir, context);
        List<String> notified = new ArrayList<>();
        for (AccountEntry account : remote.accountsFor(PimDomain.MAIL)) {
            if (mailScope.account(account.email)
                    && BackgroundCheck.notifies(context, account.email)) {
                notified.add(account.email);
            }
        }
        Map<String, Map<String, MailStore.StoredMessage>> before = new LinkedHashMap<>();
        for (String email : notified) {
            before.put(email, unreadInbox(mail, collections, email));
        }
        RemotePass.MailPass pass = remote.mailPass(mailScope);
        if (pass.failure != null) {
            Log.w("pimalaya", "background mail sync failed", pass.failure);
        }
        for (String email : notified) {
            List<MailStore.StoredMessage> arrived = new ArrayList<>();
            for (Map.Entry<String, MailStore.StoredMessage> now :
                    unreadInbox(mail, collections, email).entrySet()) {
                if (!before.get(email).containsKey(now.getKey())) {
                    arrived.add(now.getValue());
                }
            }
            notify(context, email, arrived);
        }
        if (stopped) {
            return;
        }

        Exception calendars = remote.calendarPass(scope(context, PimDomain.CALENDAR, due));
        if (calendars != null) {
            Log.w("pimalaya", "background calendar sync failed", calendars);
        }
    }

    /**
     * The accounts a run takes for one domain: on, due, and past their first
     * sync of it, which is the app's.
     */
    private static SyncScope scope(Context context, PimDomain domain, Set<String> due) {
        List<String> owing = FirstSync.owing(context, domain);
        return new SyncScope() {
            @Override
            public boolean account(String email) {
                return AccountActivation.enabled(context, email)
                        && due.contains(email)
                        && !owing.contains(email);
            }

            @Override
            public boolean collection(String email, String collection) {
                return account(email);
            }
        };
    }

    /** An account's unread inbox messages, newest first, by collection and id. */
    private static Map<String, MailStore.StoredMessage> unreadInbox(
            MailStore mail, PimdirCollections collections, String email) {
        List<String> inbox = new ArrayList<>();
        for (PimdirCollections.Stored mailbox : collections.list(PimdirSummary.MAIL)) {
            if (email.equals(mailbox.accountEmail) && "inbox".equals(mailbox.role)) {
                inbox.add(mailbox.id);
            }
        }
        Map<String, MailStore.StoredMessage> unread = new LinkedHashMap<>();
        if (inbox.isEmpty()) {
            return unread;
        }
        for (MailStore.StoredMessage message :
                mail.all(new MailStore.Query(inbox, 0, null, null, List.of()))) {
            if (!message.deleted) {
                unread.put(message.collection + "/" + message.id, message);
            }
        }
        return unread;
    }

    /**
     * One notification per arrived message, the newest {@link #BURST},
     * grouped under the account's summary, on the account's own channel.
     */
    private static void notify(
            Context context, String email, List<MailStore.StoredMessage> arrived) {
        // NOTE: a runtime permission from Android 13 on, granted before.
        if (arrived.isEmpty()
                || android.os.Build.VERSION.SDK_INT >= 33
                        && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
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

    private static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
