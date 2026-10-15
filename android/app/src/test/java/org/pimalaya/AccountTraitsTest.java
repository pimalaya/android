package org.pimalaya;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Account;
import org.pimalaya.client.BackendInfo;
import org.pimalaya.client.CardFetch;
import org.pimalaya.client.Protocol;
import org.pimalaya.mail.MailStep;
import org.robolectric.RobolectricTestRunner;

/**
 * What each screen and engine decides by an account's backend facts.
 *
 * <p>One row per backend, each site's decision as it reads the facts, pinned
 * to what the sites decided by backend identity before the facts existed.
 * Under Robolectric, as the library loads into one class loader only.
 */
@RunWith(RobolectricTestRunner.class)
public class AccountTraitsTest {
    /** The decisions by base URL, as the identity predicates made them. */
    private static final Map<String, String> DECIDED = new LinkedHashMap<>();

    static {
        DECIDED.put(
                "google://jane@gmail.com",
                "{appends=false, marksAnswered=false, accountWide=true, allMail=true, sessions=2,"
                        + " step=GmailStep, submits=true, refusesTrashless=true,"
                        + " accountLevel=true, fetch=google, primesListing=false, push=google,"
                        + " resourceName=id, readsBack=false, probesCapabilities=false,"
                        + " protocolMail=gmail, protocolCalendar=gcal,"
                        + " protocolContacts=google_api}");
        DECIDED.put(
                "msgraph://jane@outlook.com",
                "{appends=false, marksAnswered=false, accountWide=false, allMail=false,"
                        + " sessions=4, step=GraphStep, submits=true, refusesTrashless=true,"
                        + " accountLevel=false, fetch=graph, primesListing=true, push=round,"
                        + " resourceName=id, readsBack=false, probesCapabilities=false,"
                        + " protocolMail=msgraph, protocolCalendar=msgraph,"
                        + " protocolContacts=msgraph}");
        DECIDED.put(
                "jmap://api.fastmail.com/jmap/session",
                "{appends=false, marksAnswered=true, accountWide=true, allMail=false, sessions=4,"
                        + " step=GmailStep, submits=true, refusesTrashless=true,"
                        + " accountLevel=true, fetch=changes, primesListing=false, push=round,"
                        + " resourceName=id, readsBack=false, probesCapabilities=true,"
                        + " protocolMail=jmap, protocolCalendar=jmap, protocolContacts=jmap}");
        DECIDED.put(
                "imaps://imap.example.org:993",
                "{appends=true, marksAnswered=true, accountWide=false, allMail=false, sessions=3,"
                        + " step=ImapStep, submits=false, refusesTrashless=false,"
                        + " accountLevel=false, fetch=multiget, primesListing=false, push=all,"
                        + " resourceName=id.vcf, readsBack=true, probesCapabilities=false,"
                        + " protocolMail=imap, protocolCalendar=caldav,"
                        + " protocolContacts=carddav}");
        DECIDED.put(
                "https://dav.example.org/",
                "{appends=true, marksAnswered=true, accountWide=false, allMail=false, sessions=3,"
                        + " step=ImapStep, submits=false, refusesTrashless=false,"
                        + " accountLevel=false, fetch=multiget, primesListing=false, push=all,"
                        + " resourceName=id.vcf, readsBack=true, probesCapabilities=false,"
                        + " protocolMail=imap, protocolCalendar=caldav,"
                        + " protocolContacts=carddav}");
        DECIDED.put(
                "local://on-this-device",
                "{appends=true, marksAnswered=true, accountWide=false, allMail=false, sessions=3,"
                        + " step=ImapStep, submits=false, refusesTrashless=false,"
                        + " accountLevel=false, fetch=multiget, primesListing=false, push=all,"
                        + " resourceName=id, readsBack=false, probesCapabilities=false,"
                        + " protocolMail=imap, protocolCalendar=caldav,"
                        + " protocolContacts=carddav}");
    }

    /**
     * Every site decides per backend as it did by identity.
     *
     * <p>One deliberate widening: a delete with no trash is refused wherever
     * no {@code \Deleted} marker is kept, Graph and Gmail too, not JMAP alone.
     * Both always list their trash, so neither reaches it.
     */
    @Test
    public void everySiteDecidesAsItDidByIdentity() {
        for (Map.Entry<String, String> decided : DECIDED.entrySet()) {
            Account account = new Account(decided.getKey(), "", "");
            assertEquals(decided.getKey(), decided.getValue(), decisions(account).toString());
        }
    }

    /** The decisions of every former predicate site, by its facts. */
    private static Map<String, Object> decisions(Account account) {
        BackendInfo info = account.info();
        Map<String, Object> row = new LinkedHashMap<>();
        // NOTE: DeletedItemsPage.appends, MessageCompose's reply marker,
        // MailEngine.mailboxes, SyncPasses' Load more and All Mail, the
        // pools' width, MailStep.of, the submit sites, MessageView's delete.
        row.put("appends", info.appendsMail());
        row.put("marksAnswered", info.keepsAnswered());
        row.put("accountWide", info.accountLevel());
        row.put("allMail", info.hasAllMail());
        row.put("sessions", info.sessions());
        row.put("step", MailStep.of(account).getClass().getSimpleName());
        row.put("submits", info.submitsOverSession());
        row.put("refusesTrashless", !info.keepsDeleted());
        // NOTE: ContactWriter, ContactPool and ContactEngine's fetch, push,
        // create name and revision read-back.
        row.put("accountLevel", info.accountLevel());
        row.put("fetch", fetch(info.cardFetch()));
        row.put("primesListing", info.cardFetch() == CardFetch.JSON_BATCH);
        row.put("push", push(info));
        row.put("resourceName", info.namesResources() ? "id.vcf" : "id");
        row.put("readsBack", info.readsBackRevision());
        // NOTE: OnboardingFlow's probes and AccountSettings' labels.
        row.put("probesCapabilities", info.announcesCapabilities());
        row.put("protocolMail", label(info.protocol(), "gmail", "imap"));
        row.put("protocolCalendar", label(info.protocol(), "gcal", "caldav"));
        row.put("protocolContacts", label(info.protocol(), "google_api", "carddav"));
        return row;
    }

    private static String fetch(CardFetch fetch) {
        switch (fetch) {
            case BATCH_GET:
                return "google";
            case CHANGES:
                return "changes";
            case JSON_BATCH:
                return "graph";
            default:
                return "multiget";
        }
    }

    /** A round of several changes, as ContactEngine.push picks. */
    private static String push(BackendInfo info) {
        switch (info.cardPush()) {
            case BATCH:
                return "google";
            case ROUND:
                return "round";
            default:
                return "all";
        }
    }

    private static String label(Protocol protocol, String google, String standard) {
        switch (protocol) {
            case GRAPH:
                return "msgraph";
            case JMAP:
                return "jmap";
            case GOOGLE:
                return google;
            default:
                return standard;
        }
    }
}
