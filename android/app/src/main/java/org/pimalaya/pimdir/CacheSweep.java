package org.pimalaya.pimdir;

import android.content.Context;

import java.io.File;
import java.util.concurrent.TimeUnit;
import org.pimalaya.MailEngine;
import org.pimalaya.file.FilesList;

/**
 * Removes what a run killed midway left in the cache: the folders a body
 * download writes messages into ({@link MailEngine#scratch}) and the copy
 * an import from the phone's storage stages, each the plaintext of a
 * message or a document, which nothing else would ever delete. On every
 * start, only what is older than any run could be ({@link #AGE}), so a
 * background run writing beside the app keeps its own.
 */
public final class CacheSweep {
    private CacheSweep() {}

    /** How old a leftover is before it is removed. */
    public static final long AGE = TimeUnit.HOURS.toMillis(1);

    /** Removes the leftovers older than {@link #AGE} at {@code now}. */
    public static void run(Context context, long now) {
        File cache = context.getCacheDir();
        File[] sources = new File(cache, MailEngine.SCRATCH).listFiles();
        for (int index = 0; sources != null && index < sources.length; index++) {
            if (now - sources[index].lastModified() > AGE) {
                delete(sources[index]);
            }
        }
        File[] staged =
                cache.listFiles(
                        (directory, name) ->
                                name.startsWith(FilesList.STAGED) && name.endsWith(".tmp"));
        for (int index = 0; staged != null && index < staged.length; index++) {
            if (now - staged[index].lastModified() > AGE) {
                staged[index].delete();
            }
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        for (int index = 0; children != null && index < children.length; index++) {
            delete(children[index]);
        }
        file.delete();
    }
}
