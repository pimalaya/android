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
import java.util.ArrayList;
import java.util.HashMap;
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
        // NOTE: the same net for every calendar the phone shows, which is
        // also what moves the window of a series listed instance by instance.
        runner.syncPhoneCalendars();
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
        Map<String, String> floors = new HashMap<>();
        for (String email : notified) {
            before.put(email, unreadInbox(mail, collections, email));
            for (String inbox : inboxes(collections, email)) {
                floors.put(inbox, mail.coverage(inbox).since);
            }
        }
        RemotePass.MailPass pass = remote.mailPass(mailScope);
        if (pass.failure != null) {
            Log.w("pimalaya", "background mail sync failed", pass.failure);
        }
        for (String email : notified) {
            notify(
                    context,
                    email,
                    arrived(before.get(email), unreadInbox(mail, collections, email), floors));
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

    /**
     * What a run notifies, newest first: the unread inbox messages present
     * after its pass ({@code now}) and not before it, dated on or after
     * their inbox's floor as it stood before the pass ({@code floors} by
     * collection, null for none). A band of older mail an interrupted fill
     * left for the pass to finish lies below that floor and never notifies;
     * new mail, delayed or not, lies above it.
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
     * An account's unread inbox messages within its window, newest first, by
     * collection and id.
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
