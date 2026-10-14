package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.GraphStepReply;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.MailWire;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.NamedStep;
import org.pimalaya.client.NamedStepItem;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.OfflineDriver;
import org.pimalaya.client.StepRequest;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What a step asks the bridge on each backend, and what a step item lands as.
 *
 * <p>Gmail, JMAP, Graph and IMAP requests over six frontier, anchor, bound and
 * kept-whole states, account-wide and per mailbox, captured as JSON on the old
 * code; {@code BOUND} stands for the moving three-month bound.
 */
@RunWith(RobolectricTestRunner.class)
public class StepRequestTest {
    /** Each step asked: its label, the mailbox beside it, the request. */
    private static final List<String> ASKED =
            List.of(
                    "gmail s0 only=- | null | {\"count\":50,\"frontier\":null,\"bound\":null,\"anchors\":[],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\",\"TRASH\"]}",
                    "gmail s0 only=Clients | Clients | {\"count\":50,\"frontier\":null,\"bound\":null,\"anchors\":[]}",
                    "gmail s1 only=- | null | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":null,\"anchors\":[],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\",\"TRASH\"]}",
                    "gmail s1 only=Clients | Clients | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":null,\"anchors\":[]}",
                    "gmail s2 only=- | null | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":null,\"anchors\":[\"a2\",\"a1\"],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\",\"TRASH\"]}",
                    "gmail s2 only=Clients | Clients | {\"count\":50,\"frontier\":\"2098-06-01T00:00:00Z\",\"bound\":null,\"anchors\":[\"c1\"]}",
                    "gmail s3 only=- | null | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"a2\",\"a1\"],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\",\"TRASH\"]}",
                    "gmail s3 only=Clients | Clients | {\"count\":50,\"frontier\":\"2098-06-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"c1\"]}",
                    "gmail s4 only=- | null | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"a2\",\"a1\"],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\",\"TRASH\"]}",
                    "gmail s4 only=Clients | Clients | {\"count\":50,\"frontier\":\"2098-06-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"c1\"]}",
                    "gmail s5 only=- | null | {\"count\":50,\"frontier\":\"BOUND\",\"bound\":\"BOUND\",\"anchors\":[\"a2\",\"a1\"],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\",\"TRASH\"]}",
                    "gmail s5 only=Clients | Clients | {\"count\":50,\"frontier\":\"BOUND\",\"bound\":\"BOUND\",\"anchors\":[\"c1\"]}",
                    "jmap s0 only=- | null | {\"count\":50,\"frontier\":null,\"bound\":null,\"anchors\":[],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\",\"TRASH\"]}",
                    "jmap s0 only=Clients | Clients | {\"count\":50,\"frontier\":null,\"bound\":null,\"anchors\":[]}",
                    "jmap s1 only=- | null | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":null,\"anchors\":[],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\",\"TRASH\"]}",
                    "jmap s1 only=Clients | Clients | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":null,\"anchors\":[]}",
                    "jmap s2 only=- | null | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":null,\"anchors\":[\"a2\",\"a1\"],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\",\"TRASH\"]}",
                    "jmap s2 only=Clients | Clients | {\"count\":50,\"frontier\":\"2098-06-01T00:00:00Z\",\"bound\":null,\"anchors\":[\"c1\"]}",
                    "jmap s3 only=- | null | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"a2\",\"a1\"],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\",\"TRASH\"]}",
                    "jmap s3 only=Clients | Clients | {\"count\":50,\"frontier\":\"2098-06-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"c1\"]}",
                    "jmap s4 only=- | null | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"a2\",\"a1\"],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\",\"TRASH\"]}",
                    "jmap s4 only=Clients | Clients | {\"count\":50,\"frontier\":\"2098-06-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"c1\"]}",
                    "jmap s5 only=- | null | {\"count\":50,\"frontier\":\"BOUND\",\"bound\":\"BOUND\",\"anchors\":[\"a2\",\"a1\"],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\",\"TRASH\"]}",
                    "jmap s5 only=Clients | Clients | {\"count\":50,\"frontier\":\"2098-06-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"c1\"]}",
                    "graph s0 only=- | null | {\"count\":50,\"frontier\":null,\"bound\":null,\"anchors\":[],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\"]}",
                    "graph s0 only=Clients | Clients | {\"count\":50,\"frontier\":null,\"bound\":null,\"anchors\":[],\"mailboxes\":[\"Clients\"]}",
                    "graph s1 only=- | null | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":null,\"anchors\":[],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\"]}",
                    "graph s1 only=Clients | Clients | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":null,\"anchors\":[],\"mailboxes\":[\"Clients\"]}",
                    "graph s2 only=- | null | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":null,\"anchors\":[\"a2\",\"a1\"],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\"]}",
                    "graph s2 only=Clients | Clients | {\"count\":50,\"frontier\":\"2098-06-01T00:00:00Z\",\"bound\":null,\"anchors\":[\"c1\"],\"mailboxes\":[\"Clients\"]}",
                    "graph s3 only=- | null | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"a2\",\"a1\"],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\"]}",
                    "graph s3 only=Clients | Clients | {\"count\":50,\"frontier\":\"2098-06-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"c1\"],\"mailboxes\":[\"Clients\"]}",
                    "graph s4 only=- | null | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"a2\",\"a1\"],\"mailboxes\":[\"INBOX\",\"Sent\"]}",
                    "graph s5 only=- | null | {\"count\":50,\"frontier\":\"BOUND\",\"bound\":\"BOUND\",\"anchors\":[\"a2\",\"a1\"],\"mailboxes\":[\"INBOX\",\"Sent\",\"Clients\"]}",
                    "graph s5 only=Clients | Clients | {\"count\":50,\"frontier\":\"2098-06-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"c1\"],\"mailboxes\":[\"Clients\"]}",
                    "imap s0 only=- | direct | {\"count\":50,\"frontier\":null,\"bound\":null,\"anchors\":[]}",
                    "imap s0 only=Clients | direct | {\"count\":50,\"frontier\":null,\"bound\":null,\"anchors\":[]}",
                    "imap s1 only=- | direct | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":null,\"anchors\":[]}",
                    "imap s1 only=Clients | direct | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":null,\"anchors\":[]}",
                    "imap s2 only=- | direct | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":null,\"anchors\":[\"a2\",\"a1\"]}",
                    "imap s2 only=Clients | direct | {\"count\":50,\"frontier\":\"2098-06-01T00:00:00Z\",\"bound\":null,\"anchors\":[\"c1\"]}",
                    "imap s3 only=- | direct | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"a2\",\"a1\"]}",
                    "imap s3 only=Clients | direct | {\"count\":50,\"frontier\":\"2098-06-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"c1\"]}",
                    "imap s4 only=- | direct | {\"count\":50,\"frontier\":\"2099-01-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"a2\",\"a1\"]}",
                    "imap s4 only=Clients | direct | {\"count\":50,\"frontier\":\"2098-06-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"c1\"]}",
                    "imap s5 only=- | direct | {\"count\":50,\"frontier\":\"BOUND\",\"bound\":\"BOUND\",\"anchors\":[\"a2\",\"a1\"]}",
                    "imap s5 only=Clients | direct | {\"count\":50,\"frontier\":\"2098-06-01T00:00:00Z\",\"bound\":\"BOUND\",\"anchors\":[\"c1\"]}"
);

