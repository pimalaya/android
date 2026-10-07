package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * The background fill, a step at a time: which mailbox widens next, and
 * that it stops when it may not run and resumes from the floors it reached.
 */
@RunWith(RobolectricTestRunner.class)
public class MailFillTest {
    private static MailStore.Edge edge(String name, String role, boolean listed, String limit) {
        return new MailStore.Edge("jane@example.com", name, name, role, listed, limit);
    }

    /**
     * A fill host over edges held in memory: each widening moves a mailbox's
     * floor one day down, a mailbox reaching the first day of the month
     * being whole.
     */
    private static final class Host implements MailFill.Host {
        final List<MailStore.Edge> edges = new ArrayList<>();
        final List<String> widened = new ArrayList<>();
        boolean allowed = true;
        String failing;

        @Override
        public boolean allowed() {
            return allowed;
        }

        @Override
        public List<MailStore.Edge> edges() {
            return new ArrayList<>(edges);
        }

        @Override
        public void widen(MailStore.Edge edge) throws Exception {
            if (edge.mailbox.equals(failing)) {
                throw new IllegalStateException("the connection dropped");
            }
            widened.add(edge.mailbox);
            int day = edge.limit == null ? 10 : Integer.parseInt(edge.limit.substring(8, 10)) - 1;
            String limit = day <= 1 ? null : String.format("2026-09-%02dT00:00:00Z", day);
            edges.set(edges.indexOf(edge), edge(edge.mailbox, edge.role, true, limit));
        }
    }

    @Test
    public void theInboxAndTheSentMailFillFirstAndTheMostRecentFloorLeads() {
        List<MailStore.Edge> edges =
                List.of(
                        edge("Projets", "", true, "2026-09-20T00:00:00Z"),
                        edge("Sent", "sent", true, "2026-09-05T00:00:00Z"),
                        edge("INBOX", "inbox", true, "2026-09-08T00:00:00Z"),
                        edge("Archive", "", false, null));
        assertEquals("INBOX", MailFill.next(edges).mailbox);

        List<MailStore.Edge> whole =
                List.of(
                        edge("Projets", "", true, "2026-09-20T00:00:00Z"),
                        edge("Sent", "sent", true, null),
                        edge("INBOX", "inbox", true, null),
                        edge("Archive", "", false, null));
        assertEquals(
                "a mailbox never listed before one that only lacks older mail",
                "Archive",
                MailFill.next(whole).mailbox);

        assertNull(
                MailFill.next(
                        List.of(edge("INBOX", "inbox", true, null), edge("Sent", "sent", true, null))));
    }

    @Test
    public void aScrollWidensEveryShownMailboxHoldingTheLimitingFloor() {
        List<MailStore.Edge> edges =
                List.of(
                        edge("INBOX", "inbox", true, "2026-09-20T00:00:00Z"),
                        edge("Sent", "sent", true, "2026-09-20T00:00:00Z"),
                        edge("Projets", "", true, "2026-09-25T00:00:00Z"),
                        edge("Archive", "", true, "2026-09-02T00:00:00Z"));

        List<MailStore.Edge> limiting = MailFill.limiting(edges, name -> !name.equals("Projets"));
        assertEquals(2, limiting.size());
        assertEquals("INBOX", limiting.get(0).mailbox);
        assertEquals("Sent", limiting.get(1).mailbox);

        assertEquals(
                "Projets", MailFill.limiting(edges, name -> true).get(0).mailbox);
        assertEquals(0, MailFill.limiting(edges, name -> false).size());
    }

    @Test
    public void theFillStopsWhenItMayNotRunAndResumesFromItsFloors() {
        Host host = new Host();
        host.edges.add(edge("INBOX", "inbox", true, "2026-09-03T00:00:00Z"));
        host.edges.add(edge("Projets", "", true, "2026-09-03T00:00:00Z"));
        host.edges.add(edge("Sent", "sent", true, "2026-09-02T00:00:00Z"));

        assertEquals(MailFill.Step.AGAIN, MailFill.step(host));
        assertEquals(List.of("INBOX"), host.widened);

        // NOTE: backgrounded, or the network turned metered: nothing widens.
        host.allowed = false;
        assertEquals(MailFill.Step.PAUSED, MailFill.step(host));
        assertEquals(List.of("INBOX"), host.widened);

        host.allowed = true;
        while (MailFill.step(host) == MailFill.Step.AGAIN) {
            // the fill runs to its end
        }
        assertEquals(
                "resumed where it stopped, the inbox and the sent mail whole first",
                List.of("INBOX", "INBOX", "Sent", "Projets", "Projets"),
                host.widened);
        assertEquals(MailFill.Step.DONE, MailFill.step(host));
    }

    @Test
    public void aFailedChunkStopsTheFill() {
        Host host = new Host();
        host.edges.add(edge("INBOX", "inbox", true, "2026-09-03T00:00:00Z"));
        host.failing = "INBOX";

        assertEquals(MailFill.Step.FAILED, MailFill.step(host));

        host.failing = null;
        assertEquals("and the next start picks it up", MailFill.Step.AGAIN, MailFill.step(host));
    }
}
