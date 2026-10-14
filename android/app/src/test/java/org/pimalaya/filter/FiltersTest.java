package org.pimalaya.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import org.pimalaya.pimdir.PimdirCollections;

/** What Apply finds newly ticked, without a page or a sync. */
public class FiltersTest {
    private static final String JANE = "jane@example.com";
    private static final String BOB = "bob@example.org";

    @Test
    public void onlyCollectionsTickedNowAndNotKeptBeforeAreTaken() {
        List<FilterPage.Account> roster = List.of(account(JANE, "kept", "new", "off", "dropped"));

        Map<String, List<String>> taken =
                Filters.taken(
                        roster,
                        accepting(JANE + "/kept", JANE + "/dropped"),
                        accepting(JANE + "/kept", JANE + "/new"));

        assertEquals(Map.of(JANE, List.of("new")), taken);
    }

    @Test
    public void takenGroupsByAccountInRosterOrder() {
        List<FilterPage.Account> roster =
                List.of(account(BOB, "b1", "b2"), account(JANE, "j1", "j2"));

        Map<String, List<String>> taken =
                Filters.taken(
                        roster,
                        accepting(),
                        accepting(JANE + "/j2", JANE + "/j1", BOB + "/b2", BOB + "/b1"));

        assertEquals(List.of(BOB, JANE), new ArrayList<>(taken.keySet()));
        assertEquals(List.of("b1", "b2"), taken.get(BOB));
        assertEquals(List.of("j1", "j2"), taken.get(JANE));
    }

    @Test
    public void anAccountTakingNothingHasNoEntry() {
        List<FilterPage.Account> roster = List.of(account(BOB, "b1"), account(JANE, "j1"));

        Map<String, List<String>> taken =
                Filters.taken(roster, accepting(BOB + "/b1"), accepting(BOB + "/b1"));

        assertTrue(taken.isEmpty());
    }

    private static FilterPage.Account account(String email, String... ids) {
        List<PimdirCollections.Stored> collections = new ArrayList<>();
        for (String id : ids) {
            collections.add(new PimdirCollections.Stored(id, email, id, null, null));
        }
        return new FilterPage.Account(email, email, collections);
    }

    /** A filter accepting exactly the listed {@code account/collection} pairs. */
    private static BiPredicate<String, String> accepting(String... pairs) {
        Set<String> accepted = Set.of(pairs);
        return (account, collection) -> accepted.contains(account + "/" + collection);
    }
}
