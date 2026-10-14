package org.pimalaya.mail;

import java.util.List;

/**
 * One step's scope, what {@link MailStep#run} steps.
 *
 * @param accountEmail the account stepped
 * @param collections its mailboxes in the order a pass takes them
 *     ({@link MailEngine#ordered})
 * @param only the one mailbox a view filtered to it steps, null for the
 *     account
 * @param first whether it is the first sync, which opens every mailbox
 */
public record StepRun(String accountEmail, List<String> collections, String only, boolean first) {}
