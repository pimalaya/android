package org.pimalaya.mail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An IMAP account's step ({@link MailStep}): widened from the store, streamed.
 *
 * <p>The first lists every mailbox from the {@link #COUNT}th newest date
 * (UID SORT or samples, so imports do not drag it); later ones widen as
 * {@link WidenStep}; {@link MailEngine#download} reads the bodies between.
 */
final class ImapStep implements MailStep {
    @Override
    public Outcome run(MailEngine engine, StepRun run) throws Exception {
        String accountEmail = run.accountEmail();
        List<String> collections = run.collections();
        String only = run.only();
        boolean first = run.first();

        String before =
                first
                        ? null
                        : only == null
                                ? MailWindow.since(engine.context(), engine.accountId())
                                : MailWindow.since(engine.context(), engine.accountId(), only);
        Outcome outcome =
                first
                        ? opening(engine, collections)
                        : new WidenStep()
                                .run(engine, new StepRun(accountEmail, collections, only, false));
        if (!first && outcome.frontier == null) {
            return outcome;
        }
        int brought = download(engine, stepped(engine, collections, only), outcome.frontier, before);
        return new Outcome(outcome.frontier, outcome.exhausted, first ? brought : outcome.brought);
    }

    /**
     * The first step's listing: every mailbox from the account's floor.
     *
     * <p>Each one's {@link #COUNT} newest read ({@link MailEngine#newest}); the
     * frontier is the floor, all mail when they hold one step in all. A refused
     * mailbox is left out ({@link MailEngine#skip}) unless every one is.
     */
    private static Outcome opening(MailEngine engine, List<String> collections) {
        List<String> dates = new ArrayList<>();
        boolean more = false;
        List<String> stepped = stepped(engine, collections, null);
        RuntimeException refusal = null;
        int read = 0;
        for (String collection : stepped) {
            List<String> newest;
            try {
                newest = engine.newest(collection, COUNT);
                read++;
            } catch (RuntimeException failure) {
                MailEngine.skip(collection, failure);
                refusal = refusal == null ? failure : refusal;
                continue;
            }
            more |= newest.size() >= COUNT;
            dates.addAll(newest);
        }
        if (refusal != null && read == 0) {
            throw refusal;
        }
        dates.sort(Comparator.reverseOrder());
        String floor = more || dates.size() > COUNT ? dates.get(COUNT - 1) : null;
        int opened = 0;
        refusal = null;
        for (String collection : collections) {
            try {
                engine.syncSince(collection, floor);
                opened++;
            } catch (RuntimeException failure) {
                MailEngine.skip(collection, failure);
                refusal = refusal == null ? failure : refusal;
            }
        }
        if (refusal != null && opened == 0) {
            throw refusal;
        }
        return new Outcome(floor, floor == null, 0);
    }

    /**
     * The mailboxes a step brings bodies to: {@code only} alone when given.
     *
     * <p>Never junk, trash, or one kept whole (the body step's).
     */
    private static List<String> stepped(
            MailEngine engine, List<String> collections, String only) {
        Map<String, String> roles = engine.mail().roles();
        List<String> stepped = new ArrayList<>();
        for (String collection : collections) {
            if ((only == null || only.equals(collection))
                    && !MailOffline.whole(engine.context(), collection)
                    && MailBodies.downloads(roles.get(collection), false)) {
                stepped.add(collection);
            }
        }
        return stepped;
    }

    /**
     * Downloads the lacking bodies of {@code stepped} from {@code since}.
     *
     * <p>Up to below {@code until} (nulls unbounded), one mailbox after
     * another, planned on the tally first ({@link MailEngine#tally}); answers
     * how many messages lie there.
     */
    private static int download(
            MailEngine engine, List<String> stepped, String since, String until) {
        Map<String, List<MailBodies.Row>> wanted = new LinkedHashMap<>();
        int within = 0;
        for (MailBodies.Row row : engine.mail().bodyRows(stepped, since)) {
            if (until != null && !row.sortKey.isEmpty() && row.sortKey.compareTo(until) >= 0) {
                continue;
            }
            within++;
            if (!row.bodied) {
                wanted.computeIfAbsent(row.collection, collection -> new ArrayList<>())
                        .add(
                                new MailBodies.Row(
                                        row.collection,
                                        row.linkId,
                                        row.sortKey,
                                        false,
                                        row.size,
                                        row.attachment,
                                        true));
            }
        }
        if (engine.tally != null) {
            int planned = 0;
            for (List<MailBodies.Row> rows : wanted.values()) {
                planned += rows.size();
            }
            engine.tally.expect(planned);
        }
        for (Map.Entry<String, List<MailBodies.Row>> mailbox : wanted.entrySet()) {
            engine.download(mailbox.getKey(), mailbox.getValue());
        }
        return within;
    }
}
