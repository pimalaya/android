package org.pimalaya.task;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.mail.MailStore;
import org.pimalaya.pimdir.ItemFixtures;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirItems;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;
import java.util.Set;

/**
 * Which Waiting For items a reply arrived to, as io-pimdir's
 * {@code PimdirReader::replied} reads it (cairn/changes/gtd-hardening).
 *
 * <p>The real bridge and store: the canonical {@code list_replied} over mail
 * the store files the way a sync does.
 */
@RunWith(RobolectricTestRunner.class)
public class TaskRepliesTest {
    private static final String EMAIL = "jane@example.com";
    private static final String DATE = "Mon, 26 Oct 2026 09:00:00 +0000";

    private Context context;
    private PimdirDb pimdir;
    private MailStore mail;
    private TaskStore store;
    private TaskWrites writes;
    private EventStore.StoredCalendar calendar;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        EventStore events = new EventStore(context, pimdir);
        events.replaceCalendars(
                EMAIL,
                List.of(
                        new Calendar(
                                "work", "Work", "https://dav.example.org/cal/", "", null, "",
                                true)));
        calendar = events.loadCalendars().get(0);
        CalendarParts parts =
                new CalendarParts(
                        pimdir,
                        new Clients(
                                new AccountClient(), new ContactClient(), new CalendarClient(),
                                new MailClient(), new OfflineClient()),
                        events);
        store = new TaskStore(parts);
        writes = new TaskWrites(parts);
        mail = new MailStore(context, pimdir);
        mail.replaceMailboxes(
                EMAIL, List.of(new Mailbox("INBOX", "inbox"), new Mailbox("Sent", "sent")));
    }

    /** Files a mail in {@code mailbox} answering {@code inReplyTo}. */
    private void reply(String mailbox, String id, String inReplyTo) throws Exception {
        JSONObject summary =
                PimdirSummary.mail(
                        id + "@example.org", "Re: the lease", "Ann", "ann@example.org", EMAIL,
                        DATE, 0, false);
        summary.getJSONObject("mail").put("in_reply_to", new JSONArray().put(inReplyTo));
        SQLiteDatabase db = new PimdirItems(pimdir).writable();
        db.beginTransaction();
        try {
            new ItemFixtures(pimdir)
                    .put(
                            db,
                            mail.collectionOf(EMAIL, mailbox),
                            new ItemFixtures.Row(
                                    id, null, summary, PimdirSummary.mailSortKey(DATE)));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Test
    public void aReplyMarksItsWaitingForAndTheirOwnFollowUpDoesNot() throws Exception {
        String answered =
                writes.waitFor(calendar, "The lease", "Ann", "ann@example.org", "lease@example.com");
        writes.waitFor(calendar, "The keys", "Ann", "ann@example.org", "keys@example.com");
        writes.waitFor(calendar, "The quote", "Bob", "bob@example.org", "quote@example.com");
        reply("INBOX", "r1", "lease@example.com");
        // NOTE: the person's own follow-up, filed under the sent role, answers
        // nothing (pimdir GUIDE §17, list_replied).
        reply("Sent", "r2", "keys@example.com");

        Set<String> replied = store.replied(store.load(context));

        assertEquals(Set.of(answered), replied);
        assertEquals(3, store.load(context).size());
    }

    @Test
    public void onlyAnOpenWaitingForIsMarked() throws Exception {
        writes.capture(calendar, "The lease", null, "lease@example.com");
        reply("INBOX", "r1", "lease@example.com");

        // NOTE: an Inbox item linked to the mail waits on nobody.
        assertEquals(Set.of(), store.replied(store.load(context)));
    }
}
