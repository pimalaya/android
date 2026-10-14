package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.pimalaya.client.Flags;
import org.pimalaya.client.MessageBody;
import org.pimalaya.mail.MailStore;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Base64;

/** A mailbox listed, and a message opened, against a real server. */
public class E2eMailListTest extends E2eCase {
    public E2eMailListTest(String target) {
        super(target);
    }

    @Test
    @E2eScenario({
        "mail: A mailbox is listed a chunk at a time / A small mailbox",
        "mail: A message's date is its Date header / A message received late",
        "mail: A listing's text is decoded before it is stored / A subject in another script",
        "mail: The attachment mark is corrected by the body / A list footer",
        "mail: An account's mail comes in steps / A small account",
    })
    public void aFirstPassListsAScratchMailboxWhole() throws Exception {
        String mailbox = scratch();
        E2eMessage late = message("late");
        late.date = ZonedDateTime.now().minusDays(3).withNano(0);
        E2eMessage encoded = message("encoded");
        encoded.rawSubject =
                tag + " =?UTF-8?B?"
                        + Base64.getEncoder().encodeToString("Été".getBytes(StandardCharsets.UTF_8))
                        + "?=";
        E2eMessage mixed = message("mixed");
        mixed.mixed = true;
        fixture.seed(mailbox, late);
        fixture.seed(mailbox, encoded);
        fixture.seed(mailbox, mixed);

        connected();
        String collection = phone.collection(address, mailbox);
        pass(phone, collection);

        assertEquals("all three listed", 3, phone.messages(collection, tag).size());

        MailStore.StoredMessage stored = phone.message(collection, subject("late"));
        assertNotNull("listed under its subject", stored);
        assertEquals(
                "dated by its Date header, not its arrival",
                late.date.toEpochSecond() * 1000,
                stored.stamp);
        assertEquals("from the sender it names", address, stored.fromAddress);

        assertNotNull("its subject decoded", phone.message(collection, tag + " Été"));

        // NOTE: listed at meta, Gmail and IMAP mark it off the top-level
        // type, JMAP from the server's own flag; a body, stepped or opened,
        // settles the mark either way.
        MailStore.StoredMessage footer = phone.message(collection, subject("mixed"));
        if (!target.service.equals("jmap") && !phone.mail.holdsBody(footer)) {
            assertTrue("a multipart/mixed message listed with a paperclip", footer.hasAttachment);
        }
        phone.reader(address).read(footer, () -> phone.passes.openMail(account));
        assertFalse(
                "opened, it carries no attachment",
                phone.message(collection, subject("mixed")).hasAttachment);

        // NOTE: last, so a backend still stepping from the store reports
        // everything above first.
        for (MailStore.StoredMessage message : phone.messages(collection, tag)) {
            assertTrue(
                    "the first step brought it whole: " + message.subject,
                    phone.mail.holdsBody(message));
        }
    }

    @Test
    @E2eScenario({
        "mail: An opened message is stored and read back / A message never opened",
        "mail: An opened message is stored and read back / The same message twice",
        "mail: An opened message is stored and read back / Offline",
        "mail: A message body is read through the bridge / A JMAP message",
        "mail: Opening a message marks it read / Opening an unread message",
        "offline-store: A refresh keeps the bodies it does not restate / A sync after a read",
        "offline-store: An action is a local write / A marker written with the radio off",
    })
    public void anOpenedMessageIsStoredMarkedReadAndKept() throws Exception {
        String mailbox = scratch();
        E2eMessage unread = message("unread");
        unread.body = "The body of " + tag + ".";
        fixture.seed(mailbox, unread);

        connected();
        String collection = phone.collection(address, mailbox);
        pass(phone, collection);
        MailStore.StoredMessage listed = phone.message(collection, subject("unread"));
        assertFalse("listed unread", listed.seen);

        MessageBody body =
                phone.reader(address).read(listed, () -> phone.passes.openMail(account)).body;
        assertTrue("the body as sent: " + body.body(), body.body().contains("The body of " + tag));
        assertTrue("stored", phone.mail.holdsBody(listed));

        MessageBody again =
                phone.reader(address)
                        .read(
                                listed,
                                () -> {
                                    throw new AssertionError("a stored message reached the network");
                                })
                        .body;
        assertEquals("read back from the store", body.body(), again.body());

        // NOTE: the reader marks it read once the body arrived, a store write.
        phone.stager(address).stageFlag(listed, Flags.Marker.SEEN, true);
        MailStore.StoredMessage staged = phone.message(collection, subject("unread"));
        assertTrue("read at once", staged.seen);
        assertTrue("pending until a sync", phone.owes(collection, staged.id));

        pass(phone, collection);
        MailStore.StoredMessage synced = phone.message(collection, subject("unread"));
        assertFalse("pushed", phone.owes(collection, synced.id));
        assertTrue("the refresh kept the body", phone.mail.holdsBody(synced));

        E2eDevice observer = observer();
        String seen = observer.collection(address, mailbox);
        eventually(
                "another device reads it as read",
                () -> {
                    pass(observer, seen);
                    MailStore.StoredMessage there = observer.message(seen, subject("unread"));
                    return there != null && there.seen;
                });
    }
}
