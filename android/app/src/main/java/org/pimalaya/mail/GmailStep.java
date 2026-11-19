package org.pimalaya.mail;

import android.util.Log;

import org.pimalaya.client.Named;
import org.pimalaya.client.NamedStep;
import org.pimalaya.client.NamedStepItem;
import org.pimalaya.client.StepRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A Gmail account's step ({@link MailStep}): the ids it lacks.
 *
 * <p>Newest received first (spam and trash out), read whole in batches, each
 * in every labelled mailbox and All Mail: a set difference, so misdated
 * imports do not throw it off.
 */
final class GmailStep implements MailStep {
    @Override
    public Outcome run(MailEngine engine, StepRun run) {
        List<String> collections = run.collections();
        String only = run.only();
        boolean first = run.first();

        // NOTE: the bridge reads the request's count alone, Gmail listing
        // All Mail whatever; the names, frontier and anchors are what the
        // JMAP step read before it went.
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
            // NOTE: a Gmail message names All Mail among its mailboxes, an
            // archived one it alone, so a step holding by it finds it held
            // next time and does not read it again
            // (PimdirCollections.ALL_MAIL). A name the step does not take
            // but the store holds is left be; one the store does not hold
            // is reported, never dropped silently.
            String allMail = MailStore.allMailOf(engine.accountId());
            Set<String> held = new HashSet<>(engine.accountCollections());
            Set<String> unresolved = new TreeSet<>();
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
                for (String mailbox : item.mailboxes()) {
                    String collection = engine.collectionOf(mailbox);
                    List<Named> into = members.get(collection);
                    if (into != null) {
                        into.add(MailEngine.named(item));
                    } else if (!held.contains(collection)) {
                        unresolved.add(mailbox);
                    }
                }
            }
            if (!unresolved.isEmpty()) {
                Log.w(
                        "pimalaya",
                        "mail step: " + unresolved.size() + " mailboxes the store does not hold "
                                + unresolved);
            }

            // NOTE: no step fills the trash or the junk: they open by a round.
            String checkpoint = step.historyId();
            Map<String, String> roles = engine.mail().roles();
            for (Map.Entry<String, List<Named>> mailbox : members.entrySet()) {
                boolean opens = first && MailBodies.downloads(roles.get(mailbox.getKey()), false);
                if (opens || !mailbox.getValue().isEmpty()) {
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
                    items.size(),
                    unresolved.size());
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
