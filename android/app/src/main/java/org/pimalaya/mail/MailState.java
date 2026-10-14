package org.pimalaya.mail;

import android.content.Context;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the process keeps of mail between screens and runs: the bodies a
 * run gave up on and the list listening to the engine's writes.
 */
public final class MailState {
    /** The process holding the state, which the app is. */
    public interface Owner {
        /** The process's mail state. */
        MailState mailState();
    }

    /**
     * The messages whose body failed every attempt of a run, by
     * {@link MailOwed#key}: their row says so rather than dimming silently,
     * and no run plans them again until their mailbox is listed anew
     * ({@link #relisted}), so a run never starts for them alone. Kept for
     * the process, so the next app start tries them again.
     */
    final Set<String> failed = ConcurrentHashMap.newKeySet();

    /**
     * The messages the server answered gone when their body was asked for
     * (deleted since they were listed), by {@link MailOwed#key}: owed no
     * more, and planned by no run until their mailbox is listed anew
     * ({@link #relisted}), whose delta takes them away. Kept for the
     * process.
     */
    final Set<String> gone = ConcurrentHashMap.newKeySet();

    /**
     * Told after every write a mail driver's pass lands, so a list showing
     * the store redraws as the pages of a first pass land; null when
     * nothing is listening. Called on the sync thread.
     */
    volatile Runnable onWrite;

    /** The process's mail state, held by the application of a context. */
    public static MailState of(Context context) {
        return ((Owner) context.getApplicationContext()).mailState();
    }

    /**
     * Forgets what {@link #failed} and {@link #gone} hold of one mailbox,
     * just listed: what is still there and still without its body is
     * planned again.
     */
    void relisted(String collection) {
        String prefix = MailOwed.key(collection, "");
        failed.removeIf(key -> key.startsWith(prefix));
        gone.removeIf(key -> key.startsWith(prefix));
    }
}
