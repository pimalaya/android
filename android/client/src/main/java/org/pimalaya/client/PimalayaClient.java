package org.pimalaya.client;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The only surface the app needs: config search (endpoints plus
 * authentication methods) and RFC 6764 discovery, OAuth 2.0 token
 * refresh (the code-exchange side lives on {@link OauthSession}), the
 * onboarding connection check, and the addressbook/card operations.
 * Owns the TLS sockets and the Rust bridge; the app sees neither.
 * Every call blocks, so callers must run it off the main thread.
 *
 * <p>Forwards to the domain clients ({@link Clients}) until the app
 * calls them itself.
 */
public class PimalayaClient {
    /** The domain clients this one forwards to. */
    private final Clients clients = Clients.real();

    /**
     * Discovers the CardDAV context root for the email through the
     * given DNS resolver (null for the default DNS-over-HTTPS one).
     * Throws when none is found.
     */
    public String discover(Transport transport, String email, String resolver) {
        JSONObject reply = Replies.object(Native.discover(transport, email, resolver));
        return Json.string(reply, "url");
    }

    /** Forwards to {@link AccountClient#searchProvider}. */
    public List<ServiceConfig> searchProvider(Transport transport, String email, String resolver) {
        return clients.account().searchProvider(transport, email, resolver);
    }

    /** Forwards to {@link AccountClient#searchAll}. */
    public List<ServiceConfig> searchAll(String email, String resolver) {
        return clients.account().searchAll(email, resolver);
    }

    /** Forwards to {@link AccountClient#oauthRefresh}. */
    public OauthTokens oauthRefresh(
            Transport transport,
            String tokenEndpoint,
            String clientId,
            String clientSecret,
            String refreshToken,
            String scope) {
        return clients.account().oauthRefresh(
                transport,
                tokenEndpoint,
                clientId,
                clientSecret,
                refreshToken,
                scope);
    }

    /** Forwards to {@link AccountClient#oauthServerMetadata}. */
    public ServerMetadata oauthServerMetadata(Transport transport, String issuer) {
        return clients.account().oauthServerMetadata(transport, issuer);
    }

    /** Forwards to {@link AccountClient#oauthRegisterClient}. */
    public String oauthRegisterClient(
            Transport transport,
            String registrationEndpoint,
            String redirectUri,
            String clientName,
            String scope) {
        return clients.account().oauthRegisterClient(
                transport,
                registrationEndpoint,
                redirectUri,
                clientName,
                scope);
    }

    /** Forwards to {@link AccountClient#googleCarddavBase}. */
    public static String googleCarddavBase(String email) {
        return AccountClient.googleCarddavBase(email);
    }

    /** Forwards to {@link AccountClient#googleBase}. */
    public static String googleBase(String email) {
        return AccountClient.googleBase(email);
    }

    /** Forwards to {@link AccountClient#msgraphBase}. */
    public static String msgraphBase(String email) {
        return AccountClient.msgraphBase(email);
    }

    /** Forwards to {@link AccountClient#jmapBase}. */
    public static String jmapBase(String sessionUrl) {
        return AccountClient.jmapBase(sessionUrl);
    }

    /** Forwards to {@link AccountClient#submitUrl}. */
    public static String submitUrl(String entered) {
        return AccountClient.submitUrl(entered);
    }

    /** Forwards to {@link AccountClient#withUser}. */
    public static String withUser(String url, String user) {
        return AccountClient.withUser(url, user);
    }

    /** Forwards to {@link AccountClient#isAccountLevel}. */
    public static boolean isAccountLevel(Account account) {
        return AccountClient.isAccountLevel(account);
    }

    /** Forwards to {@link AccountClient#isAccountLevel}. */
    public static boolean isAccountLevel(String url) {
        return AccountClient.isAccountLevel(url);
    }

    /** Forwards to {@link AccountClient#writesOverrides}. */
    public static boolean writesOverrides(String url) {
        return AccountClient.writesOverrides(url);
    }

    /** Forwards to {@link AccountClient#writesExdates}. */
    public static boolean writesExdates(String url) {
        return AccountClient.writesExdates(url);
    }

    /** Forwards to {@link AccountClient#writesEvents}. */
    public static boolean writesEvents(String url) {
        return AccountClient.writesEvents(url);
    }

    /** Forwards to {@link AccountClient#submitsOverSession}. */
    public static boolean submitsOverSession(Account account) {
        return AccountClient.submitsOverSession(account);
    }

