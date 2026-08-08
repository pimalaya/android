package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The account id and the namespacing rules pimdir SPEC.md §9.2 imposes on an
 * owner that files several accounts in one store.
 */
@RunWith(RobolectricTestRunner.class)
public class PimdirAccountTest {
    private PimdirAccount accounts;

    @Before
    public void setUp() {
        accounts = new PimdirAccount(RuntimeEnvironment.getApplication());
    }

    @Test
    public void theIdIsStableAcrossLookups() {
        String first = accounts.idOf("jane@example.org");
        assertEquals(first, accounts.idOf("jane@example.org"));
        assertNotEquals(first, accounts.idOf("john@example.org"));
    }

    @Test
    public void theIdCarriesNoSeparator() {
        // The prefix is stripped by removing the id and one separator, so an id
        // containing one would make account 'a' + 'b/c' and account 'a/b' + 'c'
        // spell the same collection id, with nothing able to tell them apart.
        for (String email : new String[] {"a@x", "b@y", "c@z"}) {
            assertFalse(accounts.idOf(email).indexOf(PimdirAccount.SEPARATOR) >= 0);
        }
    }

    @Test
    public void renamingAnAddressKeepsTheStoreId() {
        // The whole reason the id is not the email: an address change must not
        // become an id change for every collection of that account.
        String before = accounts.idOf("old@example.org");
        accounts.rename("old@example.org", "new@example.org");

        assertEquals(before, accounts.idOf("new@example.org"));
    }

    @Test
    public void aHierarchicalCollectionNameSurvivesTheRoundTrip() {
        String id = accounts.idOf("jane@example.org");

        // The common case the spec calls out: '[Gmail]/All Mail' keeps every
        // separator of its own, because stripping removes exactly one.
        String collection = PimdirAccount.collectionId(id, "[Gmail]/All Mail");
        assertEquals("[Gmail]/All Mail", PimdirAccount.nameOf(id, collection));

        assertEquals("INBOX", PimdirAccount.nameOf(id, PimdirAccount.collectionId(id, "INBOX")));
    }

    @Test
    public void aCollectionOfAnotherAccountIsLeftAlone() {
        String mine = accounts.idOf("jane@example.org");
        String theirs = accounts.idOf("john@example.org");

        String other = PimdirAccount.collectionId(theirs, "INBOX");
        assertEquals("stripping must not claim another account's id", other,
                PimdirAccount.nameOf(mine, other));
    }

    @Test
    public void forgettingAnAccountStartsAFreshId() {
        String before = accounts.idOf("jane@example.org");
        accounts.forget("jane@example.org");

        assertNotEquals("a removed and re-added account is a new store grouping",
                before, accounts.idOf("jane@example.org"));
    }
}
