package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

/** The reply's and the forward's text decisions, without a reader. */
public class MailReplyTest {
    private static final String SELF = "jane@example.com";

    @Test
    public void replyAllLeavesTheAccountOutInAnyCase() {
        MailReply.People people =
                MailReply.replyAll(
                        SELF,
                        "bob@example.org",
                        "Jane <JANE@Example.com>, carol@example.org",
                        "jane@example.com, dave@example.org");

        assertEquals("bob@example.org, carol@example.org", people.to());
        assertEquals("dave@example.org", people.cc());
    }

    @Test
    public void aMessageTheAccountSentAnswersItsRecipients() {
        MailReply.People people =
                MailReply.replyAll(SELF, "Jane@example.com", "bob@example.org", "");

        assertEquals("bob@example.org", people.to());
        assertEquals("", people.cc());
    }

    @Test
    public void aCopyAlreadyInToIsNotRepeated() {
        MailReply.People people =
                MailReply.replyAll(
                        SELF,
                        "bob@example.org",
                        "carol@example.org",
                        "bob@example.org, carol@example.org");

        assertEquals("bob@example.org, carol@example.org", people.to());
        assertEquals("", people.cc());
    }

    @Test
    public void beforeTheBodyLoadsTheReplyGoesToTheSenderAlone() {
        MailReply.People people = MailReply.replyAll(SELF, "bob@example.org", null, null);

        assertEquals("bob@example.org", people.to());
        assertEquals("", people.cc());
    }

    @Test
    public void aPrefixIsKeptOnceWhateverItsCase() {
        assertEquals("Re: Hi", MailReply.prefixed("Re:", "Hi"));
        assertEquals("RE: Hi", MailReply.prefixed("Re:", "RE: Hi"));
        assertEquals("Fwd: Re: Hi", MailReply.prefixed("Fwd:", "Re: Hi"));
    }

    @Test
    public void addressesDropDisplayNamesAndSeparators() {
        assertEquals(
                List.of("bob@example.org", "carol@example.org", "dave@example.org"),
                MailReply.addressesOf(
                        "\"Bob, B\" <bob@example.org>; carol@example.org,dave@example.org"));
    }

    @Test
    public void everyLineIsQuotedAQuotedLineWithoutASpace() {
        assertEquals("> one\n>> two\n> \n", MailReply.quoted("one\n> two\n"));
    }

    @Test
    public void theLastIdIsInReplyToAndAllAreReferences() {
        List<String> thread = List.of("<root@example.org>", "<a@example.org>");

        assertEquals("<a@example.org>", MailReply.inReplyTo(thread));
        assertEquals("<root@example.org> <a@example.org>", MailReply.references(thread));
    }
}
