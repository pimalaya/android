package org.pimalaya;

import android.app.Application;
import android.app.job.JobService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.calendar.CalendarPhonePass;
import org.pimalaya.client.Clients;
import org.pimalaya.contact.CardStore;
import org.pimalaya.contact.ContactEngine;
import org.pimalaya.contact.ContactPhonePass;
import org.pimalaya.mail.MailState;
import org.pimalaya.phone.PhoneQueue;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.sync.BackgroundCheck;

/**
 * The process: the one holder of what every screen, service and job of the
 * app shares. Nothing runs at creation; each part opens when first asked for.
 */
public final class App extends Application
        implements PhoneQueue.Owner, BackgroundCheck.Owner, MergedFilter.Owner, MailState.Owner {
    /** The stamps the activity, the background check and the sync adapters share. */
    final AppState state = new AppState();

    /** The process's one store, null until first asked for ({@link #pimdir}). */
    private PimdirDb pimdir;

    /** The process's one card store, null until first asked for ({@link #cards}). */
    private CardStore cards;

    /** The process's list filters, by list. */
    private final Map<String, MergedFilter> filters = new HashMap<>();

    /** What the process keeps of mail between screens and runs. */
    private final MailState mailState = new MailState();

    /** The process's queue, once the app started it. */
    private volatile PhoneQueue phoneQueue;

    /** The process of a context. */
    static App of(Context context) {
        return (App) context.getApplicationContext();
    }

    /**
     * The process's one store, opened once: every screen, service and job
     * shares it, so the store is one connection pool rather than one per
     * caller, each holding the file open until the collector finds it.
     */
    synchronized PimdirDb pimdir() {
        if (pimdir == null) {
            pimdir = new PimdirDb(this);
        }
        return pimdir;
    }

    /**
     * The process's one card store, over the shared pimdir store
     * ({@link #pimdir}): one connection pool for every caller, a
     * sync adapter's included, rather than one left open per call.
     */
    synchronized CardStore cards() {
        if (cards == null) {
            cards = new CardStore(this, pimdir());
        }
        return cards;
    }

    @Override
    public Class<? extends JobService> backgroundJob() {
        return BackgroundJob.class;
    }

    @Override
    public Map<String, MergedFilter> filters() {
        return filters;
    }

    @Override
    public MailState mailState() {
        return mailState;
    }

    @Override
    public PhoneQueue phoneQueue() {
        return phoneQueue;
    }

    /** Starts the process's queue, once. */
    synchronized void startPhoneQueue() {
        if (phoneQueue != null) {
            return;
        }
        PimdirDb pimdir = pimdir();
        phoneQueue =
                new PhoneQueue(
                        new Handler(Looper.getMainLooper()),
                        Executors.newSingleThreadExecutor(),
                        url -> {
                            try {
                                new ContactPhonePass(pimdir, Clients.real())
                                        .sync(url, new ContactEngine.Report());
                            } catch (Exception error) {
                                Log.w("pimalaya", "phone pass failed for " + url, error);
                            }
                        },
                        collection -> CalendarPhonePass.pass(pimdir, collection));
    }
}
