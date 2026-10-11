package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The first sync and Load more, as steps of about 50 messages. */
public class E2eMailStepTest extends E2eCase {
    /** More than one step's worth. */
    private static final int SEEDED = MailStep.COUNT + 6;

    public E2eMailStepTest(String target) {
        super(target);
    }

    @Test
    @E2eScenario({
        "mail: An account's mail comes in steps / A first sync",
        "mail: The mail list ends on Load more / Two accounts",
        "mail: A mailbox is listed a chunk at a time / A first pass over a large mailbox",
        "mail: A mailbox is listed a chunk at a time / Widening",
        "mail: An account's frontier is where its steps reached / An imported message",
    })
    public void loadMoreStepsAMailboxPastItsFirstStep() throws Exception {
        String mailbox = scratch();
        ZonedDateTime now = ZonedDateTime.now().withNano(0);
        for (int index = 0; index < SEEDED; index++) {
            E2eMessage message = message(String.format("step-%02d", index));
            // NOTE: a day apart: an IMAP floor is a day (SINCE, RFC 9051
            // section 6.4.4), so hours apart one chunk would list them all.
            message.date = now.minusDays(index + 1);
            fixture.seed(mailbox, message);
        }

        connected();
        String collection = phone.collection(address, mailbox);
        String accountId = phone.mail.accountIdOf(address);
        int first = phone.messages(collection, tag).size();
        String floor = MailWindow.since(phone.context, accountId);
        assertNotNull("a frontier left", floor);
        if (target.service.equals("imap")) {
            // NOTE: an IMAP step floors the account, the 50th newest across
            // its mailboxes: the scratch mailbox is listed from there,
            // however much of it lies above.
            int above = 0;
            for (int index = 0; index < SEEDED; index++) {
                String date = E2eMessage.RFC_5322.format(now.minusDays(index + 1));
                above += PimdirSummary.mailSortKey(date).compareTo(floor) >= 0 ? 1 : 0;
            }
            assertEquals("listed from the account's frontier " + floor, above, first);
        } else {
            // NOTE: Gmail and JMAP step the account, newest first wherever
            // filed, so other mailboxes' newer mail takes part of the step:
            // no lower bound holds for the scratch mailbox.
            assertTrue("the first step stops short of " + SEEDED + ": " + first, first < SEEDED);
        }

        // NOTE: the view filtered to the scratch mailbox steps it alone.
        loadMore(phone, collection);
        eventually(
                "Load more brings the rest",
                () -> {
                    if (phone.messages(collection, tag).size() < SEEDED) {
                        loadMore(phone, collection);
                    }
                    return phone.messages(collection, tag).size() == SEEDED;
                });

        Set<String> subjects = new HashSet<>();
        for (MailStore.StoredMessage message : phone.messages(collection, tag)) {
            assertTrue("listed once: " + message.subject, subjects.add(message.subject));
            if (target.service.equals("gmail")) {
                assertTrue("stepped whole: " + message.subject, phone.mail.holdsBody(message));
            }
        }
        // NOTE: a step over one mailbox moves its own frontier alone.
        String frontier = MailWindow.since(phone.context, accountId, collection);
        if (frontier != null) {
            String oldest = PimdirSummary.mailSortKey(
                    E2eMessage.RFC_5322.format(now.minusDays(SEEDED)));
            assertTrue(
                    "the frontier reaches the oldest stepped: " + frontier,
                    frontier.compareTo(oldest) <= 0);
        }
    }

    @Test
    @E2eScenario({
        "mail: A Gmail step is a set difference / Two labels",
        "mail: Bodies download behind the list / The same message under two labels",
    })
    public void aGmailMessageUnderTwoLabelsIsReadOnceAndFiledInBoth() throws Exception {
        requires(target.provider.equals("google"), "labels are Gmail's");
        String left = scratch();
        String right = scratch();
        fixture.seed(List.of(left, right), message("labelled"));

        connected();
        String leftCollection = phone.collection(address, left);
        String rightCollection = phone.collection(address, right);
        if (phone.message(leftCollection, subject("labelled")) == null) {
            loadMore(phone, leftCollection);
        }
        if (!target.service.equals("gmail")) {
            pass(phone, leftCollection, rightCollection);
        }
        MailStore.StoredMessage inLeft = phone.message(leftCollection, subject("labelled"));
        MailStore.StoredMessage inRight = phone.message(rightCollection, subject("labelled"));
        assertNotNull("filed under the first label", inLeft);
        assertNotNull("filed under the second label", inRight);
        if (target.service.equals("gmail")) {
            assertTrue("whole under the first", phone.mail.holdsBody(inLeft));
            assertTrue("whole under the second", phone.mail.holdsBody(inRight));
            assertEquals(
                    "one body, linked",
                    inLeft.objectHash,
                    inRight.objectHash);
        }

        fixture.unfile(right, subject("labelled"));
        eventually(
                "the label taken off leaves its mailbox",
                () -> {
                    pass(phone, leftCollection, rightCollection);
                    return phone.message(rightCollection, subject("labelled")) == null;
                });
        assertNotNull("kept under the other", phone.message(leftCollection, subject("labelled")));
        assertNull("not resurrected", phone.message(rightCollection, subject("labelled")));
    }
}