    /** Forwards to {@link AccountClient#isCarddav}. */
    public static boolean isCarddav(String url) {
        return AccountClient.isCarddav(url);
    }

    /** Forwards to {@link AccountClient#isGraph}. */
    public static boolean isGraph(Account account) {
        return AccountClient.isGraph(account);
    }

    /** Forwards to {@link AccountClient#isGoogle}. */
    public static boolean isGoogle(Account account) {
        return AccountClient.isGoogle(account);
    }

    /** Forwards to {@link AccountClient#isJmap}. */
    public static boolean isJmap(Account account) {
        return AccountClient.isJmap(account);
    }

    /** Forwards to {@link ContactClient#listAddressbooks}. */
    public List<Addressbook> listAddressbooks(Transport transport, Account account) {
        return clients.contact().listAddressbooks(transport, account);
    }

    /** Forwards to {@link MailClient#listMailboxes}. */
    public List<Mailbox> listMailboxes(MailSession session) {
        return clients.mail().listMailboxes(session);
    }

    /** Forwards to {@link MailClient#enumerateMailbox}. */
    public JSONObject enumerateMailbox(MailSession session, String mailbox, JSONObject request) {
        return clients.mail().enumerateMailbox(session, mailbox, request);
    }

    /** Forwards to {@link MailClient#enumerateMailboxRaw}. */
    public String enumerateMailboxRaw(MailSession session, String mailbox, JSONObject request) {
        return clients.mail().enumerateMailboxRaw(session, mailbox, request);
    }

    /** Forwards to {@link MailClient#nameMessages}. */
    public JSONArray nameMessages(MailSession session, String mailbox, List<String> handles) {
        return clients.mail().nameMessages(session, mailbox, handles);
    }

    /** Forwards to {@link MailClient#mailFloor}. */
    public String mailFloor(MailSession session, String mailbox, String before, int count) {
        return clients.mail().mailFloor(session, mailbox, before, count);
    }

    /** Forwards to {@link MailClient#mailNewest}. */
    public List<String> mailNewest(MailSession session, String mailbox, String before, int count) {
        return clients.mail().mailNewest(session, mailbox, before, count);
    }

    /** Forwards to {@link MailClient#mailStep}. */
    public JSONObject mailStep(
            MailSession session,
            String mailbox,
            JSONObject request,
            File dir,
            OfflineDriver store) {
        return clients.mail().mailStep(session, mailbox, request, dir, store);
    }

    /** Forwards to {@link MailClient#fetchMessageSources}. */
    public JSONObject fetchMessageSources(
            MailSession session,
            String mailbox,
            List<String> ids,
            File dir) {
        return clients.mail().fetchMessageSources(session, mailbox, ids, dir);
    }

    /** Forwards to {@link MailClient#messagePart}. */
    public long messagePart(File source, String part, File target) {
        return clients.mail().messagePart(source, part, target);
    }

    /** Forwards to {@link MailClient#parseMessage}. */
    public MessageBody parseMessage(byte[] source) {
        return clients.mail().parseMessage(source);
    }

    /** Forwards to {@link MailClient#parseMessage}. */
    public MessageBody parseMessage(File source) {
        return clients.mail().parseMessage(source);
    }

    /** Forwards to {@link MailClient#describeMessage}. */
    public JSONObject describeMessage(File source) {
        return clients.mail().describeMessage(source);
    }

    /** Forwards to {@link MailClient#attachmentsOf}. */
    public static List<MessageBody.Attachment> attachmentsOf(JSONArray listed) {
        return MailClient.attachmentsOf(listed);
    }

    /** Forwards to {@link MailClient#setMessageFlag}. */
    public void setMessageFlag(
            MailSession session,
            String mailbox,
            String id,
            String flag,
            boolean add) {
        clients.mail().setMessageFlag(session, mailbox, id, flag, add);
    }

    /** Forwards to {@link MailClient#composeMessage}. */
    public byte[] composeMessage(String draft) {
        return clients.mail().composeMessage(draft);
    }

    /** Forwards to {@link MailClient#submitMessage}. */
    public String submitMessage(MailSession session, byte[] source) {
        return clients.mail().submitMessage(session, source);
    }

    /** Forwards to {@link MailClient#relocateMessage}. */
    public void relocateMessage(MailSession session, String mailbox, String id, String target) {
        clients.mail().relocateMessage(session, mailbox, id, target);
    }

    /** Forwards to {@link MailClient#copyMessage}. */
    public void copyMessage(MailSession session, String mailbox, String id, String target) {
        clients.mail().copyMessage(session, mailbox, id, target);
    }

