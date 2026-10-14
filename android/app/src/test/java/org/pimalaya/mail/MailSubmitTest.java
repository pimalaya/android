package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Account;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.pimdir.PimdirDb;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The composer's queue, through the real bridge and the real store: a
 * draft lands in the outbox composed, and the rule that decides whether
 * its parent is marked answered.
 */
@RunWith(RobolectricTestRunner.class)
public class MailSubmitTest {
    private static final String EMAIL = "jane@example.com";

    private MailStore store;
    private MailSubmit submit;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        PimdirDb pimdir = new PimdirDb(context);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(EMAIL, List.of(new Mailbox("INBOX", "")));
        PimalayaClient client = new PimalayaClient();
        submit = new MailSubmit(client, store, new MailStager(pimdir, client));
    }

    @Test
    public void aReplyLandsInTheOutboxWithItsThreadHeaders() throws Exception {
        submit.queue(
                new MailSubmit.Draft(
                        EMAIL,
                        "Jane",
                        "bob@example.org",
                        "",
                        "",
                        "Re: Hi",
                        "the body",
                        "<a@example.org>",
                        "<root@example.org> <a@example.org>"));

        List<MailStore.Outgoing> waiting = store.outgoing(EMAIL);
        assertEquals(1, waiting.size());
        String source = new String(waiting.get(0).source, StandardCharsets.UTF_8);
        assertTrue(source, source.contains("In-Reply-To: <a@example.org>\r\n"));
        assertTrue(
                source, source.contains("References: <root@example.org>\r\n <a@example.org>\r\n"));
        assertTrue(
                "minted under the sender's domain",
                waiting.get(0).messageId.endsWith("@example.com"));
    }

    @Test
    public void graphAndGmailKeepNoAnsweredMarker() {
        assertFalse(answers("msgraph://jane@outlook.com"));
        assertFalse(answers("google://jane@gmail.com"));
        assertTrue(answers("imaps://imap.example.org:993"));
        assertTrue(answers("jmap://api.fastmail.com/jmap/session"));
    }

    private static boolean answers(String baseUrl) {
        return MailSubmit.marksAnswered(new Account(baseUrl, "jane", "secret", null));
    }
}