    /** An account whose steps bring nothing, recording what they ask. */
    private static final class Server extends MailClient {
        final List<StepRequest> asked = new ArrayList<>();

        @Override
        public NamedStep step(
                MailSession session, StepRequest request, File dir, OfflineDriver store) {
            asked.add(request);
            return new NamedStep(null, false, false, "h", List.of());
        }

        @Override
        public GraphStepReply graphStep(
                MailSession session, StepRequest request, File dir, OfflineDriver store) {
            asked.add(request);
            return new GraphStepReply(false, List.of());
        }
    }

    @Test
    public void everyBackendAsksAsBefore() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PimdirDb pimdir = new PimdirDb(context);
        MailStore store = new MailStore(context, pimdir);
        List<String> asked = new ArrayList<>();
        List<StepRequest> requests = new ArrayList<>();
        List<String> bounds = new ArrayList<>();
        for (String kind : new String[] {"gmail", "jmap", "graph", "imap"}) {
            String email = "jane@" + kind + ".example";
            List<Mailbox> boxes =
                    List.of(
                            new Mailbox("INBOX", "inbox"),
                            new Mailbox("Clients", ""),
                            new Mailbox("Sent", "sent"),
                            new Mailbox("TRASH", "trash"));
            store.replaceMailboxes(email, boxes);
            String accountId = new PimdirAccount(context).idOf(email);
            if (kind.equals("gmail")) {
                MailPolicy.markAccountWide(context, accountId, true);
                store.ensureAllMail(email);
            }
            List<String> collections = new ArrayList<>();
            for (Mailbox mailbox : MailEngine.ordered(boxes)) {
                collections.add(store.collectionOf(email, mailbox.name()));
            }
            if (kind.equals("gmail")) {
                collections.add(MailStore.allMailOf(accountId));
            }
            String clients = store.collectionOf(email, "Clients");
            for (int state = 0; state < 6; state++) {
                if (state == 1) {
                    MailWindow.set(context, accountId, "2099-01-01T00:00:00Z");
                } else if (state == 2) {
                    MailWindow.moveBackMailbox(context, clients, "2098-06-01T00:00:00Z");
                    MailWindow.anchor(context, accountId, null, List.of("a2", "a1"));
                    MailWindow.anchor(context, accountId, "Clients", List.of("c1"));
                } else if (state == 3) {
                    MailScope.set(context, accountId, 3);
                } else if (state == 4) {
                    MailOffline.setWhole(context, clients, true);
                } else if (state == 5) {
                    MailOffline.setWhole(context, clients, false);
                    MailWindow.set(context, accountId, null);
                }
                for (String only : new String[] {null, clients}) {
                    String label =
                            kind + " s" + state + " only=" + (only == null ? "-" : "Clients");
                    Server server = new Server();
                    MailEngine engine =
                            new MailEngine(
                                    pimdir,
                                    new Clients(
                                            new AccountClient(), new ContactClient(),
                                            new CalendarClient(), server, new OfflineClient()),
                                    null,
                                    accountId);
                    if (kind.equals("imap")) {
                        server.asked.add(engine.stepRequest(only, null));
                    } else if (kind.equals("graph")) {
                        new GraphStep().run(engine, new StepRun(email, collections, only, false));
                    } else {
                        new GmailStep().run(engine, new StepRun(email, collections, only, false));
                    }
                    for (StepRequest request : server.asked) {
                        asked.add(label);
                        requests.add(request);
                        bounds.add(MailScope.sinceOf(context, accountId));
                    }
                }
            }
        }

