package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * A domain's filter: kept across restarts by collection id, an account's
 * checkbox over its collections, and the scope a domain's pull syncs.
 */
@RunWith(RobolectricTestRunner.class)
public class MergedFilterTest {
    private static final String JANE = "jane@example.com";
    private static final String JOHN = "john@example.org";
    private static final List<String> HERS = List.of("jane-inbox", "jane-archive", "jane-sent");

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    /** A build that kept the filter in memory left nothing: everything shows. */
    @Test
    public void aFirstStartShowsEverything() {
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);

        assertTrue(filter.accepts(JANE, "jane-inbox"));
        assertTrue(filter.accepts(JOHN, "john-inbox"));
        assertFalse(filter.isActive());
        assertEquals(MergedFilter.Tick.ON, filter.tick(JANE, HERS));
    }

    /** What is hidden is still hidden after a restart, and in its domain alone. */
    @Test
    public void theFilterIsKeptPerDomain() {
        MergedFilter.of(context, PimDomain.MAIL).toggleCollection("jane-archive");
        MergedFilter.of(context, PimDomain.CALENDAR).toggleAccount(JOHN);

        MergedFilter mail = MergedFilter.of(context, PimDomain.MAIL);
        MergedFilter calendar = MergedFilter.of(context, PimDomain.CALENDAR);

        assertFalse(mail.accepts(JANE, "jane-archive"));
        assertTrue(mail.accepts(JANE, "jane-inbox"));
        assertTrue(mail.accepts(JOHN, "john-inbox"));
        assertFalse(calendar.showsAccount(JOHN));
        assertTrue(calendar.accepts(JANE, "jane-archive"));
        assertTrue(MergedFilter.of(context, PimDomain.CONTACTS).accepts(JOHN, "john-book"));
    }

    /**
     * A shown account reads partly ticked while any of its collections is
     * unticked, none ticked included: a blank box is a hidden account alone.
     */
    @Test
    public void aShownAccountReadsWhatItsCollectionsSay() {
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);

        filter.toggleCollection("jane-archive");
        assertEquals(MergedFilter.Tick.PARTIAL, filter.tick(JANE, HERS));

        filter.toggleCollection("jane-inbox");
        filter.toggleCollection("jane-sent");
        assertEquals(MergedFilter.Tick.PARTIAL, filter.tick(JANE, HERS));
        assertTrue(filter.showsAccount(JANE));
        assertFalse(filter.accepts(JANE, "jane-inbox"));

        filter.toggleCollection("jane-inbox");
        filter.toggleCollection("jane-archive");
        filter.toggleCollection("jane-sent");
        assertEquals(MergedFilter.Tick.ON, filter.tick(JANE, HERS));
    }

    /**
     * Unticking an account hides it and nothing more: its collections keep
     * their own boxes while it is folded away, and come back as they were.
     */
    @Test
    public void anAccountComesBackWithItsChoices() {
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);
        filter.toggleCollection("jane-archive");

        filter.toggleAccount(JANE);
        assertEquals(MergedFilter.Tick.OFF, filter.tick(JANE, HERS));
        assertFalse(filter.showsAccount(JANE));
        assertFalse(filter.accepts(JANE, "jane-inbox"));
        assertFalse(filter.ticked(JANE, "jane-inbox"));
        assertTrue(filter.chosen("jane-inbox"));
        assertFalse(filter.chosen("jane-archive"));
        assertTrue(filter.isActive());

        filter = MergedFilter.of(context, PimDomain.MAIL);
        filter.toggleAccount(JANE);
        assertEquals(MergedFilter.Tick.PARTIAL, filter.tick(JANE, HERS));
        assertTrue(filter.accepts(JANE, "jane-inbox"));
        assertTrue(filter.accepts(JANE, "jane-sent"));
        assertFalse(filter.accepts(JANE, "jane-archive"));
    }

    /** An account with every collection unticked comes back with none ticked. */
    @Test
    public void anAccountWithNothingTickedComesBackSo() {
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);
        for (String collection : HERS) {
            filter.toggleCollection(collection);
        }

        filter.toggleAccount(JANE);
        filter.toggleAccount(JANE);

        assertEquals(MergedFilter.Tick.PARTIAL, filter.tick(JANE, HERS));
        for (String collection : HERS) {
            assertFalse(filter.chosen(collection));
            assertFalse(filter.accepts(JANE, collection));
        }
    }

    /** A collection flipped under a hidden account stays hidden until it is back. */
    @Test
    public void aCollectionFlippedUnderAHiddenAccountWaitsForIt() {
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);
        filter.toggleAccount(JANE);

        filter.toggleCollection("jane-sent");

        assertFalse(filter.showsAccount(JANE));
        assertEquals(MergedFilter.Tick.OFF, filter.tick(JANE, HERS));
        assertFalse(filter.accepts(JANE, "jane-inbox"));

        filter.toggleAccount(JANE);
        assertTrue(filter.accepts(JANE, "jane-inbox"));
        assertFalse(filter.accepts(JANE, "jane-sent"));
    }

    @Test
    public void resetShowsEverything() {
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);
        filter.toggleAccount(JOHN);
        filter.toggleCollection("jane-archive");

        filter.reset();

        assertFalse(MergedFilter.of(context, PimDomain.MAIL).isActive());
        assertTrue(filter.accepts(JOHN, "john-inbox"));
        assertTrue(filter.accepts(JANE, "jane-archive"));
    }

    /**
     * A domain's pull syncs what its filter shows; "Sync all" syncs every
     * collection of every account that is on; an account that is off syncs
     * nothing and shows nothing.
     */
    @Test
    public void thePullSyncsWhatTheFilterShows() {
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);
        filter.toggleCollection("jane-archive");
        filter.toggleAccount(JOHN);
        AccountActivation.set(context, "off@example.net", false);
        SyncScope all = SyncScope.all(context);

        assertTrue(filter.collection(JANE, "jane-inbox"));
        assertFalse(filter.collection(JANE, "jane-archive"));
        assertFalse(filter.account(JOHN));
        assertFalse(filter.collection(JOHN, "john-inbox"));
        assertFalse(filter.account("off@example.net"));
        assertFalse(filter.accepts("off@example.net", "off-inbox"));

        assertTrue(all.collection(JANE, "jane-archive"));
        assertTrue(all.account(JOHN));
        assertFalse(all.account("off@example.net"));
        assertFalse(all.collection("off@example.net", "off-inbox"));

        AccountActivation.set(context, "off@example.net", true);
        assertTrue(all.account("off@example.net"));
        assertTrue(filter.account("off@example.net"));
    }
}
