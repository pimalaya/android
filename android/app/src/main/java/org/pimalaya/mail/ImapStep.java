package org.pimalaya.mail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An IMAP account's step ({@link MailStep}): its mailboxes widened from
 * the store, then the bodies the step brought read in streamed batches,
 * one mailbox after another on the account's session.
 *
 * <p>The first step takes the account's floor, the {@code Date} of the
 * {@link #COUNT}th newest message by {@code Date} across the mailboxes it
 * brings bodies to (an order statistic of each one's newest, which the
 * bridge reads with {@code UID SORT} where the server has it and from a
 * sample of the newest UIDs elsewhere, so no imported message drags it
 * back), so it brings about {@link #COUNT} bodies; it lists every mailbox
 * from that floor, for the checkpoint each next pass is a delta from, and
 * leaves it as the frontier. A later step widens as {@link WidenStep}
 * does: the mailbox holding the most recent coverage floor by
 * {@link #COUNT} until about that many stored messages lie below the
 * frontier, the new frontier the {@code Date} of the {@link #COUNT}th of
 * them. Either way, the bodies between the new frontier and the old one
 * are read whole after it ({@link MailEngine#download}), the junk and the
 * trash left out.
 */
final class ImapStep implements MailStep {
    @Override
    public Outcome run(
            MailEngine engine,
            String accountEmail,
            List<String> collections,
            String only,
            boolean first)
            throws Exception {
        String before =
                first
                        ? null
                        : only == null
                                ? MailWindow.since(engine.context(), engine.accountId())
                                : MailWindow.since(engine.context(), engine.accountId(), only);
        Outcome outcome =
                first
                        ? opening(engine, collections)
                        : new WidenStep().run(engine, accountEmail, collections, only, false);
        if (!first && outcome.frontier == null) {
            return outcome;
        }
        int brought = download(engine, stepped(engine, collections, only), outcome.frontier, before);
        return new Outcome(outcome.frontier, outcome.exhausted, first ? brought : outcome.brought);
    }

    /**
     * The first step's listing: every mailbox from the account's floor,
     * the {@code Date} of the {@link #COUNT}th newest message across the
     * mailboxes it brings bodies to, each one's {@link #COUNT} newest read
     * ({@link MailEngine#newest}); its frontier that floor, all mail when
     * they hold no more than a step in all. A mailbox the server refuses
     * to open is left out ({@link MailEngine#skip}), unless every one is.
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
     * The mailboxes a step brings bodies to: {@code only} alone when given,
     * none of the junk, the trash, or one kept whole (its bodies are the
     * body step's).
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
     * Downloads the bodies the store lacks of the messages of
     * {@code stepped} dated from {@code since} (null for all) to below
     * {@code until} (null for no ceiling), whatever the frontier says yet,
     * one mailbox after another, planned on the engine's tally first
     * ({@link MailEngine#tally}); answers how many messages lie there.
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
