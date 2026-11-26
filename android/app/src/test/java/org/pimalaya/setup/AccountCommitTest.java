package org.pimalaya.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.FakeAndroidKeyStore;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.Clients;
import org.pimalaya.client.OauthGrant;
import org.pimalaya.client.OauthTokens;
import org.pimalaya.contact.BookEntry;
import org.pimalaya.contact.CardStore;
import org.pimalaya.contact.ContactStager;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The wizard's commit: one account from the sign-ins, books subscribed.
 *
 * <p>Saved onto what the address already had.
 */
@RunWith(RobolectricTestRunner.class)
public class AccountCommitTest {
    private static final String EMAIL = "jane@example.org";

    private static final String TOKEN = "https://auth.example.org/token";

    private Context context;

    private SecureStore store;

    @Before
    public void setUp() {
        FakeAndroidKeyStore.install();
        context = RuntimeEnvironment.getApplication();
        store = new SecureStore(context);
    }

    @Test
    public void aTokenSignedMailConnectionNamesItsUser() {
        AccountCredential grant =
                AccountCredential.oauth(
                        new OauthTokens("access", "Bearer", 0, "refresh", null, 0),
                        new OauthGrant(TOKEN, "app", null, "mail"));
        AccountEntry account =
                AccountCommit.assemble(
                        EMAIL,
                        List.of(
                                new AccountCommit.Connection(
                                        PimDomain.MAIL,
                                        "imaps://imap.example.org:993",
                                        "smtps://smtp.example.org:465",
                                        grant),
                                new AccountCommit.Connection(
                                        PimDomain.CONTACTS, "https://dav.example.org/", null, grant)));

        assertEquals(EMAIL, account.email);
        assertEquals(
                "imaps://jane%40example.org@imap.example.org:993",
                account.connection(PimDomain.MAIL).baseUrl);
        assertEquals(
                "smtps://jane%40example.org@smtp.example.org:465",
                account.connection(PimDomain.MAIL).submitUrl);
        assertEquals("https://dav.example.org/", account.connection(PimDomain.CONTACTS).baseUrl);
    }

    @Test
    public void aPasswordMailConnectionKeepsItsEndpointsAndMaySendNowhere() {
        AccountEntry account =
                AccountCommit.assemble(
                        EMAIL,
                        List.of(
                                new AccountCommit.Connection(
                                        PimDomain.MAIL,
                                        "imaps://imap.example.org:993",
                                        null,
                                        AccountCredential.password(EMAIL, "secret"))));

        assertEquals("imaps://imap.example.org:993", account.connection(PimDomain.MAIL).baseUrl);
        assertNull(account.connection(PimDomain.MAIL).submitUrl);
        assertNull(AccountCommit.assemble(EMAIL, List.of()));
    }

    @Test
    public void persistingKeepsTheDomainsTheAddressAlreadyHad() {
        store.connect(
                EMAIL,
                PimDomain.CONTACTS,
                "https://dav.example.org/",
                null,
                AccountCredential.password(EMAIL, "secret"));
        AccountEntry connected =
                AccountEntry.empty(EMAIL)
                        .with(
                                PimDomain.MAIL,
                                "imaps://imap.example.org:993",
                                null,
                                AccountCredential.password(EMAIL, "secret"));

        AccountEntry stored = AccountCommit.persist(store, connected);

        assertEquals(Set.of(PimDomain.MAIL, PimDomain.CONTACTS), stored.domains());
        assertEquals(
                Set.of(PimDomain.MAIL, PimDomain.CONTACTS),
                AccountEntry.find(store.loadAll(), EMAIL).domains());
    }

    @Test
    public void aGrantCoveringAnEarlierOneTakesItsDomainsOver() {
        store.connect(
                EMAIL,
                PimDomain.CONTACTS,
                "https://dav.example.org/",
                null,
                AccountCredential.oauth(
                        new OauthTokens("old", "Bearer", 0, "old-refresh", null, 0),
                        new OauthGrant(TOKEN, "app", null, "contacts")));
        AccountCredential union =
                AccountCredential.oauth(
                        new OauthTokens("new", "Bearer", 0, "new-refresh", null, 0),
                        new OauthGrant(TOKEN, "app", null, "contacts calendar"));
        AccountEntry connected =
                AccountEntry.empty(EMAIL).with(PimDomain.CALENDAR, "https://dav.example.org/cal/", null, union);

        AccountEntry stored = AccountCommit.persist(store, connected);

        assertEquals(1, stored.credentials().size());
        assertEquals(union.id, stored.credential(PimDomain.CONTACTS).id);
        assertEquals(
                union.id,
                AccountEntry.find(store.loadAll(), EMAIL).credential(PimDomain.CONTACTS).id);
    }

