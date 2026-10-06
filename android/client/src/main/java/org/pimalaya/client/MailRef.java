package org.pimalaya.client;

import java.util.List;

/** One member of an enumerated mailbox: its UID and its markers. */
public final class MailRef {
    public final String id;
    public final List<String> flags;

    public MailRef(String id, List<String> flags) {
        this.id = id;
        this.flags = flags;
    }
}
