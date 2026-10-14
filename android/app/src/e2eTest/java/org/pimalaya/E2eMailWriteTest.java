package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.pimalaya.client.Flags;
import org.pimalaya.mail.MailStager;
import org.pimalaya.mail.MailStore;

import java.util.List;

/**
 * Markers and deletes staged on the phone, pushed, read back by another device.
 */
public class E2eMailWriteTest extends E2eCase {
    public E2eMailWriteTest(String target) {
        super(target);
    }

    @Test
    @E2eScenario({
        "mail: A message's markers can be written / Marking a message read",
        "mail: A message's markers can be written / A keyword the app does not model",
        "mail: A mail sync pushes no body / Flagging an opened message",
        "offline-store: A placement's status is derived from the row / A staged edit",
    })
    public void markersArePushedAndAKeywordIsKept() throws Exception {
        String mailbox = scratch();
        E2eMessage junk = message("junk");
        if (target.keywords) {
            junk.keywords.add("$junk");
        }
        fixture.seed(mailbox, junk);

        connected();
        String collection = phone.collection(address, mailbox);
        pass(phone, collection);
        MailStore.StoredMessage listed = phone.message(collection, subject("junk"));
        assertNotNull("listed", listed);
        assertFalse("unread", listed.seen());
        assertFalse("not starred", listed.flagged());

        phone.stager(address).stageFlag(listed, Flags.Marker.FLAGGED, true);
        phone.stager(address)
                .stageFlag(phone.message(collection, subject("junk")), Flags.Marker.SEEN, true);
        MailStore.StoredMessage staged = phone.message(collection, subject("junk"));
        assertTrue("starred at once", staged.flagged());
        assertTrue("read at once", staged.seen());
        assertTrue("pending", phone.owes(collection, staged.id));

        pass(phone, collection);
        assertFalse("pushed", phone.owes(collection, staged.id));

        E2eDevice observer = observer();
        String seen = observer.collection(address, mailbox);
        eventually(
                "another device reads it starred and read",
                () -> {
                    pass(observer, seen);
                    MailStore.StoredMessage there = observer.message(seen, subject("junk"));
                    return there != null && there.flagged() && there.seen();
                });
        if (target.keywords) {
            assertTrue(
                    "the keyword the app does not model is kept",
                    fixture.keywords(mailbox, subject("junk")).contains("$junk"));
        }

        phone.stager(address)
                .stageFlag(phone.message(collection, subject("junk")), Flags.Marker.FLAGGED, false);
        pass(phone, collection);
        eventually(
                "another device reads it unstarred",
                () -> {
                    pass(observer, seen);
                    MailStore.StoredMessage there = observer.message(seen, subject("junk"));
                    return there != null && !there.flagged() && there.seen();
                });
    }

    @Test
    @E2eScenario({
        "mail: A message is deleted into the account's trash / An account with a trash",
        "mail: A message is deleted into the account's trash / A message already in the trash",
        "offline-store: A local action is visible before any sync / A message deleted offline",
        "offline-store: A staged removal is a tombstone the load hands back / The row is not resurrected",
    })
    public void aDeleteMovesIntoTheTrashThenErases() throws Exception {
        String mailbox = scratch();
        fixture.seed(mailbox, message("deleted"));
        fixture.seed(mailbox, message("kept"));

        connected();
        String collection = phone.collection(address, mailbox);
        String trash = role(phone, "trash");
        pass(phone, collection, trash);
        MailStore.StoredMessage listed = phone.message(collection, subject("deleted"));
        assertNotNull("listed", listed);

        assertEquals(
                "a move into the trash",
                MailStager.Deletion.MOVED,
                phone.stager(address).stageDelete(listed));
        assertNull("gone from its mailbox at once", phone.message(collection, subject("deleted")));
        MailStore.StoredMessage moving = phone.message(trash, subject("deleted"));
        assertNotNull("in the trash at once", moving);
        assertTrue("pending", phone.owes(trash, moving.id));

        pass(phone, collection, trash);
        assertNull("not resurrected", phone.message(collection, subject("deleted")));
        // NOTE: a Google fixture reads labels, the trash being `TRASH`
        // whatever IMAP names it; `messages.trash` keeps the other labels,
        // which Gmail hides while it is in the trash.
        String trashName =
                target.provider.equals("google") ? "TRASH" : phone.mail.policy().trashOf(address);
        settles(
                "the server holds it in " + trashName + " and not in its mailbox",
                () -> {
                    List<String> where = fixture.where(subject("deleted"));
                    return where.contains(trashName)
                                    && (target.provider.equals("google") || !where.contains(mailbox))
                            ? null
                            : "filed in " + where;
                });

        E2eDevice observer = observer();
        String seen = observer.collection(address, mailbox);
        // NOTE: what the trash holds is read on the server and on the phone
        // that moved it: a device's steps leave the trash out, and listing
        // it when opened is owed (mail-load-more, B.13 rule 7).
        settles(
                "another device reads it gone from its mailbox, the other kept",
                () -> {
                    pass(observer, seen);
                    boolean left = observer.message(seen, subject("deleted")) == null;
                    boolean kept = observer.message(seen, subject("kept")) != null;
                    return left && kept
                            ? null
                            : "left its mailbox " + left + ", the other kept " + kept;
                });

        eventually(
                "the phone holds the server's copy in the trash",
                () -> {
                    pass(phone, trash);
                    MailStore.StoredMessage there = phone.message(trash, subject("deleted"));
                    return there != null && !phone.owes(trash, there.id);
                });
        MailStore.StoredMessage inTrash = phone.message(trash, subject("deleted"));
        MailStager.Deletion erased = phone.stager(address).stageDelete(inTrash);
        assertEquals("a delete for good", MailStager.Deletion.ERASED, erased);
        assertNull("gone at once", phone.message(trash, subject("deleted")));
        pass(phone, trash);

        settles(
                "the server holds it nowhere, the other message kept",
                () -> {
                    List<String> where = fixture.where(subject("deleted"));
                    pass(observer, seen);
                    boolean kept = observer.message(seen, subject("kept")) != null;
                    return where.isEmpty() && kept
                            ? null
                            : "filed in " + where + ", the other kept " + kept;
                });
    }
}
