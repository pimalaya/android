package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirQueue;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The submission the queue persists: Annex B.2's {@code submit}, strict.
 *
 * <p>The payload is the standard's alone; what the outbox lists, the {@code
 * Message-ID}, the subject and the date, is derived from the message.
 */
@RunWith(RobolectricTestRunner.class)
public class SubmissionTest {
    private static final String ONE = "jane@example.com";
    private static final String DATE = "Mon, 5 Jan 2026 09:00:00 +0000";

    /** The payload this app queued before draft-08: not Annex B.2's. */
    private static final String FORMER =
            "{\"v\":1,\"from\":\"jane@example.com\",\"messageId\":\"m1@example.org\","
                    + "\"subject\":\"Hello\",\"sortKey\":\"2026-01-05T09:00:00Z\"}";

    private PimdirDb pimdir;
    private MailStore store;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(ONE, List.of(new Mailbox("INBOX", "")));
    }

    private List<String> payloads() {
        return column("SELECT payload FROM queue ORDER BY id");
    }

    private List<String> column(String sql) {
        List<String> values = new ArrayList<>();
        try (Cursor cursor = pimdir.getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                values.add(cursor.isNull(0) ? null : cursor.getString(0));
            }
        }
        return values;
    }

    private void payload(long id, String text) {
        pimdir.getWritableDatabase()
                .execSQL("UPDATE queue SET payload = ? WHERE id = ?", new Object[] {text, id});
    }

    private List<String> listed() {
        List<String> rows = new ArrayList<>();
        for (MailStore.StoredMessage message : MailFixtures.merged(store, 10)) {
            rows.add(message.accountEmail + "|" + message.mailbox + "|" + message.id + "|"
                    + message.subject + "|" + message.fromAddress + "|" + message.stamp);
        }
        return rows;
    }

    @Test
    public void thePayloadIsAnnexB2sAndTheListingDerivesFromTheMessage() throws Exception {
        byte[] message = ("Date: " + DATE + "\r\n"
                + "Message-ID: <m1@example.org>\r\n"
                + "From: Jane <jane@example.com>\r\n"
                + "To: Bob <bob@example.org>, carol@example.org\r\n"
                + "Bcc: eve@example.org\r\n"
                + "Subject: =?UTF-8?B?UsOpdW5pb24=?=\r\n\r\nHi\r\n")
                .getBytes(StandardCharsets.UTF_8);
        store.outbox().queueSubmission(ONE, message);

        JSONObject payload = new JSONObject(payloads().get(0));
        assertEquals(1, payload.getInt("v"));
        assertEquals("server", payload.getString("source"));
        assertEquals(ONE, payload.getString("from"));
        assertEquals(
                List.of("bob@example.org", "carol@example.org", "eve@example.org"),
                strings(payload.getJSONArray("rcpts")));
        assertEquals("Réunion", payload.getString("subject"));
        assertEquals("nothing of the app's own", 5, payload.length());

        assertEquals(
                List.of("jane@example.com|Outbox|m1@example.org|Réunion|jane@example.com"
                        + "|1767603600000"),
                listed());
        Outbox.Outgoing waiting = store.outbox().outgoing(ONE).get(0);
        assertEquals("m1@example.org", waiting.messageId);
        assertEquals(payloads().get(0), waiting.payload);
        assertTrue(store.outbox().submitting("m1@example.org"));
    }

    @Test
    public void aMessageNamingNobodyIsNeverQueued() {
        byte[] nobody = "From: jane@example.com\r\nSubject: x\r\n\r\nHi\r\n"
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(PimalayaException.class, () -> store.outbox().queueSubmission(ONE, nobody));
        assertEquals(List.of(), payloads());
    }

    /** A producer never appends a payload its schema refuses (§15.3). */
    @Test
    public void aMalformedPayloadIsNeverQueued() {
        PimdirQueue queue =
                new PimdirQueue(pimdir, new PimdirAccount(RuntimeEnvironment.getApplication()));
        byte[] message = MailFixtures.outgoing(ONE, "m1@example.org", "Hello", DATE);

        assertThrows(
                PimalayaException.class,
                () -> queue.enqueue(
                        new PimdirQueue.Enqueued(
                                store.outbox().outboxOf(ONE),
                                ONE,
                                PimdirQueue.SUBMIT,
                                FORMER,
                                message)));
        assertEquals(List.of(), payloads());
    }

    /**
     * The drain reads a row strictly before handing anything over: a malformed
     * one is parked whole, never sent, and stays listed with why.
     */
    @Test
    public void theDrainParksAMalformedRowWhole() {
        MailFixtures.queue(store, ONE, "m1@example.org", "Hello", DATE);
        MailFixtures.queue(store, ONE, "m2@example.org", "Next", DATE);
        payload(1, FORMER);

        List<String> waiting = new ArrayList<>();
        for (Outbox.Outgoing outgoing : store.outbox().outgoing(ONE)) {
            waiting.add(outgoing.messageId);
        }
        assertEquals("the row behind it goes on", List.of("m2@example.org"), waiting);

        List<String> errors = column("SELECT error FROM queue ORDER BY id");
        assertTrue(errors.get(0), errors.get(0).contains("messageId"));
        assertNull(errors.get(1));
        assertEquals("still listed, as refused", 2, store.outbox().outgoing().size());
    }

    /**
     * A row stating a later payload version is a later program's: skipped,
     * left pending and untouched, never parked (§15.2).
     */
    @Test
    public void theDrainSkipsALaterPayloadVersion() {
        MailFixtures.queue(store, ONE, "m1@example.org", "Hello", DATE);
        payload(1, "{\"v\":2,\"source\":\"server\",\"route\":\"later\"}");

        assertEquals(List.of(), store.outbox().outgoing(ONE));
        assertEquals(java.util.Collections.singletonList(null),
                column("SELECT error FROM queue"));
        assertEquals(List.of("0"), column("SELECT attempts FROM queue"));
    }

    @Test
    public void aResentPayloadKeepsItsText() {
        MailFixtures.queue(store, ONE, "m1@example.org", "Hello", DATE);
        String text = payloads().get(0);
        store.outbox().parkUncertain(1, "eof");

        store.outbox().resendOutgoing(1);

        assertEquals(List.of(text), payloads());
        assertEquals("m1@example.org", store.outbox().outgoing(ONE).get(0).messageId);
    }

    /**
     * A submission sent again is its own row put back (pimdir STORAGE §15.5,
     * retry_action): its id and its attempts kept, in its place before a row
     * queued after it, where a new row was appended behind it.
     */
    @Test
    public void aResentSubmissionKeepsItsRowAndItsPlace() {
        MailFixtures.queue(store, ONE, "m1@example.org", "Hello", DATE);
        MailFixtures.queue(store, ONE, "m2@example.org", "Next", DATE);
        store.outbox().retryOutgoing(1);
        store.outbox().parkUncertain(1, "eof");

        store.outbox().resendOutgoing(1);

        assertEquals(List.of("1", "2"), column("SELECT id FROM queue ORDER BY id"));
        assertEquals(List.of("2", "0"), column("SELECT attempts FROM queue ORDER BY id"));
        assertEquals(java.util.Arrays.asList(null, null),
                column("SELECT error FROM queue ORDER BY id"));
        assertEquals("m1@example.org", store.outbox().outgoing(ONE).get(0).messageId);
    }

    /**
     * A refusal whose text begins with the reserved prefix is recorded behind
     * failed: (pimdir STORAGE §15.2), so no failure reads as an unknown
     * outcome.
     */
    @Test
    public void aRefusalNeverReadsAsAnUnknownOutcome() {
        MailFixtures.queue(store, ONE, "m1@example.org", "Hello", DATE);

        store.outbox().parkOutgoing(1, "unconfirmed: said the server");

        assertEquals(
                List.of("failed: unconfirmed: said the server"),
                column("SELECT error FROM queue"));
    }

    /**
     * An unknown outcome is the statement's to record (pimdir STORAGE §15.2,
     * unconfirm_action): it writes the reserved prefix and counts the attempt
     * for an intent, and writes nothing over an action the owner applies
     * itself, nor for a row that is gone.
     */
    @Test
    public void anUnknownOutcomeIsRecordedForAnIntentAlone() {
        PimdirQueue queue =
                new PimdirQueue(pimdir, new PimdirAccount(RuntimeEnvironment.getApplication()));
        MailFixtures.queue(store, ONE, "m1@example.org", "Hello", DATE);
        long applied =
                queue.enqueue(
                        new PimdirQueue.Enqueued(
                                store.outbox().outboxOf(ONE),
                                ONE,
                                "remove",
                                "{\"v\":1,\"seq\":3}",
                                null));

        boolean intent = queue.unconfirm(1, "eof");
        boolean owned = queue.unconfirm(applied, "eof");
        boolean gone = queue.unconfirm(99, "eof");

        assertEquals(
                java.util.Arrays.asList("unconfirmed: eof", null),
                column("SELECT error FROM queue ORDER BY id"));
        assertEquals(List.of("1", "0"), column("SELECT attempts FROM queue ORDER BY id"));
        assertTrue(intent);
        assertEquals("an owner's action", false, owned);
        assertEquals("no such row", false, gone);
    }

    /** A hand-over the app died in is parked, never handed over again. */
    @Test
    public void aHandOverTheAppDiedInIsParkedUncertain() {
        MailFixtures.queue(store, ONE, "m1@example.org", "Hello", DATE);
        MailFixtures.queue(store, ONE, "m2@example.org", "Next", DATE);
        store.outbox().handing(store.outbox().outgoing(ONE).get(0).id);

        Context context = RuntimeEnvironment.getApplication();
        MailStore restarted = new MailStore(context, new PimdirDb(context));
        List<String> waiting = new ArrayList<>();
        for (Outbox.Outgoing outgoing : restarted.outbox().outgoing(ONE)) {
            waiting.add(outgoing.messageId);
        }

        assertEquals(List.of("m2@example.org"), waiting);
        // NOTE: the reserved prefix of an intent whose outcome is unknown
        // (pimdir STORAGE §15.2), spelled here as the standard spells it.
        assertTrue(column("SELECT error FROM queue ORDER BY id").get(0).startsWith("unconfirmed: "));
    }

    /** A hand-over with a verdict leaves nothing to park. */
    @Test
    public void aHandOverWithAVerdictIsNotParked() {
        MailFixtures.queue(store, ONE, "m1@example.org", "Hello", DATE);
        long id = store.outbox().outgoing(ONE).get(0).id;
        store.outbox().handing(id);
        store.outbox().retryOutgoing(id);

        assertEquals(1, store.outbox().outgoing(ONE).size());
        assertNull(column("SELECT error FROM queue").get(0));
    }

    private static List<String> strings(JSONArray array) throws Exception {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            values.add(array.getString(index));
        }
        return values;
    }
}
