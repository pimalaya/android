package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.PimdirSql;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Links between items (pimdir STORAGE section 14.2) as the item pages read and
 * write them: both sides of a person's link, the reason a rule's link carries,
 * unlinking, and the search a link is picked from.
 */
@RunWith(RobolectricTestRunner.class)
public class ItemLinksTest {
    private static final String ONE = "jane@example.com";
    private static final String DATE = "Mon, 5 Jan 2026 09:00:00 +0000";
    private static final String BOOK = "https://dav.example.org/jane/contacts/";
    private static final String VCARD =
            "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:alice\r\nFN:Alice Martin\r\nEND:VCARD\r\n";
    private static final String SOURCE =
            "From: a@example.org\r\n"
                    + "Content-Type: multipart/mixed; boundary=\"sep\"\r\n"
                    + "\r\n"
                    + "--sep\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "\r\n"
                    + "the body\r\n"
                    + "--sep\r\n"
                    + "Content-Type: application/pdf\r\n"
                    + "Content-Disposition: attachment; filename=\"Quarterly.pdf\"\r\n"
                    + "\r\n"
                    + "%PDF\r\n"
                    + "--sep--\r\n";

    private PimdirDb pimdir;
    private PimdirItems items;
    private MailStore mail;
    private FileStore files;
    private ItemLinks links;
    private String inbox;

    private final ItemLinks.Endpoint message = new ItemLinks.Endpoint(PimdirSummary.MAIL, "m1");
    private final ItemLinks.Endpoint contact = ItemLinks.ofContact("alice");

    @Before
    public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        items = new PimdirItems(pimdir);
        mail = new MailStore(context, pimdir);
        files = new FileStore(context, pimdir);
        links = new ItemLinks(context, pimdir);
        mail.replaceMailboxes(ONE, List.of(new Mailbox("INBOX", "")));
        inbox = mail.collectionOf(ONE, "INBOX");
        new PimdirCollections(pimdir, context).ensure(BOOK, ONE, PimdirSummary.CONTACT, "Contacts");

        JSONObject card = PimdirSql.derive(PimdirSummary.CONTACT, VCARD.getBytes(StandardCharsets.UTF_8));
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            items.put(
                    db,
                    inbox,
                    new PimdirItems.Row(
                            "m1",
                            null,
                            PimdirSummary.mail(
                                    null, "Quarterly report", "Alice", "a@example.org", null,
                                    DATE, 0, true),
                            PimdirSummary.mailSortKey(DATE)));
            items.put(
                    db,
                    BOOK,
                    new PimdirItems.Row(
                            "alice",
                            VCARD,
                            card.getJSONObject("summary"),
                            card.getString("sortKey")));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Test
    public void aPersonsLinkShowsOnBothSidesUntilUnlinked() {
        assertTrue(links.add(message, contact));
        assertTrue("linking again is still linked", links.add(message, contact));

        List<ItemLinks.Link> fromMessage = links.links(message);
        assertEquals(1, fromMessage.size());
        assertFalse(fromMessage.get(0).automatic());
        assertEquals(ItemLinks.RELATED, fromMessage.get(0).role);
        assertEquals("Alice Martin", fromMessage.get(0).other.title);

        List<ItemLinks.Link> fromContact = links.links(contact);
        assertEquals(1, fromContact.size());
        assertEquals("Quarterly report", fromContact.get(0).other.title);
        assertEquals(inbox, fromContact.get(0).other.collection);

        links.remove(fromContact.get(0));
        assertTrue(links.links(message).isEmpty());
    }

    @Test
    public void anItemTheStoreDoesNotHoldIsNotLinked() {
        assertFalse(links.add(message, ItemLinks.ofContact("nobody")));
        assertFalse("nor an item to itself", links.add(message, message));
        assertNull(links.placement(ItemLinks.ofContact("nobody")));
    }

    @Test
    public void anAttachmentIsARulesLinkFromItsMessage() {
        byte[] source = SOURCE.getBytes(StandardCharsets.UTF_8);
        mail.saveSource(inbox, "m1", source);
        files.recordAttachments(
                mail.accountIdOf(ONE), "m1", new PimalayaClient().parseMessage(source).attachments);

        ItemLinks.Link link = links.links(message).get(0);
        assertTrue(link.automatic());
        assertEquals("attachment", link.role);
        assertEquals(PimdirSummary.FILE, link.to.kind);
        assertEquals("Quarterly.pdf", link.other.title);
    }

    @Test
    public void theSearchFindsEachKindByItsTitle() {
        byte[] source = SOURCE.getBytes(StandardCharsets.UTF_8);
        mail.saveSource(inbox, "m1", source);
        files.recordAttachments(
                mail.accountIdOf(ONE), "m1", new PimalayaClient().parseMessage(source).attachments);

        List<ItemLinks.Placement> found = links.search("quarterly");
        assertEquals(2, found.size());
        assertEquals(PimdirSummary.MAIL, found.get(0).endpoint.kind);
        assertEquals(PimdirSummary.FILE, found.get(1).endpoint.kind);

        List<ItemLinks.Placement> people = links.search("martin");
        assertEquals(1, people.size());
        assertEquals(contact.linkId, people.get(0).endpoint.linkId);
        assertTrue(links.search("  ").isEmpty());
    }
}
