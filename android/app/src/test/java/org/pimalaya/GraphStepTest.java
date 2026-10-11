package org.pimalaya;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Account;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaClient;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A Microsoft Graph step from the engine down to the store: the bridge's
 * account listing names the step's messages and reads their bodies, every
 * stepped folder is listed by band down to the oldest of them, and the
 * bodies are stored from the step's files; the junk and the trash take no
 * part.
 */
@RunWith(RobolectricTestRunner.class)
public class GraphStepTest {
    private static final String EMAIL = "jane@contoso.onmicrosoft.com";
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    private Context context;
    private PimdirDb pimdir;
    private MailStore store;
    private String accountId;
    private String inbox;
    private String sent;
    private String projets;
    private String trash;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(
                EMAIL,
                List.of(
                        new Mailbox("Inbox", "inbox"),
                        new Mailbox("Sent Items", "sent"),
                        new Mailbox("Projets", ""),
                        new Mailbox("Deleted Items", "trash")));
        accountId = new PimdirAccount(context).idOf(EMAIL);
        inbox = store.collectionOf(EMAIL, "Inbox");
        sent = store.collectionOf(EMAIL, "Sent Items");
        projets = store.collectionOf(EMAIL, "Projets");
        trash = store.collectionOf(EMAIL, "Deleted Items");
    }

    /** One message of the fake account: its id, folder, date and reception. */
    private static final class Mail {
        final String id;
        final String mailbox;
        final String date;
        final Instant received;

        Mail(String id, String mailbox, String date, Instant received) {
            this.id = id;
            this.mailbox = mailbox;
            this.date = date;
            this.received = received;
        }

        byte[] source() {
            return ("Date: " + date + "\r\nSubject: Mail " + id + "\r\n\r\nBody " + id + "\r\n")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    /** A Graph account holding {@code mail}, whose next step brings {@code next}. */
    private final class Server extends MailEngine {
        final List<Mail> mail = new ArrayList<>();
        List<Mail> next = List.of();
        boolean exhausted;
        final List<JSONObject> asked = new ArrayList<>();
        final List<String> banded = new ArrayList<>();
        final List<Set<String>> heldOf = new ArrayList<>();

        Server() {
            super(pimdir, new PimalayaClient(), null, accountId);
        }

        @Override
        protected JSONObject stepped(String mailbox, JSONObject request, File dir) {
            asked.add(request);
            try {
                List<String> listing = new ArrayList<>();
                for (Mail mail : this.mail) {
                    listing.add(mail.id);
                }
                Set<String> held = held(listing);
                heldOf.add(held);
                JSONArray items = new JSONArray();
                for (Mail mail : next) {
                    JSONObject item =
                            new JSONObject()
                                    .put("handle", mail.id)
                                    .put("mailboxes", new JSONArray().put(mail.mailbox))
                                    .put("sortKey", mail.date)
                                    .put("received", mail.received.toEpochMilli())
                                    .put("size", 99);
                    if (!held.contains(mail.id)) {
                        File file = new File(dir, mail.id + ".eml");
                        Files.write(file.toPath(), mail.source());
                        item.put("path", file.getAbsolutePath()).put("size", mail.source().length);
                    }
                    items.put(item);
                }
                return new JSONObject().put("exhausted", exhausted).put("items", items);
            } catch (JSONException | IOException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        protected JSONObject enumerate(JSONObject yielded) throws JSONException {
            String mailbox = mailboxOf(yielded.getString("collection"));
            JSONObject listing = yielded.getJSONObject("listing");
            JSONObject scope = yielded.optJSONObject("scope");
            String since = scope == null || scope.isNull("since") ? null : scope.optString("since");
            String until = scope == null || scope.isNull("until") ? null : scope.optString("until");
            JSONObject page = new JSONObject().put("vanished", new JSONArray()).put("last", true);
            if ("delta".equals(listing.getString("kind"))) {
                return page.put("items", new JSONArray())
                        .put("complete", false)
                        .put("checkpoint", "cp");
            }
            banded.add(mailbox);
            JSONArray items = new JSONArray();
            for (Mail mail : this.mail) {
                if (mail.mailbox.equals(mailbox)
                        && (since == null || mail.date.compareTo(since) >= 0)
                        && (until == null || mail.date.compareTo(until) < 0)) {
                    items.put(named(mail));
                }
            }
            page.put("items", items).put("complete", true);
            if (!listing.optBoolean("band")) {
                page.put("checkpoint", "cp");
            }
            return page;
        }

        @Override
        protected JSONObject push(JSONObject yielded) throws JSONException {
            return new JSONObject().put("results", new JSONArray());
        }
    }

    private static JSONObject named(Mail mail) throws JSONException {
        return new JSONObject()
                .put("handle", mail.id)
                .put("flags", new JSONArray())
                .put("linkId", mail.id)
                .put(
                        "summary",
                        PimdirSummary.mail(
                                null, "Mail " + mail.id, "", "a@example.org", null, mail.date,
                                0, false))
                .put("sortKey", PimdirSummary.mailSortKey(mail.date));
    }

    private List<String> mailboxes() {
        List<String> collections = new ArrayList<>();
        for (Mailbox mailbox :
                MailEngine.ordered(
                        List.of(
                                new Mailbox("Inbox", "inbox"),
                                new Mailbox("Sent Items", "sent"),
                                new Mailbox("Projets", ""),
                                new Mailbox("Deleted Items", "trash")))) {
            collections.add(store.collectionOf(EMAIL, mailbox.name));
        }
        return collections;
    }

    private long held(String collection) {
        long count = 0;
        MailStore.Query all = store.query((account, name) -> true, false, false, "");
        for (MailStore.StoredMessage message : store.all(all)) {
            if (message.collection.equals(collection)) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void aStepBandsEveryFolderDownToItsOldestAndStoresItsBodies() throws Exception {
        Server server = new Server();
        Mail newest = new Mail("i0", "Inbox", "2026-10-09T11:00:00Z", NOW);
        Mail reply = new Mail("s0", "Sent Items", "2026-10-09T10:00:00Z", NOW);
        Mail older = new Mail("i1", "Inbox", "2026-10-08T20:00:00Z", NOW);
        Mail imported = new Mail("i2", "Inbox", "2019-03-01T08:00:00Z", NOW);
        Mail binned = new Mail("t0", "Deleted Items", "2026-10-09T09:00:00Z", NOW);
        Mail oldest = new Mail("i3", "Inbox", "2018-01-01T08:00:00Z", NOW);
        server.mail.addAll(List.of(newest, reply, older, imported, binned, oldest));
        server.next = List.of(newest, reply, older, imported);

        MailStep.Outcome first = new GraphStep().run(server, EMAIL, mailboxes(), null, true);

        JSONObject request = server.asked.get(0);
        assertTrue(request.isNull("frontier"));
        assertEquals(
                "the trash takes no part",
                List.of("Inbox", "Sent Items", "Projets"),
                strings(request.getJSONArray("mailboxes")));
        assertEquals(
                "the step's folders first, then the rest",
                List.of("Inbox", "Sent Items", "Projets"),
                server.banded);
        assertEquals("down to the import, not below", 3, held(inbox));
        assertEquals(1, held(sent));
        assertEquals(0, held(trash));
        assertNull("the trash is left to the pass", store.coverage(trash).at);
        assertNotNull("an empty folder is opened", store.coverage(projets).at);
        assertEquals(imported.date, store.coverage(projets).since);
        for (Mail mail : List.of(newest, older, imported)) {
            assertArrayEquals(mail.source(), store.storedSource(inbox, mail.id));
        }
        assertArrayEquals(reply.source(), store.storedSource(sent, "s0"));
        assertEquals("the import is held, not the frontier", older.date, first.frontier);
        assertEquals(4, first.brought);
        assertFalse(first.exhausted);
    }

    @Test
    public void theNextStepAsksBelowTheFrontierAndMovesEveryFloor() throws Exception {
        Server server = new Server();
        Mail newest = new Mail("i0", "Inbox", "2026-10-09T11:00:00Z", NOW);
        Mail next = new Mail("i1", "Inbox", "2026-10-01T08:00:00Z", NOW);
        Mail reply = new Mail("s0", "Sent Items", "2026-09-20T08:00:00Z", NOW);
        server.mail.addAll(List.of(newest, next, reply));
        server.next = List.of(newest);
        MailStep.Outcome first = new GraphStep().run(server, EMAIL, mailboxes(), null, true);
        MailWindow.set(context, accountId, first.frontier);

        server.next = List.of(next, reply);
        server.banded.clear();
        MailStep.Outcome second = new GraphStep().run(server, EMAIL, mailboxes(), null, false);

        JSONObject request = server.asked.get(1);
        assertEquals(newest.date, request.getString("frontier"));
        assertEquals(Set.of("i0"), server.heldOf.get(1));
        assertEquals(2, held(inbox));
        assertEquals(1, held(sent));
        assertArrayEquals(reply.source(), store.storedSource(sent, "s0"));
        for (String collection : List.of(inbox, sent, projets)) {
            assertEquals(collection, reply.date, store.coverage(collection).since);
        }
        assertEquals(reply.date, second.frontier);
    }

    @Test
    public void anExhaustedStepListsEveryFolderWhole() throws Exception {
        Server server = new Server();
        Mail only = new Mail("i0", "Inbox", "2026-10-09T11:00:00Z", NOW);
        server.mail.add(only);
        server.next = List.of(only);
        server.exhausted = true;

        MailStep.Outcome first = new GraphStep().run(server, EMAIL, mailboxes(), null, true);

        assertTrue(first.exhausted);
        for (String collection : List.of(inbox, sent, projets)) {
            assertNotNull(collection, store.coverage(collection).at);
            assertNull(collection + " whole", store.coverage(collection).since);
        }
        assertArrayEquals(only.source(), store.storedSource(inbox, "i0"));
    }

    @Test
    public void aStepOverOneFolderMovesItsOwnFrontierAlone() throws Exception {
        Server server = new Server();
        Mail newest = new Mail("i0", "Inbox", "2026-10-09T11:00:00Z", NOW);
        Mail next = new Mail("i1", "Inbox", "2026-10-01T08:00:00Z", NOW);
        Mail last = new Mail("i2", "Inbox", "2026-09-01T08:00:00Z", NOW);
        server.mail.addAll(List.of(newest, next, last));
        MailWindow.set(context, accountId, newest.date);

        server.next = List.of(next);
        MailStep.Outcome first = new GraphStep().run(server, EMAIL, mailboxes(), inbox, false);
        MailWindow.stepped(context, accountId, inbox, first);
        server.next = List.of(last);
        new GraphStep().run(server, EMAIL, mailboxes(), inbox, false);

        assertEquals(List.of("Inbox"), strings(server.asked.get(0).getJSONArray("mailboxes")));
        assertEquals(newest.date, server.asked.get(0).getString("frontier"));
        assertEquals("below the folder's own", next.date, server.asked.get(1).getString("frontier"));
        assertEquals("the account's stays", newest.date, MailWindow.since(context, accountId));
    }

    @Test
    public void aGraphAccountStepsItself() {
        assertTrue(
                MailStep.of(new Account("msgraph://example", EMAIL, "token"))
                        instanceof GraphStep);
    }

    private static List<String> strings(JSONArray array) throws JSONException {
        List<String> strings = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            strings.add(array.getString(index));
        }
        return strings;
    }
}
