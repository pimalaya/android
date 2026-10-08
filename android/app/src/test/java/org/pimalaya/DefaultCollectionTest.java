package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.Calendar;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where a new event or contact goes: the collection its source names default
 * (stored as pimdir's {@code default} role), else the account's only writable
 * one, else the user's choice; a read-only collection is never offered.
 */
@RunWith(RobolectricTestRunner.class)
public class DefaultCollectionTest {
    private static final String JANE = "jane@example.com";
    private static final String JOHN = "john@example.org";

    private Context context;
    private PimdirDb pimdir;
    private EventStore events;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        events = new EventStore(context, pimdir);
    }

    private static Calendar calendar(String id, String role, boolean writable) {
        return new Calendar(
                id, id, "https://dav.example.com/" + id + "/", null, null, role, writable);
    }

    private List<PimdirCollections.Stored> calendars() {
        return new PimdirCollections(pimdir, context).list(PimdirSummary.CALENDAR);
    }

    private DefaultCollection.Choice choice() {
        return DefaultCollection.choice(
                context, PimdirSummary.CALENDAR, calendars(), (account, collection) -> true);
    }

    private static List<String> names(List<PimdirCollections.Stored> collections) {
        List<String> names = new ArrayList<>();
        for (PimdirCollections.Stored collection : collections) {
            names.add(collection.name);
        }
        return names;
    }

    /** A Google account's events go to its primary calendar without asking. */
    @Test
    public void theSourcesDefaultTakesTheEvent() {
        events.replaceCalendars(
                JANE,
                List.of(
                        calendar("holidays", "", false),
                        calendar("jane", Calendar.DEFAULT, true),
                        calendar("team", "", true)));

        DefaultCollection.Choice choice = choice();

        assertEquals("jane", choice.direct.name);
        assertFalse(DefaultCollection.choosable(choice.direct, calendars()));
    }

    /** The role is pimdir's, and it moves when the source names another. */
    @Test
    public void theDefaultRoleIsStoredAndMoves() {
        events.replaceCalendars(
                JANE, List.of(calendar("a", Calendar.DEFAULT, true), calendar("b", "", true)));
        events.replaceCalendars(
                JANE, List.of(calendar("a", "", true), calendar("b", Calendar.DEFAULT, true)));

        List<PimdirCollections.Stored> listed = calendars();
        assertNull(listed.get(0).role);
        assertEquals(Calendar.DEFAULT, listed.get(1).role);
        assertTrue(events.loadCalendars().get(1).isDefault);
    }

    /** A source naming none falls back to the account's only writable calendar. */
    @Test
    public void theOnlyWritableCalendarIsTheDefault() {
        events.replaceCalendars(
                JANE, List.of(calendar("holidays", "", false), calendar("mine", "", true)));

        assertEquals("mine", choice().direct.name);
    }

    /** Several writable and none named: the user is asked, then their choice holds. */
    @Test
    public void theUsersChoiceIsTheLastFallback() {
        events.replaceCalendars(
                JANE,
                List.of(
                        calendar("holidays", "", false),
                        calendar("home", "", true),
                        calendar("work", "", true)));

        DefaultCollection.Choice asked = choice();
        assertNull(asked.direct);
        assertEquals(List.of("home", "work"), names(asked.offered));
        assertEquals(-1, asked.preselected);

        PimdirCollections.Stored work = asked.offered.get(1);
        assertTrue(DefaultCollection.choosable(work, calendars()));
        DefaultCollection.choose(context, JANE, PimdirSummary.CALENDAR, work.id);

        assertEquals("work", choice().direct.name);
        // NOTE: the choice is the app's: the store's role stays what the
        // source said.
        assertNull(calendars().get(2).role);
    }

    /** A read-only calendar is never offered, nor ever a default. */
    @Test
    public void aReadOnlyCalendarIsNeverOffered() {
        events.replaceCalendars(
                JANE,
                List.of(calendar("shared", Calendar.DEFAULT, false), calendar("a", "", true)));
        events.replaceCalendars(JOHN, List.of(calendar("b", "", true), calendar("c", "", false)));

        PimdirCollections.Stored readOnly =
                new PimdirCollections.Stored("x", JANE, "x", null, null, null, false);
        assertNull(DefaultCollection.of(List.of(readOnly), "x"));

        // NOTE: two accounts in view: each one's default is the one taken
        // there (its only writable one), and the user picks between them.
        DefaultCollection.Choice choice = choice();
        assertNull(choice.direct);
        assertEquals(Set.of("a", "b"), new HashSet<>(names(choice.offered)));
        assertTrue(choice.preselected >= 0);
    }

    /** The accounts in view are those the filter shows. */
    @Test
    public void theAccountInViewIsTheOneTheFilterShows() {
        events.replaceCalendars(
                JANE,
                List.of(calendar("jane", Calendar.DEFAULT, true), calendar("team", "", true)));
        events.replaceCalendars(JOHN, List.of(calendar("john", Calendar.DEFAULT, true)));

        DefaultCollection.Choice choice =
                DefaultCollection.choice(
                        context,
                        PimdirSummary.CALENDAR,
                        calendars(),
                        (account, collection) -> account.equals(JOHN));

        assertEquals("john", choice.direct.name);
    }

    /** A book's role comes through the roster the same way. */
    @Test
    public void aBooksRoleIsStored() {
        PimdirCollections collections = new PimdirCollections(pimdir, context);
        collections.replace(
                JANE,
                PimdirSummary.CONTACT,
                PimdirCollections.of(
                        JANE,
                        List.of(
                                new Addressbook(
                                        "myContacts", "Contacts", "https://g/myContacts", null,
                                        null, Calendar.DEFAULT, true),
                                new Addressbook(
                                        "friends", "Friends", "https://g/friends", null, null, "",
                                        true))));

        PimdirCollections.Stored found =
                DefaultCollection.of(
                        context, JANE, PimdirSummary.CONTACT,
                        collections.list(PimdirSummary.CONTACT));
        assertEquals("https://g/myContacts", found.id);
    }
}