    /** Forwards to {@link MailClient#destroyMessage}. */
    public void destroyMessage(MailSession session, String mailbox, String id) {
        clients.mail().destroyMessage(session, mailbox, id);
    }

    /** Forwards to {@link MailClient#appendMessage}. */
    public void appendMessage(MailSession session, String mailbox, byte[] source, JSONArray flags) {
        clients.mail().appendMessage(session, mailbox, source, flags);
    }

    /** Forwards to {@link MailClient#openMail}. */
    public static MailSession openMail(Account account) {
        return MailClient.openMail(account);
    }

    /** Forwards to {@link ContactClient#jmapCapabilities}. */
    public Set<String> jmapCapabilities(Transport transport, Account account) {
        return clients.contact().jmapCapabilities(transport, account);
    }

    /** Forwards to {@link CalendarClient#listCalendars}. */
    public List<Calendar> listCalendars(Transport transport, Account account) {
        return clients.calendar().listCalendars(transport, account);
    }

    /** Forwards to {@link CalendarClient#syncEvents}. */
    public EventDelta syncEvents(
            Transport transport,
            Account account,
            String calendarUrl,
            String cursor) {
        return clients.calendar().syncEvents(transport, account, calendarUrl, cursor);
    }

    /** Forwards to {@link CalendarClient#multigetEvents}. */
    public List<Event> multigetEvents(
            Transport transport,
            Account account,
            String calendarUrl,
            List<String> ids) {
        return clients.calendar().multigetEvents(transport, account, calendarUrl, ids);
    }

    /** Forwards to {@link CalendarClient#expandEvent}. */
    public List<Occurrence> expandEvent(String ical, String windowStart, String windowEnd) {
        return CalendarClient.expandEvent(ical, windowStart, windowEnd);
    }

    /** Forwards to {@link CalendarClient#readEvent}. */
    public EventDetail readEvent(String ical, String recurrenceId) {
        return CalendarClient.readEvent(ical, recurrenceId);
    }

    /** Forwards to {@link CalendarClient#writeEvent}. */
    public String writeEvent(String ical, String edit) {
        return CalendarClient.writeEvent(ical, edit);
    }

    /** Forwards to {@link CalendarClient#mergeEvent}. */
    public EventMerge mergeEvent(String base, String local, String remote) {
        return CalendarClient.mergeEvent(base, local, remote);
    }

    /** Forwards to {@link CalendarClient#resolveEvent}. */
    public String resolveEvent(
            String base,
            String local,
            String remote,
            Map<Integer, String> picks) {
        return CalendarClient.resolveEvent(base, local, remote, picks);
    }

    /** Forwards to {@link CalendarClient#splitEvent}. */
    public EventSplit splitEvent(String ical, String edit) {
        return CalendarClient.splitEvent(ical, edit);
    }

    /** Forwards to {@link CalendarClient#removeEvent}. */
    public String removeEvent(String ical, String edit) {
        return CalendarClient.removeEvent(ical, edit);
    }

    /** Forwards to {@link CalendarClient#updateEvent}. */
    public String updateEvent(
            Transport transport,
            Account account,
            String calendarUrl,
            String id,
            String ical,
            String etag,
            String address) {
        return clients.calendar().updateEvent(
                transport,
                account,
                calendarUrl,
                id,
                ical,
                etag,
                address);
    }

    /** Forwards to {@link CalendarClient#newEvent}. */
    public String newEvent(
            String component,
            String uid,
            String stamp,
            String start,
            String tzid,
            String vtimezone) {
        return CalendarClient.newEvent(component, uid, stamp, start, tzid, vtimezone);
    }

    /** Forwards to {@link CalendarClient#createEvent}. */
    public EventRef createEvent(
            Transport transport,
            Account account,
            String calendarUrl,
            String id,
            String ical,
            String address) {
        return clients.calendar().createEvent(transport, account, calendarUrl, id, ical, address);
    }

    /** Forwards to {@link CalendarClient#deleteEvent}. */
    public void deleteEvent(
            Transport transport,
            Account account,
            String calendarUrl,
            String id,
            String etag,
            String address) {
        clients.calendar().deleteEvent(transport, account, calendarUrl, id, etag, address);
    }

    /**
     * Lists every card of an account-level backend (JMAP, Google) in
     * one pass, each carrying its addressbook memberships as book ids
     * ({@link Card#books}).
     */
    public List<Card> listAccountCards(Transport transport, Account account) {
        return ContactClient.cards(
                Native.listAccountCards(
                        transport, account.baseUrl, account.login, account.password));
    }

