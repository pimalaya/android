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
        MergedFilter.of(context, PimDomain.MAIL).toggleCollection(JANE, "jane-archive", HERS);
        MergedFilter.of(context, PimDomain.CALENDAR).toggleAccount(JOHN, List.of("john-work"));

        MergedFilter mail = MergedFilter.of(context, PimDomain.MAIL);
        MergedFilter calendar = MergedFilter.of(context, PimDomain.CALENDAR);

        assertFalse(mail.accepts(JANE, "jane-archive"));
        assertTrue(mail.accepts(JANE, "jane-inbox"));
        assertTrue(mail.accepts(JOHN, "john-inbox"));
        assertFalse(calendar.showsAccount(JOHN));
        assertTrue(calendar.accepts(JANE, "jane-archive"));
        assertTrue(MergedFilter.of(context, PimDomain.CONTACTS).accepts(JOHN, "john-book"));
    }

    /** An account reads partly ticked while some of its collections are hidden. */
    @Test
    public void anAccountReadsWhatItsCollectionsSay() {
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);

        filter.toggleCollection(JANE, "jane-archive", HERS);
        assertEquals(MergedFilter.Tick.PARTIAL, filter.tick(JANE, HERS));

        filter.toggleCollection(JANE, "jane-inbox", HERS);
        filter.toggleCollection(JANE, "jane-sent", HERS);
        assertEquals(MergedFilter.Tick.OFF, filter.tick(JANE, HERS));

        // NOTE: ticking an account whose collections are all unticked
        // ticks them all.
        filter.toggleAccount(JANE, HERS);
        assertEquals(MergedFilter.Tick.ON, filter.tick(JANE, HERS));
    }

    /** Unticking an account keeps its collections' choices for when it comes back. */
    @Test
    public void anAccountComesBackWithItsChoices() {
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);
        filter.toggleCollection(JANE, "jane-archive", HERS);

        filter.toggleAccount(JANE, HERS);
        assertEquals(MergedFilter.Tick.OFF, filter.tick(JANE, HERS));
        assertFalse(filter.accepts(JANE, "jane-inbox"));
        assertFalse(filter.ticked(JANE, "jane-inbox"));

        filter.toggleAccount(JANE, HERS);
        assertEquals(MergedFilter.Tick.PARTIAL, filter.tick(JANE, HERS));
        assertTrue(filter.accepts(JANE, "jane-inbox"));
        assertFalse(filter.accepts(JANE, "jane-archive"));
    }

    /** Ticking one collection of a hidden account brings it back with that one alone. */
    @Test
    public void aCollectionTickedUnderAHiddenAccountShowsAlone() {
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);
        filter.toggleAccount(JANE, HERS);

        filter.toggleCollection(JANE, "jane-sent", HERS);

        assertTrue(filter.accepts(JANE, "jane-sent"));
        assertFalse(filter.accepts(JANE, "jane-inbox"));
        assertEquals(MergedFilter.Tick.PARTIAL, filter.tick(JANE, HERS));
    }

    @Test
    public void resetShowsEverything() {
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);
        filter.toggleAccount(JOHN, List.of("john-inbox"));
        filter.toggleCollection(JANE, "jane-archive", HERS);

        filter.reset();

        assertFalse(MergedFilter.of(context, PimDomain.MAIL).isActive());
        assertTrue(filter.accepts(JOHN, "john-inbox"));
    }

    /**
     * A domain's pull syncs what its filter shows; "Sync all" syncs every
     * collection of every account that is on; an account that is off syncs
     * nothing and shows nothing.
     */
    @Test
    public void thePullSyncsWhatTheFilterShows() {
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);
        filter.toggleCollection(JANE, "jane-archive", HERS);
        filter.toggleAccount(JOHN, List.of("john-inbox"));
        AccountActivation.set(context, "off@example.net", false);
        SyncScope all = SyncScope.all(context);

        assertTrue(filter.collection(JANE, "jane-inbox"));
        assertFalse(filter.collection(JANE, "jane-archive"));
        assertFalse(filter.account(JOHN));
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