        assertEquals(ASKED.size(), requests.size());
        for (int index = 0; index < ASKED.size(); index++) {
            String[] expected = ASKED.get(index).split(" \\| ");
            StepRequest request = requests.get(index);
            assertEquals(expected[0], asked.get(index));
            String mailbox = expected[1];
            if (mailbox.equals("direct")) {
                mailbox = expected[0].endsWith("only=-") ? "null" : "Clients";
            }
            assertEquals(expected[0], mailbox, String.valueOf(request.mailbox()));
            String bound = bounds.get(index);
            MailWire.assertSameJson(
                    bound == null ? expected[2] : expected[2].replace("BOUND", bound), request);
        }
    }

    @Test
    public void aStepItemLandsAsTheListingNamesIt() throws Exception {
        NamedStep step =
                MailWire.namedStep(
                        "{\"byReception\":true,\"exhausted\":false,\"historyId\":\"h\",\"items\":["
                                + "{\"flags\":[\"\\\\Seen\",\"$label\"],\"handle\":\"m1\","
                                + "\"linkId\":\"m1\",\"mailboxes\":[\"INBOX\"],\"path\":\"/d/m1\","
                                + "\"received\":1767690000000,\"size\":120,"
                                + "\"sortKey\":\"2026-01-06T08:30:00Z\","
                                + "\"summary\":{\"subject\":\"Lunch\"}},"
                                + "{\"flags\":[],\"handle\":\"m2\",\"linkId\":\"m2\","
                                + "\"mailboxes\":[],\"path\":\"/d/m2\",\"received\":null,"
                                + "\"size\":0}]}");
        NamedStepItem full = step.items().get(0);
        JSONObject named = MailEngine.wireItem(full);
        MailWire.assertSameJson(
                "{\"handle\":\"m1\",\"flags\":[\"\\\\Seen\",\"$label\"],"
                        + "\"linkId\":\"m1\",\"summary\":{\"subject\":\"Lunch\"},"
                        + "\"sortKey\":\"2026-01-06T08:30:00Z\"}",
                named);
        JSONObject bare = MailEngine.wireItem(step.items().get(1));
        MailWire.assertSameJson("{\"handle\":\"m2\",\"flags\":[],\"linkId\":\"m2\"}", bare);
    }
}
