package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Addressbook;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.sqlite.ShadowHostSqliteConnection;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** Pins what reading the waiting changes leaves open. */
@RunWith(RobolectricTestRunner.class)
public class UnsentContactsTest {
    private static final String EMAIL = "jane@example.com";
    private static final String BOOK = "https://dav.example.com/books/b1/";

    /**
     * Each sync's end and each list reload reads the waiting changes: none
     * leaves a connection to cards.db open beside the app's one card store.
     */
    @Test
    public void readingTheRowsOpensNoCardStoreOfItsOwn() {
        Context context = RuntimeEnvironment.getApplication();
        PimdirDb pimdir = new PimdirDb(context);
        CardStore cards = new CardStore(context, pimdir);
        List<Addressbook> books = List.of(new Addressbook("b1", "Book One", BOOK, null, null));
        cards.replaceAddressbooks(EMAIL, books);
        new PimdirCollections(pimdir, context)
                .replace(EMAIL, PimdirSummary.CONTACT, PimdirCollections.of(EMAIL, books));
        String path = cards.getReadableDatabase().getPath();
        int open = ShadowHostSqliteConnection.openNow(path);

        for (int i = 0; i < 3; i++) {
            UnsentContacts.rows(pimdir, cards, context);
        }

        assertEquals(open, ShadowHostSqliteConnection.openNow(path));
    }
}
