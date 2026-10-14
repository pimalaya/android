package org.pimalaya.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.FakeAndroidKeyStore;
import org.pimalaya.account.AccountActivation;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.LocalBook;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.Card;
import org.pimalaya.client.Clients;
import org.pimalaya.client.Mailbox;
import org.pimalaya.contact.CardStore;
import org.pimalaya.contact.PimdirContacts;
import org.pimalaya.mail.MailStore;
import org.pimalaya.mail.SenderName;
import org.pimalaya.pimdir.DefaultCollection;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.sync.BackgroundCheck;
import org.pimalaya.sync.FirstSync;
import org.pimalaya.sync.SyncPasses;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Deleting an account: its credentials, mail and events go, its contacts stay
 * on the device, and every per-account choice the app kept is dropped.
 */
@RunWith(RobolectricTestRunner.class)
public class AccountRemovalTest {
    private static final String EMAIL = "jane@example.org";

    private static final String BOOK = "https://dav.example.org/books/jane/";

    private Context context;

    private PimdirDb pimdir;

    private CardStore cards;

    private SecureStore store;

    private MailStore mail;

    private EventStore events;

    private PimdirContacts contacts;

    @Before
    public void setUp() {
        FakeAndroidKeyStore.install();
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        cards = new CardStore(context, pimdir);
        store = new SecureStore(context);
        mail = new MailStore(context, pimdir);
        events = new EventStore(context, pimdir);
        contacts = new PimdirContacts(pimdir);

        cards.ensureLocalAddressbook(LocalBook.URL, LocalBook.ACCOUNT, LocalBook.ID);
        new PimdirCollections(pimdir, context)
                .ensure(LocalBook.URL, LocalBook.ACCOUNT, PimdirSummary.CONTACT, "On this device");

        AccountCredential password = AccountCredential.password(EMAIL, "secret");
        store.connect(EMAIL, PimDomain.MAIL, "imaps://imap.example.org:993", null, password);
        store.connect(EMAIL, PimDomain.CONTACTS, "https://dav.example.org/", null, password);
        store.connect(EMAIL, PimDomain.CALENDAR, "https://dav.example.org/", null, password);
    }

    @Test
    public void anAccountGoesWithItsDataAndItsChoices() {
        List<Addressbook> books = List.of(new Addressbook("jane", "Jane", BOOK, null, null));
        cards.replaceAddressbooks(EMAIL, books);
        new PimdirCollections(pimdir, context)
                .replace(EMAIL, PimdirSummary.CONTACT, PimdirCollections.of(EMAIL, books));
        cards.setBookState(BOOK, true, true, false);
        contacts.save(
                BOOK,
                new Card(
                        "u1",
                        null,
                        null,
                        "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:u1\r\nFN:Bob\r\nEND:VCARD\r\n"));

        mail.replaceMailboxes(EMAIL, List.of(new Mailbox("INBOX", "inbox"), new Mailbox("Archive", "")));
        String archive = mail.collectionOf(EMAIL, "Archive");
        MergedFilter.of(context, PimDomain.MAIL).toggleCollection(archive);

        events.replaceCalendars(
                EMAIL,
                List.of(
                        new Calendar("work", "Work", "https://dav.example.org/cal/work/", null, null),
                        new Calendar("home", "Home", "https://dav.example.org/cal/home/", null, null)));
        List<PimdirCollections.Stored> calendars =
                new PimdirCollections(pimdir, context).list(PimdirSummary.CALENDAR);
        DefaultCollection.choose(context, EMAIL, PimdirSummary.CALENDAR, calendars.get(0).id);
        assertEquals(
                calendars.get(0).id,
                DefaultCollection.of(context, EMAIL, PimdirSummary.CALENDAR, calendars).id);

        FirstSync.owe(context, EMAIL, PimDomain.MAIL);
        SenderName.set(context, EMAIL, "Jane");
        BackgroundCheck.setNotifies(context, EMAIL, true);
        BackgroundCheck.setInterval(context, EMAIL, 240);
        AccountActivation.set(context, EMAIL, false);

        List<String> urls =
                new AccountRemoval(
                                context,
                                new SyncPasses.Parts(
                                        pimdir, cards, store, mail, events, Clients.real()),
                                contacts)
                        .remove(EMAIL);

        assertEquals(List.of(BOOK), urls);
        assertNull(store.find(EMAIL));
        assertEquals(1, contacts.list(LocalBook.URL).size());
        assertEquals("Bob", contacts.list(LocalBook.URL).get(0).name);
        assertTrue(mail.loadMailboxes(List.of()).isEmpty());
        assertTrue(events.loadCalendars().isEmpty());

        assertFalse(FirstSync.owing(context, PimDomain.MAIL).contains(EMAIL));
        assertEquals("", SenderName.of(context, EMAIL));
        assertFalse(BackgroundCheck.notifies(context, EMAIL));
        assertEquals(
                BackgroundCheck.interval(context, "nobody@example.org"),
                BackgroundCheck.interval(context, EMAIL));

        assertTrue(MergedFilter.of(context, PimDomain.MAIL).accepts(EMAIL, archive));
        assertFalse(MergedFilter.of(context, PimDomain.MAIL).isActive());
        assertNull(DefaultCollection.of(context, EMAIL, PimdirSummary.CALENDAR, calendars));
        assertTrue(AccountActivation.enabled(context, EMAIL));
    }
}
