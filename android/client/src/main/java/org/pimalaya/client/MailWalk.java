package org.pimalaya.client;

import java.util.List;

/**
 * One walk of an account's mail: the mailboxes it holds and the newest
 * messages of each.
 *
 * <p>One authentication answers both, which is why they cross together:
 * the roster alone would be a second connection for two attributes.
 */
public final class MailWalk {
    public final List<Mailbox> mailboxes;
    public final List<Message> messages;

    public MailWalk(List<Mailbox> mailboxes, List<Message> messages) {
        this.mailboxes = mailboxes;
        this.messages = messages;
    }
}
