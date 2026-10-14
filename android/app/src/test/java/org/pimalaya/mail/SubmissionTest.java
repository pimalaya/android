package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Mailbox;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The submission payload the queue persists, byte for byte as before.
 *
 * <p>Written as the version before the typed {@link Submission} wrote it
 * (Android's org.json, insertion order), and that version's payloads read back
 * to the same values; literals captured on that version.
 */
@RunWith(RobolectricTestRunner.class)
public class SubmissionTest {
    private static final String ONE = "jane@example.com";
    private static final String DATE = "Mon, 5 Jan 2026 09:00:00 +0000";
    private static final byte[] SOURCE =
            "From: a@example.org\r\n\r\nthe body\r\n".getBytes(StandardCharsets.UTF_8);

    /** A payload as the version before wrote it, with an unknown key. */
    private static final String OLD =
            "{\"v\":1,\"from\":\"jane@example.com\",\"messageId\":\"old\\/x@example.org\","
                    + "\"subject\":\"Old \\u00e9\",\"sortKey\":\"2026-01-05T09:00:00Z\","
                    + "\"extra\":{\"k\":[1,2]}}";

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
        List<String> payloads = new ArrayList<>();
        try (Cursor cursor =
                pimdir.getReadableDatabase()
                        .rawQuery("SELECT payload FROM queue ORDER BY id", null)) {
            while (cursor.moveToNext()) {
                payloads.add(cursor.getString(0));
            }
        }
        return payloads;
    }

    private void payload(long id, String text) {
        pimdir.getWritableDatabase()
                .execSQL("UPDATE queue SET payload = ? WHERE id = ?", new Object[] {text, id});
    }

    @Test
    public void thePayloadIsWrittenAsBefore() {
        String key = PimdirSummary.mailSortKey(DATE);
        store.outbox().queueSubmission(Submission.of(ONE, "m1@example.org", "Hello", key), SOURCE);
        store.outbox().queueSubmission(
                Submission.of(
                        ONE,
                        "a/b@example.org",
                        "Quote \" back\\slash / tab\t \u00e9 \ud83d\ude00 \u0001",
                        key),
                SOURCE);
        store.outbox().queueSubmission(Submission.of(ONE, "empty@example.org", "", ""), SOURCE);
        store.outbox().queueSubmission(Submission.of(ONE, "nul@example.org", null, null), SOURCE);

        assertEquals(
                List.of(
                        "{\"v\":1,\"from\":\"jane@example.com\",\"messageId\":\"m1@example.org\","
                                + "\"subject\":\"Hello\",\"sortKey\":\"2026-01-05T09:00:00Z\"}",
                        "{\"v\":1,\"from\":\"jane@example.com\",\"messageId\":\"a\\/b@example.org\","
                                + "\"subject\":\"Quote \\\" back\\\\slash \\/ tab\\t \u00e9"
                                + " \ud83d\ude00 \\u0001\",\"sortKey\":\"2026-01-05T09:00:00Z\"}",
                        "{\"v\":1,\"from\":\"jane@example.com\",\"messageId\":\"empty@example.org\","
                                + "\"subject\":\"\",\"sortKey\":\"\"}",
                        "{\"v\":1,\"from\":\"jane@example.com\",\"messageId\":\"nul@example.org\"}"),
                payloads());

        List<String> rows = new ArrayList<>();
        for (MailStore.StoredMessage message : store.loadMerged(10)) {
            rows.add(message.accountEmail + "|" + message.mailbox + "|" + message.id + "|"
                    + message.subject + "|" + message.fromAddress + "|" + message.stamp);
        }
        assertEquals(
                List.of(
                        "jane@example.com|Outbox|nul@example.org||jane@example.com|0",
                        "jane@example.com|Outbox|empty@example.org||jane@example.com|0",
                        "jane@example.com|Outbox|a/b@example.org|Quote \" back\\slash / tab\t \u00e9"
                                + " \ud83d\ude00 \u0001|jane@example.com|1767603600000",
                        "jane@example.com|Outbox|m1@example.org|Hello|jane@example.com|1767603600000"),
                rows);

        List<String> waiting = new ArrayList<>();
        for (Outbox.Outgoing outgoing : store.outbox().outgoing(ONE)) {
            waiting.add(outgoing.messageId);
        }
        assertEquals(
                List.of("m1@example.org", "a/b@example.org", "empty@example.org", "nul@example.org"),
                waiting);
    }

    @Test
    public void aPayloadWrittenBeforeReadsTheSame() {
        store.outbox().queueSubmission(Submission.of(ONE, "old@example.org", "x", ""), SOURCE);
        store.outbox().queueSubmission(Submission.of(ONE, "bad@example.org", "x", ""), SOURCE);
        payload(1, OLD);
        payload(2, "not json");

        List<String> rows = new ArrayList<>();
        for (MailStore.StoredMessage message : store.loadMerged(10)) {
            rows.add(message.accountEmail + "|" + message.mailbox + "|" + message.id + "|"
                    + message.subject + "|" + message.fromAddress + "|" + message.stamp);
        }
        assertEquals(
                "an unreadable payload reads as empty parts",
                List.of(
                        "|Outbox||||0",
                        "jane@example.com|Outbox|old/x@example.org|Old \u00e9|jane@example.com"
                                + "|1767603600000"),
                rows);

        List<String> waiting = new ArrayList<>();
        for (Outbox.Outgoing outgoing : store.outbox().outgoing(ONE)) {
            waiting.add(outgoing.id + " " + outgoing.messageId);
        }
        assertEquals(List.of("1 old/x@example.org", "2 "), waiting);

        assertTrue(store.outbox().submitting("old/x@example.org"));
        assertFalse(store.outbox().submitting("bad@example.org"));
        assertTrue("the unreadable one names the empty id", store.outbox().submitting(""));
    }

    @Test
    public void aResentPayloadKeepsItsText() {
        store.outbox().queueSubmission(Submission.of(ONE, "old@example.org", "x", ""), SOURCE);
        payload(1, OLD);
        store.outbox().parkUncertain(1, "eof");

        store.outbox().resendOutgoing(1);

        assertEquals("unknown keys and escapes kept as written", List.of(OLD), payloads());
        assertEquals("old/x@example.org", store.outbox().outgoing(ONE).get(0).messageId);
    }
}