    @Test
    public void onlyTheTickedBooksAreSubscribedAndOnThePhoneOnlyWhenAsked() {
        PimdirDb pimdir = new PimdirDb(context);
        CardStore cards = new CardStore(context, pimdir);
        List<Addressbook> books =
                List.of(
                        new Addressbook("work", "Work", "https://dav.example.org/work/", null, null),
                        new Addressbook("home", "Home", "https://dav.example.org/home/", null, null));
        PimdirCollections collections = new PimdirCollections(pimdir, context);

        AccountCommit.books(
                cards,
                collections,
                new AccountCommit.BookChoice(
                        EMAIL, books, Set.of("https://dav.example.org/work/"), false));

        Map<String, BookEntry> entries = byUrl(cards);
        assertTrue(entries.get("https://dav.example.org/work/").subscribed);
        assertTrue(entries.get("https://dav.example.org/work/").remoteSynced);
        assertFalse(entries.get("https://dav.example.org/work/").phoneSynced);
        assertFalse(entries.get("https://dav.example.org/home/").subscribed);

        AccountCommit.books(
                cards,
                collections,
                new AccountCommit.BookChoice(
                        EMAIL, books, Set.of("https://dav.example.org/work/"), true));

        entries = byUrl(cards);
        assertTrue(entries.get("https://dav.example.org/work/").phoneSynced);
        assertFalse(entries.get("https://dav.example.org/home/").phoneSynced);
    }

    /**
     * A book committed unsubscribed is registered under the server (STORAGE
     * §15.6), so a card copied into it is the server's own create, never
     * refused as a copy no source could push (SYNC §7).
     */
    @Test
    public void aBookCommittedUnsubscribedTakesACopy() throws Exception {
        PimdirDb pimdir = new PimdirDb(context);
        String work = "https://dav.example.org/work/";
        String home = "https://dav.example.org/home/";
        List<Addressbook> books =
                List.of(
                        new Addressbook("work", "Work", work, null, null),
                        new Addressbook("home", "Home", home, null, null));
        AccountCommit.books(
                new CardStore(context, pimdir),
                new PimdirCollections(pimdir, context),
                new AccountCommit.BookChoice(EMAIL, books, Set.of(work), false));

        String card = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:u1\r\nFN:Jane\r\nEND:VCARD\r\n";
        String hash = CardStore.byteHash(card);
        ContactStager stager = new ContactStager(pimdir, Clients.real());
        JSONObject placement =
                new JSONObject()
                        .put("collection", work)
                        .put("handle", "c1.vcf")
                        .put("linkId", "u1")
                        .put("object", hash)
                        .put("level", "full")
                        .put("status", "clean")
                        .put("flags", new JSONArray())
                        .put("base", new JSONObject().put("revision", "e1").put("object", hash));
        JSONArray writes =
                new JSONArray()
                        .put(
                                new JSONObject()
                                        .put("op", "storeObject")
                                        .put("hash", hash)
                                        .put("size", card.length())
                                        .put("body", card))
                        .put(new JSONObject().put("op", "upsert").put("placement", placement));
        assertEquals(
                "{}",
                stager.serve(new JSONObject().put("op", "write").put("writes", writes).toString()));

        stager.mutateCopy(work, "c1.vcf", home);

        try (Cursor cursor =
                pimdir.getReadableDatabase()
                        .rawQuery(
                                "SELECT deleted FROM items WHERE collection = ? AND link_id = 'u1'",
                                new String[] {home})) {
            assertTrue("Home holds the copy", cursor.moveToFirst());
            assertEquals(0, cursor.getInt(0));
        }
    }

    private static Map<String, BookEntry> byUrl(CardStore cards) {
        Map<String, BookEntry> entries = new HashMap<>();
        for (BookEntry entry : cards.loadAllAddressbooks()) {
            entries.put(entry.book.url(), entry);
        }
        return entries;
    }
}
