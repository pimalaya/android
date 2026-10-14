package org.pimalaya.contact;

import java.util.ArrayList;
import java.util.List;

/**
 * One merged row: the replicas of a logical contact, by vCard UID.
 *
 * <p>Grouped by the bridge; a card without a UID stays a singleton.
 */
public final class Group {
    final String key;
    /** The replicas behind the row. */
    public final List<Entry> replicas = new ArrayList<>();

    Group(String key) {
        this.key = key;
    }

    Entry primary() {
        return replicas.get(0);
    }

    /** Whether any replica is a sync conflict awaiting the user. */
    public boolean conflicted() {
        for (Entry entry : replicas) {
            if (entry.conflicted) {
                return true;
            }
        }
        return false;
    }
}
