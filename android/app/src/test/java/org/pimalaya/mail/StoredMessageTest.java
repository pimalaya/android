package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Mailbox;
import org.pimalaya.pimdir.ItemFixtures;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirItems;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The rows a list reads, synced and waiting to go out, on a seeded store.
 *
 * <p>Every value a row carries, captured before rows were built from typed
 * parts (header, listing or queue row, flags) and read the same since.
 */
@RunWith(RobolectricTestRunner.class)
public class StoredMessageTest {
    private static final String ONE = "jane@example.com";
    private static final String DATE = "Mon, 5 Jan 2026 09:00:00 +0000";
    private static final byte[] SOURCE =
            "From: a@example.org\r\n\r\nthe body\r\n".getBytes(StandardCharsets.UTF_8);

    private PimdirDb pimdir;
    private PimdirItems items;
    private ItemFixtures fixtures;
    private MailStore store;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        items = new PimdirItems(pimdir);
        fixtures = new ItemFixtures(pimdir);
        store = new MailStore(context, pimdir);
    }

    private void envelope(String mailbox, String id, String subject, String fromName,
            String date, long size, boolean attachment, String body, String flags) {
        String collection = store.collectionOf(ONE, mailbox);
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            fixtures.put(
                    db,
                    collection,
                    new ItemFixtures.Row(
                            id,
                            body,
                            PimdirSummary.mail(
                                    null, subject, fromName, "sender@example.org", null, date,
                                    size, attachment),
                            date == null ? "" : PimdirSummary.mailSortKey(date),
                            flags,
                            null));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private String dump(MailStore.StoredMessage m) {
        String account = store.accountIdOf(ONE);
        return (m.accountEmail + "|" + m.collection + "|" + m.mailbox + "|" + m.mailboxLabel
                        + "|" + m.id + "|" + m.subject + "|" + m.fromName + "|" + m.fromAddress
                        + "|" + m.sender() + "|" + m.stamp + "|" + m.sortKey + "|" + m.seq
                        + "|seen=" + m.seen() + "|answered=" + m.answered() + "|flagged="
                        + m.flagged() + "|deleted=" + m.deleted() + "|clip=" + m.hasAttachment
                        + "|" + m.attachment + "|" + m.size + "|bodied=" + m.bodied + "|q="
                        + (m.queued == null ? 0 : m.queued.id()) + "|" + m.objectHash
                        + "|failed=" + m.failed + "|uncertain=" + m.uncertain + "|pending="
                        + m.pending() + "|endpoint=" + (MailStore.endpointOf(m) != null))
                .replace(account, "A");
    }

    @Test
    public void rowsReadTheSameValues() {
        store.replaceMailboxes(
                ONE,
                List.of(new Mailbox("INBOX", "inbox"), new Mailbox("Bin", "trash"),
                        new Mailbox("Work", "")));
        envelope("INBOX", "1", "Plain", "Sender", DATE, 0, false, null, "[]");
        envelope("INBOX", "2", "Marked", "", "Tue, 6 Jan 2026 09:00:00 +0000", 1234, true,
                "From: x\r\n\r\nbody\r\n",
                "[\"\\\\Seen\",\"\\\\Answered\",\"\\\\Flagged\",\"$Label\"]");
        envelope("Bin", "3", "Gone", "Name", "Wed, 7 Jan 2026 09:00:00 +0000", 10, false, null,
                "[\"\\\\Deleted\",\"\\\\Seen\"]");
        envelope("Work", "4", "Bad flags", "N", "Thu, 8 Jan 2026 09:00:00 +0000", 0, true, null,
                "{\"x\":1}");
        envelope("Work", "5", "Undated", "N", null, 0, false, null, null);

        store.outbox().queueSubmission(
                Submission.of(ONE, "out1@example.org", "Out one", PimdirSummary.mailSortKey(DATE)),
                SOURCE);
        store.outbox().queueSubmission(
                Submission.of(ONE, "out2@example.org", "Out two", ""),
                SOURCE);
        store.outbox().queueSubmission(
                Submission.of(ONE, "out3@example.org", "Out three", ""),
                SOURCE);
        store.outbox().parkOutgoing(2, "550 refused");
        store.outbox().parkUncertain(3, "eof");

        List<String> merged = new ArrayList<>();
        for (MailStore.StoredMessage m : MailFixtures.merged(store, 20)) {
            merged.add(dump(m));
        }
        assertEquals(
                List.of(
                        "jane@example.com|A/\u0001outbox|Outbox|Outbox|out3@example.org|Out three||jane@example.com|jane@example.com|0||0|seen=true|answered=false|flagged=false|deleted=false|clip=false|0|null|bodied=true|q=3|gt5hyoqbe2nz7ut7orzhiqu2da|failed=true|uncertain=true|pending=true|endpoint=false",
                        "jane@example.com|A/\u0001outbox|Outbox|Outbox|out2@example.org|Out two||jane@example.com|jane@example.com|0||0|seen=true|answered=false|flagged=false|deleted=false|clip=false|0|null|bodied=true|q=2|gt5hyoqbe2nz7ut7orzhiqu2da|failed=true|uncertain=false|pending=true|endpoint=false",
                        "jane@example.com|A/\u0001outbox|Outbox|Outbox|out1@example.org|Out one||jane@example.com|jane@example.com|1767603600000||0|seen=true|answered=false|flagged=false|deleted=false|clip=false|0|null|bodied=true|q=1|gt5hyoqbe2nz7ut7orzhiqu2da|failed=false|uncertain=false|pending=true|endpoint=false",
                        "jane@example.com|A/Work|Work|Work|4|Bad flags|N|sender@example.org|N|1767862800000|2026-01-08T09:00:00Z|4|seen=false|answered=false|flagged=false|deleted=false|clip=true|1|null|bodied=false|q=0|null|failed=false|uncertain=false|pending=false|endpoint=true",
                        "jane@example.com|A/Bin|Bin|Trash|3|Gone|Name|sender@example.org|Name|1767776400000|2026-01-07T09:00:00Z|3|seen=true|answered=false|flagged=false|deleted=true|clip=false|0|10|bodied=false|q=0|null|failed=false|uncertain=false|pending=false|endpoint=true",
                        "jane@example.com|A/INBOX|INBOX|Inbox|2|Marked||sender@example.org|sender@example.org|1767690000000|2026-01-06T09:00:00Z|2|seen=true|answered=true|flagged=true|deleted=false|clip=true|1|1234|bodied=true|q=0|null|failed=false|uncertain=false|pending=false|endpoint=true",
                        "jane@example.com|A/INBOX|INBOX|Inbox|1|Plain|Sender|sender@example.org|Sender|1767603600000|2026-01-05T09:00:00Z|1|seen=false|answered=false|flagged=false|deleted=false|clip=false|0|null|bodied=false|q=0|null|failed=false|uncertain=false|pending=false|endpoint=true",
                        "jane@example.com|A/Work|Work|Work|5|Undated|N|sender@example.org|N|0||5|seen=false|answered=false|flagged=false|deleted=false|clip=false|0|null|bodied=false|q=0|null|failed=false|uncertain=false|pending=false|endpoint=true"),
                merged);

        List<String> unread = new ArrayList<>();
        for (MailStore.StoredMessage m :
                store.page(store.query((a, c) -> true, true, false, ""), null, 0, 20)) {
            unread.add(dump(m));
        }
        assertEquals(
                List.of(
                        "jane@example.com|A/Work|Work|Work|4|Bad flags|N|sender@example.org|N|1767862800000|2026-01-08T09:00:00Z|4|seen=false|answered=false|flagged=false|deleted=false|clip=true|1|null|bodied=false|q=0|null|failed=false|uncertain=false|pending=false|endpoint=true",
                        "jane@example.com|A/INBOX|INBOX|Inbox|1|Plain|Sender|sender@example.org|Sender|1767603600000|2026-01-05T09:00:00Z|1|seen=false|answered=false|flagged=false|deleted=false|clip=false|0|null|bodied=false|q=0|null|failed=false|uncertain=false|pending=false|endpoint=true",
                        "jane@example.com|A/Work|Work|Work|5|Undated|N|sender@example.org|N|0||5|seen=false|answered=false|flagged=false|deleted=false|clip=false|0|null|bodied=false|q=0|null|failed=false|uncertain=false|pending=false|endpoint=true"),
                unread);

        assertEquals(
                "jane@example.com|A/INBOX|INBOX|Inbox|2|Marked||sender@example.org|sender@example.org|1767690000000|2026-01-06T09:00:00Z|2|seen=true|answered=true|flagged=true|deleted=false|clip=true|1|1234|bodied=true|q=0|null|failed=false|uncertain=false|pending=false|endpoint=true",
                dump(store.message(store.collectionOf(ONE, "INBOX"), "2")));
    }
}
