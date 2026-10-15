package org.pimalaya.mail;

import org.pimalaya.client.Named;
import org.pimalaya.client.NamedStep;
import org.pimalaya.client.NamedStepItem;
import org.pimalaya.client.StepRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Gmail (or JMAP) account's step ({@link MailStep}): the ids it lacks.
 *
 * <p>Newest received first (spam and trash out), read whole in batches, each
 * in every labelled mailbox: a set difference, so misdated imports do not
 * throw it off. JMAP queries after the {@link MailWindow#anchors}.
 */
final class GmailStep implements MailStep {
    @Override
    public Outcome run(MailEngine engine, StepRun run) {
        List<String> collections = run.collections();
        String only = run.only();
        boolean first = run.first();

        // NOTE: an account step names the mailboxes it takes, which a JMAP
        // account's query keeps to (Gmail lists All Mail whatever).
        List<String> names = null;
        if (only == null) {
            names = new ArrayList<>();
            for (String collection : collections) {
                if (!collection.equals(MailStore.allMailOf(engine.accountId()))) {
                    names.add(engine.mailboxOf(collection));
                }
            }
        }
        StepRequest request = engine.stepRequest(only, names);
        File dir = MailEngine.scratch(engine.context());
        try {
            NamedStep step = engine.stepRead(collections, request, dir);
            List<NamedStepItem> items = step.items();
            Map<String, List<Named>> members = new LinkedHashMap<>();
            for (String collection : collections) {
                members.put(collection, new ArrayList<>());
            }
            // NOTE: every message lands in All Mail where the step holds by
            // it, an archived one there alone, so the next step finds it
            // held and does not read it again (PimdirCollections.ALL_MAIL).
            String allMail = MailStore.allMailOf(engine.accountId());
            List<Named> all = members.get(allMail);
            Map<String, File> files = new HashMap<>();
            Map<String, Long> sizes = new HashMap<>();
            List<Brought> brought = new ArrayList<>();
            Long reach = null;
            for (NamedStepItem item : items) {
                String handle = item.handle();
                files.put(handle, new File(item.path()));
                sizes.put(handle, item.size());
                Long received = item.received();
                brought.add(new Brought(item.sortKey(), received));
                if (received != null && (reach == null || received < reach)) {
                    reach = received;
                }
                if (all != null) {
                    all.add(MailEngine.named(item));
                }
                for (String mailbox : item.mailboxes()) {
                    List<Named> into = members.get(engine.collectionOf(mailbox));
                    if (into != null) {
                        into.add(MailEngine.named(item));
                    }
                }
            }

            // NOTE: the first step opens every mailbox, the trash and the
            // junk included, so the next pass is a delta in each. A mailbox
            // keeps its members as they landed: a sent copy under its
            // create's link id, which its body downloads under.
            String checkpoint = step.historyId();
            for (Map.Entry<String, List<Named>> mailbox : members.entrySet()) {
                if (first || !mailbox.getValue().isEmpty()) {
                    mailbox.setValue(engine.land(mailbox.getKey(), mailbox.getValue(), checkpoint));
                }
            }
            engine.fetched(files);
            try {
                for (Map.Entry<String, List<Named>> mailbox : members.entrySet()) {
                    // NOTE: All Mail holds no body, being shown nowhere.
                    if (mailbox.getKey().equals(allMail)) {
                        continue;
                    }
                    List<MailBodies.Row> rows = rows(mailbox.getKey(), mailbox.getValue(), sizes);
                    if (rows.isEmpty()) {
                        continue;
                    }
                    // NOTE: a body that did not land is owed, so the body
                    // step brings it whatever its date.
                    List<String> owed = new ArrayList<>();
                    for (MailBodies.Row failed : engine.download(mailbox.getKey(), rows)) {
                        owed.add(failed.linkId);
                    }
                    MailOwed.owe(engine.context(), mailbox.getKey(), owed);
                }
            } finally {
                engine.fetched(Map.of());
            }

            if (reach != null) {
                MailWindow.reach(engine.context(), engine.accountId(), reach);
            }
            MailWindow.anchor(
                    engine.context(),
                    engine.accountId(),
                    only == null ? null : engine.mailboxOf(only),
                    step.anchors());
            return new Outcome(
                    MailWindow.frontier(brought, step.byReception()),
                    step.exhausted(),
                    items.size());
        } finally {
            MailEngine.discard(dir);
        }
    }

    /**
     * The bodies a mailbox's members download, whatever their date.
     *
     * <p>Under the link id they landed with, a sent copy's being its create's.
     */
    private static List<MailBodies.Row> rows(
            String collection, List<Named> members, Map<String, Long> sizes) {
        List<MailBodies.Row> rows = new ArrayList<>(members.size());
        for (Named member : members) {
            rows.add(
                    new MailBodies.Row(
                            collection,
                            member.linkId(),
                            member.sortKey(),
                            false,
                            sizes.get(member.handle()),
                            null,
                            true));
        }
        return rows;
    }
}
