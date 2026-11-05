package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The background fill's listing queue.
 *
 * <p>Which mailbox a free job lists next, that one under way is never taken
 * twice, and that the queue stops when it may not run and resumes from the
 * floors it reached.
 */
public class MailFillTest {
    private static MailStore.Edge edge(String name, String role, boolean listed, String limit) {
        return new MailStore.Edge("jane@example.com", name, name, role, listed, limit, false);
    }

    /**
     * A fill host over edges in memory.
     *
     * <p>Each widening moves a mailbox's floor a day down; a mailbox reaching
     * the first of the month is whole.
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

        /** Widens one mailbox as a job does; answers whether it landed. */
        boolean widen(MailStore.Edge edge) {
            if (edge.mailbox.equals(failing)) {
                return false;
            }
            widened.add(edge.mailbox);
            int day = edge.limit == null ? 10 : Integer.parseInt(edge.limit.substring(8, 10)) - 1;
            String limit = day <= 1 ? null : String.format("2026-09-%02dT00:00:00Z", day);
            edges.set(edges.indexOf(edge), edge(edge.mailbox, edge.role, true, limit));
            return true;
        }
    }

    /** One job taking from the queue until it has nothing for it. */
    private static MailFill.Step drain(Host host, MailFill.Queue queue) {
        MailStore.Edge edge;
        while ((edge = queue.take()) != null) {
            queue.done(edge.collection, host.widen(edge));
        }
        return queue.outcome();
    }

    @Test
    public void everyFirstChunkComesFirstThenTheInboxAndTheSentMailThenTheMostRecentFloor() {
        List<MailStore.Edge> edges =
                List.of(
                        edge("Projets", "", true, "2026-09-20T00:00:00Z"),
                        edge("Sent", "sent", true, "2026-09-05T00:00:00Z"),
                        edge("INBOX", "inbox", true, "2026-09-08T00:00:00Z"),
                        edge("Archive", "", false, null));
        assertEquals(
                "a mailbox never listed before any fill chunk, the inbox's included",
                "Archive",
                MailFill.next(edges).mailbox);

        List<MailStore.Edge> fresh =
                List.of(
                        edge("Archive", "", false, null),
                        edge("Sent", "sent", false, null),
                        edge("INBOX", "inbox", false, null));
        assertEquals("INBOX", MailFill.next(fresh).mailbox);

        List<MailStore.Edge> listed =
                List.of(
                        edge("Projets", "", true, "2026-09-20T00:00:00Z"),
                        edge("Sent", "sent", true, "2026-09-05T00:00:00Z"),
                        edge("INBOX", "inbox", true, "2026-09-08T00:00:00Z"));
        assertEquals("INBOX", MailFill.next(listed).mailbox);

        assertNull(
                MailFill.next(
                        List.of(edge("INBOX", "inbox", true, null), edge("Sent", "sent", true, null))));
    }

    @Test
    public void aMailboxUnderWayIsNotTakenTwice() {
        Host host = new Host();
        host.edges.add(edge("INBOX", "inbox", true, "2026-09-08T00:00:00Z"));
        host.edges.add(edge("Projets", "", true, "2026-09-03T00:00:00Z"));
        MailFill.Queue queue = new MailFill.Queue(host);

        MailStore.Edge slow = queue.take();
        MailStore.Edge other = queue.take();
        assertEquals("INBOX", slow.mailbox);
        assertNotEquals(slow.mailbox, other.mailbox);
        assertNull("nothing left for a third job", queue.take());

        // NOTE: the other job lands first and takes again while the inbox's
        // chunk is under way: the queue answers what is left, never the inbox.
        queue.done(other.collection, host.widen(other));
        assertEquals("Projets", queue.take().mailbox);
    }

    @Test
    public void theFillStopsWhenItMayNotRunAndResumesFromItsFloors() {
        Host host = new Host();
        host.edges.add(edge("INBOX", "inbox", true, "2026-09-03T00:00:00Z"));
        host.edges.add(edge("Projets", "", true, "2026-09-03T00:00:00Z"));
        host.edges.add(edge("Sent", "sent", true, "2026-09-02T00:00:00Z"));

        MailFill.Queue queue = new MailFill.Queue(host);
        MailStore.Edge first = queue.take();
        queue.done(first.collection, host.widen(first));
        assertEquals(List.of("INBOX"), host.widened);

        // NOTE: backgrounded, or the network turned metered: nothing widens.
        host.allowed = false;
        assertNull(queue.take());
        assertEquals(MailFill.Step.PAUSED, queue.outcome());
        assertEquals(List.of("INBOX"), host.widened);

        host.allowed = true;
        assertEquals(MailFill.Step.DONE, drain(host, new MailFill.Queue(host)));
        assertEquals(
                "resumed where it stopped, the inbox and the sent mail whole first",
                List.of("INBOX", "INBOX", "Sent", "Projets", "Projets"),
                host.widened);
    }

    @Test
    public void aFailedChunkStopsTheFill() {
        Host host = new Host();
        host.edges.add(edge("INBOX", "inbox", true, "2026-09-03T00:00:00Z"));
        host.edges.add(edge("Projets", "", true, "2026-09-03T00:00:00Z"));
        host.failing = "INBOX";

        MailFill.Queue queue = new MailFill.Queue(host);
        assertEquals(MailFill.Step.FAILED, drain(host, queue));
        assertEquals("nothing taken after the failure", List.of(), host.widened);

        host.failing = null;
        assertEquals(
                "and the next start picks it up",
                MailFill.Step.DONE,
                drain(host, new MailFill.Queue(host)));
    }
}
