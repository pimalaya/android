package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.account.AccountActivation;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Mailbox;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirItems;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * The body step's plan over a real store and the kept choices.
 *
 * <p>What an account downloads under its frontier, the filter and the mailboxes
 * kept whole, and what it owes.
 */
@RunWith(RobolectricTestRunner.class)
public class BodyPlanTest {
    private static final String JANE = "jane@example.com";
    private static final String OLD = "Mon, 5 Jan 2026 09:00:00 +0000";
    private static final String NEW = "Thu, 15 Jan 2026 09:00:00 +0000";
    private static final String FRONTIER = "2026-01-10T00:00:00Z";

    private Context context;
    private PimdirItems items;
    private MailStore store;
    private List<AccountEntry> accounts;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        PimdirDb pimdir = new PimdirDb(context);
        items = new PimdirItems(pimdir);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(
                JANE,
                List.of(
                        new Mailbox("INBOX", "inbox"),
                        new Mailbox("Archive", "archive"),
                        new Mailbox("Junk", "junk"),
                        new Mailbox("Trash", "trash")));
        MailWindow.set(context, PimdirAccount.accountIdOf(collection("INBOX")), FRONTIER);
        accounts = List.of(AccountEntry.empty(JANE));
    }

    private String collection(String mailbox) {
        return store.collectionOf(JANE, mailbox);
    }

    /** Files one message, its body held when one is given. */
    private void message(String mailbox, String id, String date, String body) {
        filed(collection(mailbox), id, date, body);
    }

    /** Files one message in any account's collection. */
    private void filed(String collection, String id, String date, String body) {
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            items.put(
                    db,
                    collection,
                    new PimdirItems.Row(
                            id,
                            body,
                            PimdirSummary.mail(
                                    null, id, "Sender", "sender@example.org", null, date, 0,
                                    true),
                            PimdirSummary.mailSortKey(date),
                            "[]",
                            null));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Ticks or unticks one mailbox on the filter page. */
    private void shown(String mailbox, boolean shown) {
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);
        if (filter.chosen(collection(mailbox)) != shown) {
            filter.toggleCollection(collection(mailbox));
        }
    }

    private BodyPlan.Plan plan() {
        return BodyPlan.of(context, store, accounts, MailState.of(context));
    }

    /** The planned rows of the account, in order, as collection and link id. */
    private static List<String> planned(BodyPlan.Plan plan) {
        List<String> rows = new ArrayList<>();
        Deque<MailBodies.Row> left = plan.run().left.get(JANE);
        if (left != null) {
            for (MailBodies.Row row : left) {
                rows.add(row.collection + " " + row.linkId);
            }
        }
        return rows;
    }

    @Test
    public void anAccountSwitchedOffPlansNothing() {
        message("INBOX", "1", NEW, null);
        AccountActivation.set(context, JANE, false);

        BodyPlan.Plan plan = plan();

        assertTrue(plan.run().drained());
        assertTrue("not counted, not even as zero", plan.left().isEmpty());
        assertEquals(0, plan.planned());
    }

    @Test
    public void theWindowIsPlannedNewestFirstAndCounted() {
        message("INBOX", "1", NEW, null);
        message("INBOX", "2", OLD, null);
        message("INBOX", "3", NEW, "From: a@example.org\r\n\r\nheld\r\n");

        BodyPlan.Plan plan = plan();

        assertEquals(List.of(collection("INBOX") + " 1"), planned(plan));
        assertEquals(Integer.valueOf(1), plan.left().get(JANE));
        assertEquals(1, plan.planned());
    }

    @Test
    public void anAccountPlanningNothingCountsZero() {
        BodyPlan.Plan plan = plan();

        assertEquals(Integer.valueOf(0), plan.left().get(JANE));
        assertEquals(0, plan.planned());
    }

    @Test
    public void anAccountPlansNoneOfAnotherAccountsBodies() {
        String john = "john@example.org";
        store.replaceMailboxes(john, List.of(new Mailbox("INBOX", "inbox")));
        String his = store.collectionOf(john, "INBOX");
        MailWindow.set(context, PimdirAccount.accountIdOf(his), FRONTIER);
        message("INBOX", "1", NEW, null);
        filed(his, "2", NEW, null);

        BodyPlan.Plan plan = plan();

        assertEquals(List.of(collection("INBOX") + " 1"), planned(plan));
        assertEquals(1, plan.planned());
    }

    @Test
    public void junkAndTrashPlanNothingAndTheirOwedBodiesAreSettled() {
        message("Junk", "1", NEW, null);
        message("Trash", "2", NEW, null);
        MailOwed.owe(context, collection("Junk"), List.of("1"));
        MailOwed.owe(context, collection("Trash"), List.of("2"));
        // NOTE: the filter quiets both once listed; ticked, they still plan
        // nothing.
        shown("Junk", true);
        shown("Trash", true);

        BodyPlan.Plan plan = plan();

        assertEquals(List.of(), planned(plan));
        assertTrue(MailOwed.all(context).isEmpty());
    }

    @Test
    public void aMailboxUntickedPlansNothingAndKeepsWhatItOwes() {
        message("Archive", "1", NEW, null);
        MailOwed.owe(context, collection("Archive"), List.of("2"));
        shown("Archive", false);

        BodyPlan.Plan plan = plan();

        assertEquals(List.of(), planned(plan));
        assertEquals(List.of(MailOwed.key(collection("Archive"), "2")), MailOwed.all(context));
    }

    @Test
    public void anAccountHiddenFromTheListStillPlansItsTickedMailboxes() {
        message("INBOX", "1", NEW, null);
        shown("INBOX", true);
        MergedFilter.of(context, PimDomain.MAIL).toggleAccount(JANE);

        assertEquals(List.of(collection("INBOX") + " 1"), planned(plan()));
    }

    @Test
    public void aMailboxKeptWholePlansEveryBody() {
        message("Archive", "1", OLD, null);
        message("INBOX", "2", OLD, null);
        shown("Archive", true);
        MailOffline.setWhole(context, collection("Archive"), true);

        assertEquals(List.of(collection("Archive") + " 1"), planned(plan()));
    }

    @Test
    public void anOwedBodyBelowTheFrontierIsPlannedLast() {
        message("INBOX", "1", OLD, null);
        message("INBOX", "2", NEW, null);
        MailOwed.owe(context, collection("INBOX"), List.of("1"));

        BodyPlan.Plan plan = plan();

        assertEquals(
                List.of(collection("INBOX") + " 2", collection("INBOX") + " 1"), planned(plan));
        assertTrue("downloads whatever its date", plan.run().left.get(JANE).peekLast().always);
        assertEquals(
                Set.of(MailOwed.key(collection("INBOX"), "1")), Set.copyOf(MailOwed.all(context)));
    }

    @Test
    public void anOwedBodyHeldOrGoneIsSettled() {
        message("INBOX", "1", OLD, "From: a@example.org\r\n\r\nheld\r\n");
        MailOwed.owe(context, collection("INBOX"), List.of("1", "2"));

        BodyPlan.Plan plan = plan();

        assertEquals(List.of(), planned(plan));
        assertFalse(MailOwed.owed(context, collection("INBOX"), "1"));
        assertTrue(MailOwed.all(context).isEmpty());
    }
}