    /** Forwards to {@link ContactClient#listCards}. */
    public List<Card> listCards(Transport transport, Account account, String addressbookUrl) {
        return clients.contact().listCards(transport, account, addressbookUrl);
    }

    /** Forwards to {@link ContactClient#syncCards}. */
    public CardDelta syncCards(
            Transport transport,
            Account account,
            String addressbookUrl,
            String syncToken) {
        return clients.contact().syncCards(transport, account, addressbookUrl, syncToken);
    }

    /** Forwards to {@link ContactClient#multigetCards}. */
    public List<Card> multigetCards(
            Transport transport,
            Account account,
            String addressbookUrl,
            List<String> uris) {
        return clients.contact().multigetCards(transport, account, addressbookUrl, uris);
    }

    /** Forwards to {@link ContactClient#readGraphCards}. */
    public List<Card> readGraphCards(Transport transport, Account account, List<String> ids) {
        return clients.contact().readGraphCards(transport, account, ids);
    }

    /** Forwards to {@link ContactClient#readGoogleCards}. */
    public List<Card> readGoogleCards(Transport transport, Account account, List<String> ids) {
        return clients.contact().readGoogleCards(transport, account, ids);
    }

    /** Forwards to {@link OfflineClient#offlineSync}. */
    public JSONObject offlineSync(OfflineDriver driver, String collection, boolean full) {
        return clients.offline().offlineSync(driver, collection, full);
    }

    /** Forwards to {@link OfflineClient#offlineSyncImmutable}. */
    public JSONObject offlineSyncImmutable(
            OfflineDriver driver,
            String collection,
            String since,
            boolean scopeBound) {
        return clients.offline().offlineSyncImmutable(driver, collection, since, scopeBound);
    }

    /** Forwards to {@link OfflineClient#offlineUpgrade}. */
    public JSONObject offlineUpgrade(
            OfflineDriver driver,
            String collection,
            List<String> handles) {
        return clients.offline().offlineUpgrade(driver, collection, handles);
    }

    /** Forwards to {@link OfflineClient#offlineMutate}. */
    public void offlineMutate(OfflineDriver driver, String collection, JSONObject mutation) {
        clients.offline().offlineMutate(driver, collection, mutation);
    }

    /** Forwards to {@link ContactClient#updateCardBooks}. */
    public void updateCardBooks(
            Transport transport,
            Account account,
            String cardId,
            List<String> add,
            List<String> remove) {
        clients.contact().updateCardBooks(transport, account, cardId, add, remove);
    }

    /** Forwards to {@link ContactClient#createCard}. */
    public Card createCard(
            Transport transport,
            Account account,
            String addressbookUrl,
            String id,
            String vcard) {
        return clients.contact().createCard(transport, account, addressbookUrl, id, vcard);
    }

    /** Forwards to {@link ContactClient#readCard}. */
    public Card readCard(Transport transport, Account account, String addressbookUrl, String uri) {
        return clients.contact().readCard(transport, account, addressbookUrl, uri);
    }

    /** Forwards to {@link ContactClient#updateCard}. */
    public Card updateCard(
            Transport transport,
            Account account,
            String addressbookUrl,
            Card card,
            String baseVcard) {
        return clients.contact().updateCard(transport, account, addressbookUrl, card, baseVcard);
    }

    /** Forwards to {@link ContactClient#deleteCard}. */
    public void deleteCard(Transport transport, Account account, String addressbookUrl, Card card) {
        clients.contact().deleteCard(transport, account, addressbookUrl, card);
    }

    /** Forwards to {@link ContactClient#createCards}. */
    public List<Card> createCards(Transport transport, Account account, List<String> vcards) {
        return clients.contact().createCards(transport, account, vcards);
    }

    /** Forwards to {@link ContactClient#deleteCards}. */
    public void deleteCards(Transport transport, Account account, List<String> ids) {
        clients.contact().deleteCards(transport, account, ids);
    }

    /** Forwards to {@link ContactClient#pushCards}. */
    public JSONArray pushCards(
            Transport transport,
            Account account,
            String addressbookUrl,
            JSONArray changes) {
        return clients.contact().pushCards(transport, account, addressbookUrl, changes);
    }

    /** A bridge reply read whole, a failure it carries thrown. */
    public static JSONObject reply(String json) {
        return Replies.object(json);
    }
}
