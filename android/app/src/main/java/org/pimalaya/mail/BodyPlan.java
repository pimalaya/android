package org.pimalaya.mail;

import android.content.Context;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.pimalaya.account.AccountActivation;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.pimdir.PimdirCollections;

/**
 * The body step's plan: which bodies each account downloads this run, out
 * of the store, the mail filter and the offline choices (the coordinator
 * in sync runs it, then counts what it planned).
 */
public final class BodyPlan {
    private BodyPlan() {}

    /**
     * What a plan answers: the run, the bodies each planned account has
     * left (zero for one planning none), and their sum.
     */
    public record Plan(MailBodies.Run run, Map<String, Integer> left, int planned) {}

    /**
     * What the body step downloads this run, by account: every account that
     * is on, its mailboxes' messages dated from their frontier on but in the
     * junk and the trash, every message of a mailbox kept whole, and what is
     * owed below its frontier ({@link MailOwed}); the mailboxes the mail
     * list shows first, each part newest first, the owed ones last; none
     * failed or gone since its mailbox was last listed
     * ({@link MailBodies.Run#plan}). Walked on the io thread.
     */
    public static Plan of(
            Context context, MailStore mail, List<AccountEntry> accounts, MailState state) {
        MailBodies.Run run = new MailBodies.Run(state);
        Map<String, Integer> left = new LinkedHashMap<>();
        int planned = 0;
        // NOTE: read afresh from what was kept, off the main thread.
        MergedFilter shown = MergedFilter.of(context, PimDomain.MAIL);
        for (AccountEntry account : accounts) {
            String email = account.email;
            if (!AccountActivation.enabled(context, email)) {
                continue;
            }
            List<String> windowedFirst = new ArrayList<>();
            List<String> windowedThen = new ArrayList<>();
            List<String> wholeFirst = new ArrayList<>();
            List<String> wholeThen = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (PimdirCollections.Stored stored : mail.loadMailboxes(List.of(email))) {
                boolean whole = MailOffline.whole(context, stored.id);
                if (!MailBodies.downloads(stored.role, whole)) {
                    skipped.add(stored.id);
                    continue;
                }
                // NOTE: a mailbox the filter unticks syncs nothing more, its
                // bodies included; what it owes waits for it to be ticked.
                if (!shown.chosen(stored.id)) {
                    continue;
                }
                boolean first = shown.accepts(email, stored.id);
                List<String> into =
                        whole
                                ? (first ? wholeFirst : wholeThen)
                                : (first ? windowedFirst : windowedThen);
                into.add(stored.id);
            }
            List<MailBodies.Row> wanted =
                    new ArrayList<>(
                            MailBodies.merged(
                                    windowedRows(mail, windowedFirst),
                                    MailBodies.wanted(mail.bodyRows(wholeFirst, null))));
            wanted.addAll(
                    MailBodies.merged(
                            windowedRows(mail, windowedThen),
                            MailBodies.wanted(mail.bodyRows(wholeThen, null))));
            List<String> windowed = new ArrayList<>(windowedFirst);
            windowed.addAll(windowedThen);
            wanted.addAll(owed(context, mail, windowed, skipped));
            int taken = run.plan(email, wanted);
            planned += taken;
            left.put(email, taken);
        }
        return new Plan(run, left, planned);
    }

    /**
     * The bodies some mailboxes want from their frontier on, newest first:
     * a mailbox stepped alone keeps a frontier older than its account's
     * ({@link MailWindow#since(android.content.Context, String, String)}).
     */
    private static List<MailBodies.Row> windowedRows(MailStore mail, List<String> collections) {
        Map<String, List<String>> bySince = new HashMap<>();
        for (String collection : collections) {
            bySince.computeIfAbsent(mail.mailboxWindow(collection), since -> new ArrayList<>())
                    .add(collection);
        }
        List<MailBodies.Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<String>> since : bySince.entrySet()) {
            rows =
                    MailBodies.merged(
                            rows,
                            MailBodies.wanted(mail.bodyRows(since.getValue(), since.getKey())));
        }
        return rows;
    }

    /**
     * The owed bodies of some mailboxes dated below their frontier, which
     * download all the same: one dated at or after it is in the plan
     * already. What is owed in a mailbox that downloads nothing
     * ({@code skipped}: the junk, the trash), or by a message gone or
     * downloaded since, is owed no more.
     */
    private static List<MailBodies.Row> owed(
            Context context, MailStore mail, List<String> windowed, List<String> skipped) {
        List<MailBodies.Row> rows = new ArrayList<>();
        List<String> settled = new ArrayList<>();
        for (String key : MailOwed.all(context)) {
            String collection = MailOwed.collectionOf(key);
            if (skipped.contains(collection)) {
                settled.add(key);
                continue;
            }
            if (!windowed.contains(collection)) {
                continue;
            }
            MailStore.StoredMessage message = mail.message(collection, MailOwed.linkIdOf(key));
            String window = mail.mailboxWindow(collection);
            if (message == null || message.bodied) {
                settled.add(key);
            } else if (window != null && message.sortKey.compareTo(window) < 0) {
                rows.add(
                        new MailBodies.Row(
                                collection,
                                message.id,
                                message.sortKey,
                                false,
                                message.size,
                                message.attachment,
                                true));
            }
        }
        MailOwed.settle(context, settled);
        return rows;
    }
}
