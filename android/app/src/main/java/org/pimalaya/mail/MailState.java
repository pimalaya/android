package org.pimalaya.mail;

import android.content.Context;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** What the process keeps of mail: failed bodies and the listening list. */
public final class MailState {
    /** The process holding the state, which the app is. */
    public interface Owner {
        /** The process's mail state. */
        MailState mailState();
    }

    /**
     * Messages whose body failed every attempt of a run, by owed key.
     *
     * <p>Their row says so, and no run plans them until their mailbox is listed
     * anew ({@link #relisted}). Kept for the process: the next start retries.
     */
    final Set<String> failed = ConcurrentHashMap.newKeySet();

    /**
     * Messages the server answered gone for their body, by owed key.
     *
     * <p>Owed no more, planned by no run until their mailbox is listed anew
     * ({@link #relisted}), whose delta removes them. Kept for the process.
     */
    final Set<String> gone = ConcurrentHashMap.newKeySet();

    /**
     * Told after every write a mail pass lands, null when nothing listens.
     *
     * <p>Called on the sync thread.
     */
    volatile Runnable onWrite;

    /** The process's mail state, held by the application of a context. */
    public static MailState of(Context context) {
        return ((Owner) context.getApplicationContext()).mailState();
    }

    /** Forgets a just-listed mailbox's {@link #failed} and {@link #gone}. */
    void relisted(String collection) {
        String prefix = MailOwed.key(collection, "");
        failed.removeIf(key -> key.startsWith(prefix));
        gone.removeIf(key -> key.startsWith(prefix));
    }
}
