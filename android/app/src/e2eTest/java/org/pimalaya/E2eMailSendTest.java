package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.pimalaya.client.ComposeDraft;
import org.pimalaya.mail.MailStore;
import org.pimalaya.mail.MailSubmit;
import org.pimalaya.sync.SyncPasses;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * A message to the account itself, through the outbox, as the composer sends.
 */
public class E2eMailSendTest extends E2eCase {
    public E2eMailSendTest(String target) {
        super(target);
    }

    @Test
    @E2eScenario({
        "mail: A message is sent through an outbox / Composed with no network",
        "mail: A sent message is in Sent at once / Sent offline",
        "mail: A message is submitted and a copy is kept / The next sync",
        "mail: A message is submitted and a copy is kept / No network",
        "mail: A sent message is in Sent at once / Gmail renaming what it sends",
        "mail: A message is composed to RFC 5322 / A blind copy",
        "offline-store: An action is a local write / A marker written with the radio off",
    })
    public void aMessageSentToSelfIsDeliveredFiledOnceAndDrained() throws Exception {
        requires(!target.service.equals("jmap"), "a JMAP account sends nothing yet");
        connected();
        String inbox = role(phone, "inbox");
        String sent = role(phone, "sent");
        String outbox = phone.outbox(address);

        // NOTE: composed with the radio off: composing reaches for nothing.
        E2eDevice.network(RuntimeEnvironment.getApplication(), E2eDevice.Network.NONE);
        String subject = subject("sent");
        phone.submitter(address).queue(draft(subject, address, address));
        assertEquals("queued", 1, phone.mail.outbox().outgoing(address).size());
        MailStore.StoredMessage staged = phone.message(sent, subject);
        assertNotNull("in Sent at once", staged);
        assertTrue("marked pending", phone.owes(sent, staged.id));
        E2eDevice.network(RuntimeEnvironment.getApplication(), E2eDevice.Network.WIFI);

        if (target.smtp) {
            // NOTE: a submission endpoint nobody answers stands for no
            // network: the drain counts an attempt and stops, the copy waits.
            phone.accounts.add(E2eTarget.unreachable(account));
            SyncPasses.MailPass failed = phone.sync(address, List.of(outbox, sent));
            assertNotNull("the drain failed", failed.failure);
            assertEquals("still queued", 1, phone.mail.outbox().outgoing(address).size());
            assertTrue("the copy still pending", phone.owes(sent, phone.message(sent, subject).id));
            phone.accounts.add(account);
        }

        SyncPasses.MailPass drained = phone.sync(address, List.of(outbox, sent, inbox));
        assertNoFailure(drained);
        assertEquals("handed over once", 1, drained.sent);
        assertTrue("the outbox drained", phone.mail.outbox().outgoing(address).isEmpty());

        E2eDevice observer = observer();
        String seenInbox = observer.role(address, "inbox");
        String seenSent = observer.role(address, "sent");
        eventually(
                "delivered to the inbox and filed in Sent",
                () -> {
                    pass(observer, seenInbox, seenSent);
                    return observer.message(seenInbox, subject) != null
                            && !observer.messages(seenSent, subject).isEmpty();
                });
        // NOTE: a provider filing late would show a second copy on a later
        // pass, so Sent is read once more before counting.
        pass(observer, seenSent);
        assertEquals("Sent holds one", 1, observer.messages(seenSent, subject).size());

        eventually(
                "the phone's staged copy landed",
                () -> {
                    pass(phone, sent);
                    List<MailStore.StoredMessage> copies = phone.messages(sent, subject);
                    return copies.size() == 1 && !phone.owes(sent, copies.get(0).id);
                });
        assertEquals("the phone's Sent holds one", 1, phone.messages(sent, subject).size());

        if (!target.provider.equals("google")) {
            // NOTE: Gmail files one message for a self-send, the sender's,
            // which keeps its Bcc; elsewhere the delivered copy is apart.
            MailStore.StoredMessage delivered = observer.message(seenInbox, subject);
            observer.reader(address).read(delivered, () -> observer.passes.openMail(account));
            String source =
                    new String(
                            observer.mail.source(observer.message(seenInbox, subject)),
                            StandardCharsets.UTF_8);
            assertFalse(
                    "the delivered copy names no blind copy",
                    headers(source).toLowerCase(Locale.ROOT).contains("\nbcc:"));
        }
    }

    @Test
    @E2eScenario({
        "mail: Mail syncs on Wi-Fi only / A first sync on mobile data",
        "mail: Mail syncs on Wi-Fi only / Opening a message on mobile data",
    })
    public void onMobileDataTheOutboxGoesOutAndNothingIsListed() throws Exception {
        requires(!target.service.equals("jmap"), "a JMAP account sends nothing yet");
        String mailbox = scratch();
        fixture.seed(mailbox, message("before"));
        connected();
        String collection = phone.collection(address, mailbox);
        String outbox = phone.outbox(address);
        assertNotNull("the first step listed the scratch mailbox", phone.message(collection, subject("before")));

        fixture.seed(mailbox, message("after"));
        E2eDevice.network(RuntimeEnvironment.getApplication(), E2eDevice.Network.MOBILE);
        assertFalse("the gate reads mobile data as metered", phone.wifi());
        assertNotNull("Load more waits for Wi-Fi", phone.loadMore(address, null));

        String subject = subject("metered");
        phone.submitter(address).queue(draft(subject, address, ""));
        SyncPasses.MailPass metered = phone.sync(address, List.of(outbox, collection));
        assertNoFailure(metered);
        assertEquals("the outbox went out", 1, metered.sent);
        assertTrue("drained", phone.mail.outbox().outgoing(address).isEmpty());
        assertEquals(
                "nothing listed on mobile data",
                null,
                phone.message(collection, subject("after")));

        MailStore.StoredMessage before = phone.message(collection, subject("before"));
        if (!phone.mail.holdsBody(before)) {
            assertNotNull(
                    "opened on mobile data",
                    phone.reader(address).read(before, () -> phone.passes.openMail(account)).body);
        }

        E2eDevice.network(RuntimeEnvironment.getApplication(), E2eDevice.Network.WIFI);
        pass(phone, collection);
        assertNotNull("listed once on Wi-Fi", phone.message(collection, subject("after")));
    }

    /** A draft in the composer's shape, from the account. */
    private ComposeDraft draft(String subject, String to, String bcc) {
        return new ComposeDraft(
                address,
                "Pimalaya e2e",
                to,
                "",
                bcc,
                subject,
                "Sent by the Pimalaya Android end-to-end suite, " + tag + ".",
                MailSubmit.now(),
                MailSubmit.messageId(address),
                "",
                "");
    }

    /** A message's header section, a line feed before the first. */
    private static String headers(String source) {
        String normalized = source.replace("\r\n", "\n");
        int end = normalized.indexOf("\n\n");
        return "\n" + (end < 0 ? normalized : normalized.substring(0, end));
    }
}
