package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.FakeAndroidKeyStore;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.pimalaya.account.SyncScope;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.client.Transport;
import org.pimalaya.mail.MailStore;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.sync.SyncPasses;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * A card copied or moved into another book, at the store (pimdir SYNC §7).
 *
 * <p>No app path stages one today (the books dialog places a card as a
 * membership row or a fresh create): these pin the rule a future one meets,
 * after the roster a contacts pass reads registers every declared book
 * (STORAGE §15.6). The real bridge, engine and store; the server lists its
 * books and nothing else.
 */
@RunWith(RobolectricTestRunner.class)
public class CardMoveTest {
    private static final String EMAIL = "jane@example.org";
    private static final String WORK = "https://dav.example.org/books/work/";
    private static final String HOME = "https://dav.example.org/books/home/";
    private static final String CARD =
            "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:uid-c1\r\nFN:Jane Doe\r\nEND:VCARD\r\n";

    private static final List<Addressbook> BOOKS =
            List.of(
                    new Addressbook("work", "Work", WORK, null, null),
                    new Addressbook("home", "Home", HOME, null, null));

    private Context context;
    private PimdirDb pimdir;
    private SyncPasses passes;

    /** A contacts server that lists its books, and nothing else. */
    private static final class Books extends ContactClient {
        @Override
        public List<Addressbook> listAddressbooks(Transport transport, Account account) {
            return BOOKS;
        }
    }

    @Before
    public void setUp() throws Exception {
        FakeAndroidKeyStore.install();
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        CardStore cards = new CardStore(context, pimdir);
        SecureStore accounts = new SecureStore(context);
        accounts.connect(
                EMAIL,
                PimDomain.CONTACTS,
                "https://dav.example.org/",
                null,
                AccountCredential.password(EMAIL, "secret"));

        // NOTE: the books as a store written before the roster registered
        // them holds them; Work subscribed with its server switch off, so the
        // pass reaches no server past the roster, and Home not at all.
        cards.replaceAddressbooks(EMAIL, BOOKS);
        new PimdirCollections(pimdir, context)
                .replace(EMAIL, PimdirSummary.CONTACT, PimdirCollections.of(EMAIL, BOOKS));
        cards.setBookState(WORK, true, false, false);
        cards.setBookState(HOME, false, false, false);
        file(WORK);

        Clients clients =
                new Clients(
                        new AccountClient(),
                        new Books(),
                        new CalendarClient(),
                        new MailClient(),
                        new OfflineClient());
        passes =
                new SyncPasses(
                        context,
                        new SyncPasses.Parts(
                                pimdir,
                                cards,
                                accounts,
                                new MailStore(context, pimdir),
                                new EventStore(context, pimdir),
                                clients),
                        SyncPasses.HEADLESS);
    }

    /**
     * A card copied into a book the roster declares and no pass listed is
     * the server's own create: the pass's roster read registered the book.
     */
    @Test
    public void aCardCopiedIntoABookNeverListedIsTheServersOwn() throws Exception {
        pass();
        stager().mutateCopy(WORK, "c1.vcf", HOME);

        assertEquals("the target holds it, live", "0", deletedIn(HOME));
        assertEquals("the source keeps it", "0", deletedIn(WORK));
    }

    /** The same for a move, its source tombstoned beside the create. */
    @Test
    public void aCardMovedIntoABookNeverListedIsTheServersOwn() throws Exception {
        pass();
        stager().mutateMove(WORK, "c1.vcf", HOME);

        assertEquals("the target holds it, live", "0", deletedIn(HOME));
        assertEquals("tombstone", load(WORK).getJSONArray("placements")
                .getJSONObject(0).getString("status"));
    }

    /**
     * Registering lists nothing: the never-listed book holds no sync state,
     * and a book synced before keeps its checkpoint, its next pass a delta.
     */
    @Test
    public void theRosterRegistersAndListsNothing() throws Exception {
        pass();

        assertFalse("Home holds no sync state", load(HOME).has("checkpoint"));
        assertEquals("cp", load(WORK).getString("checkpoint"));
        assertEquals("nothing filed in Home", "0",
                scalar("SELECT count(*) FROM items WHERE collection = ?", HOME));
    }

    /**
     * A copy into a book no roster declared, which no source syncs, is refused,
     * staging nothing (SYNC §7, vector 229).
     */
    @Test
    public void aCardCopiedIntoABookNoSourceSyncsIsRefused() throws Exception {
        pass();
        String elsewhere = "https://dav.example.org/books/elsewhere/";
        new PimdirCollections(pimdir, context)
                .ensure(elsewhere, EMAIL, PimdirSummary.CONTACT, "Elsewhere");
        try {
            stager().mutateCopy(WORK, "c1.vcf", elsewhere);
            throw new AssertionError("a copy no source could push stages");
        } catch (PimalayaException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("no source syncs"));
        }
        assertNull("nothing lands in the target", deletedIn(elsewhere));
    }

    /** One contacts pass over the account, as a sync runs it. */
    private void pass() {
        SyncPasses.Outcome outcome = passes.syncRemote(SyncScope.all(context));
        assertNull(String.valueOf(outcome.failure), outcome.failure);
    }

    /** Files the card in {@code book} as a sync would, then checkpoints it. */
    private void file(String book) throws JSONException {
        String hash = CardStore.byteHash(CARD);
        JSONObject placement =
                new JSONObject()
                        .put("collection", book)
                        .put("handle", "c1.vcf")
                        .put("linkId", "uid-c1")
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
                                        .put("size", CARD.length())
                                        .put("body", CARD))
                        .put(new JSONObject().put("op", "upsert").put("placement", placement))
                        .put(
                                new JSONObject()
                                        .put("op", "setCheckpoint")
                                        .put("collection", book)
                                        .put("checkpoint", "cp"));
        assertEquals(
                "{}",
                stager().serve(
                        new JSONObject().put("op", "write").put("writes", writes).toString()));
    }

    private JSONObject load(String book) throws JSONException {
        return new JSONObject(
                stager().serve(new JSONObject().put("op", "load").put("collection", book)
                        .toString()));
    }

    private ContactStager stager() {
        return new ContactStager(pimdir, Clients.real());
    }

    private String deletedIn(String book) {
        return scalar(
                "SELECT deleted FROM items WHERE collection = ? AND link_id = 'uid-c1'", book);
    }

    private String scalar(String sql, String... args) {
        try (Cursor cursor = pimdir.getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }
}
