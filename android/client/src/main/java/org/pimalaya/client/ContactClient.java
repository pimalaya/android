package org.pimalaya.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The contact verbs of the bridge (rs/ffi/contact.rs): the addressbook and
 * card operations over a transport, and the pure vCard computations, static,
 * that never open a socket. Every call that takes a transport blocks, so
 * callers must run it off the main thread.
 *
 * <p>Four backends hide behind the same operations: CardDAV, Microsoft
 * Graph, JMAP for Contacts (RFC 9610) and the Google People API. The
 * Rust bridge tells them apart by the account's base URL (the
 * non-CardDAV backends carry a sentinel scheme there) and dispatches
 * every operation; the base URL is opaque here, built by the
 * {@code *Base} helpers and threaded back verbatim. Graph and Google
 * accounts authenticate with their OAuth access token in
 * {@link Account#password}. Graph and JMAP updates carry no If-Match
 * guard (last-write-wins); CardDAV and Google updates are guarded by
 * the ETag.
 */
public class ContactClient {
    /**
     * Lists the account's addressbooks: the CardDAV discovery walk,
     * the Graph contact folders (default Contacts folder first), the
     * JMAP AddressBooks, or the Google contact groups.
     */
    public List<Addressbook> listAddressbooks(Transport transport, Account account) {
        JSONArray reply =
                Replies.array(
                        Native.listAddressbooks(
                                transport, account.baseUrl, account.login, account.password));

        List<Addressbook> books = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            books.add(Addressbook.from(Json.object(reply, index)));
        }
        return books;
    }

    /**
     * The capability URNs a JMAP account's session serves an account for, read afresh: what
     * the connection flow connects a domain over JMAP by.
     */
    public Set<String> jmapCapabilities(Transport transport, Account account) {
        JSONArray reply =
                Replies.array(
                        Native.jmapCapabilities(
                                transport, account.baseUrl, account.login, account.password));

        Set<String> urns = new LinkedHashSet<>();
        for (int index = 0; index < reply.length(); index++) {
            urns.add(reply.optString(index));
        }
        return urns;
    }

    /** Lists the cards of the addressbook collection at the given URL. */
    public List<Card> listCards(Transport transport, Account account, String addressbookUrl) {
        return cards(
                Native.listCards(
                        transport,
                        account.baseUrl,
                        addressbookUrl,
                        account.login,
                        account.password));
    }

    /**
     * Lists a collection's changes since the given cursor, on every
     * backend: a CardDAV sync-collection REPORT (RFC 6578), a Graph
     * contacts delta round (id and changeKey only), a JMAP
     * ContactCard/changes round (changed cards in full), or a People
     * connections sync (changed contacts in full, account-wide with
     * memberships). A null cursor runs the initial round: the complete
     * member set plus the cursor to delta from next time. A cursor the
     * server no longer accepts re-runs an initial round bridge-side,
     * flagged {@link CardDelta#complete}.
     */
    public CardDelta syncCards(Transport transport, Account account, String addressbookUrl, String syncToken) {
        String reply =
                Native.syncCards(
                        transport,
                        account.baseUrl,
                        addressbookUrl,
                        account.login,
                        account.password,
                        syncToken == null ? "" : syncToken);

        return CardDelta.from(Replies.object(reply));
    }

    /**
     * Batch-fetches the cards at the given resource names inside a
     * CardDAV addressbook via REPORT addressbook-multiget.
     */
    public List<Card> multigetCards(Transport transport, Account account, String addressbookUrl, List<String> uris) {
        return cards(
                Native.multigetCards(
                        transport,
                        addressbookUrl,
                        account.login,
                        account.password,
                        new JSONArray(uris).toString()));
    }

    /**
     * Reads the named Graph contacts, 20 to a {@code $batch}: one request
     * where {@link #readCard} sent one a contact. A contact Graph no
     * longer holds is left out of the list, as a multiget leaves out a
     * resource it no longer finds. Graph only.
     */
    public List<Card> readGraphCards(Transport transport, Account account, List<String> ids) {
        return cards(
                Native.readGraphCards(transport, account.password, new JSONArray(ids).toString()));
    }

    /**
     * Reads the named People contacts, 200 to a {@code people:batchGet}:
     * one request where {@link #readCard} sent one a contact. A contact
     * Google no longer holds is left out of the list, as a multiget
     * leaves out a resource it no longer finds. Google only.
     */
    public List<Card> readGoogleCards(Transport transport, Account account, List<String> ids) {
        return cards(
                Native.readGoogleCards(
                        transport, account.password, new JSONArray(ids).toString()));
    }

    /**
     * Adds and removes the card's addressbook memberships on an
     * account-level backend, by book id: JMAP patches addressBookIds,
     * Google modifies group members.
     */
    public void updateCardBooks(Transport transport, Account account, BooksUpdate update) {
        Replies.object(
                Native.updateCardBooks(
                        transport,
                        account.baseUrl,
                        account.login,
                        account.password,
                        update.cardId(),
                        books(update.add()),
                        books(update.remove())));
    }

    /**
     * Creates the card in the addressbook collection, returning it
     * with its ETag. The backends naming the resource themselves
     * return the server-assigned id instead of the given one.
     */
    public Card createCard(Transport transport, Account account, String addressbookUrl, NewCard card) {
        return Card.from(
                Replies.object(
                        Native.createCard(
                                transport,
                                account.baseUrl,
                                addressbookUrl,
                                account.login,
                                account.password,
                                card.id(),
                                card.vcard())));
    }

    /** Reads the card at the given resource name from the addressbook collection. */
    public Card readCard(Transport transport, Account account, String addressbookUrl, String uri) {
        return Card.from(
                Replies.object(
                        Native.readCard(
                                transport,
                                account.baseUrl,
                                addressbookUrl,
                                account.login,
                                account.password,
                                uri)));
    }

    /**
     * Updates the card in the addressbook collection, returning it with
     * its new ETag. The base vCard (the state last synced with the
     * server, null when unknown) trims the Graph, JMAP and Google
     * patches to the fields the edit changed; CardDAV PUTs the full
     * vCard and ignores it.
     */
    public Card updateCard(Transport transport, Account account, BookCard card, String baseVcard) {
        return Card.from(
                Replies.object(
                        Native.updateCard(
                                transport,
                                account.baseUrl,
                                card.bookUrl(),
                                account.login,
                                account.password,
                                card.card().id(),
                                card.card().uri() == null ? "" : card.card().uri(),
                                card.card().vcard(),
                                baseVcard == null ? "" : baseVcard,
                                card.card().etag() == null ? "" : card.card().etag())));
    }

    /** Deletes the card from the addressbook collection. */
    public void deleteCard(Transport transport, Account account, BookCard card) {
        Replies.object(
                Native.deleteCard(
                        transport,
                        account.baseUrl,
                        card.bookUrl(),
                        account.login,
                        account.password,
                        card.card().id(),
                        card.card().uri() == null ? "" : card.card().uri(),
                        card.card().etag() == null ? "" : card.card().etag()));
    }

    /**
     * Creates a batch of cards (Google-only: the one backend with a
     * batch create verb), returning the created cards in input order
     * with their server-assigned ids.
     */
    public List<Card> createCards(Transport transport, Account account, List<String> vcards) {
        return cards(
                Native.createCards(
                        transport,
                        account.baseUrl,
                        account.login,
                        account.password,
                        new JSONArray(vcards).toString()));
    }

    /** Deletes a batch of cards by id (Google-only, like createCards). */
    public void deleteCards(Transport transport, Account account, List<String> ids) {
        Replies.object(
                Native.deleteCards(
                        transport,
                        account.baseUrl,
                        account.login,
                        account.password,
                        new JSONArray(ids).toString()));
    }

    /**
     * Pushes a round of changes as batch calls (JMAP ContactCard/set,
     * Graph $batch), returning one {@code {ref, accepted, id?, etag?,
     * error?}} outcome per change: a rejected change reports rejected
     * instead of failing the round.
     */
    public List<PushOutcome> pushCards(
            Transport transport,
            Account account,
            String addressbookUrl,
            List<PushChange> changes) {
        JSONArray batch = new JSONArray();
        for (PushChange change : changes) {
            batch.put(change.toJson());
        }
        return PushOutcome.list(
                Replies.array(
                        Native.pushCards(
                                transport,
                                account.baseUrl,
                                addressbookUrl,
                                account.login,
                                account.password,
                                batch.toString())));
    }

    /** Serializes a book id list for the bridge. */
    private static String books(List<String> ids) {
        return new JSONArray(ids).toString();
    }

    /** Parses a card-array reply. */
    private static List<Card> cards(String reply) {
        return Card.list(Replies.array(reply));
    }

    /**
     * Projects the card's vCard onto the neutral field model the app
     * maps to ContactsContract rows (docs/contacts-mapping.md).
     */
    public static JSONObject projectCard(Card card) {
        return Replies.object(Native.projectCard(card.vcard()));
    }

    /** Projects a raw vCard document onto the neutral field model. */
    public static JSONObject projectCard(String vcard) {
        return Replies.object(Native.projectCard(vcard));
    }

    /**
     * Indexes a vCard for the store: the display fields the contacts
     * list renders (name, first email and phone, UID) and a normalized
     * content hash for the divergence flag of linked replicas.
     */
    public static JSONObject indexCard(String vcard) {
        return Replies.object(Native.indexCard(vcard));
    }

    /**
     * Patches an edited field model back onto the vCard, preserving
     * every property the model does not manage.
     */
    public static String applyCard(String vcard, JSONObject model) {
        return Json.string(
                Replies.object(Native.applyCard(vcard, model.toString())), "vcard");
    }

    /**
     * Merges several vCard documents into one union: the merged
     * document, its field model (the merge form prefill) and the
     * per-field alternative values.
     */
    public static JSONObject mergeCards(List<String> vcards) {
        JSONArray cards = new JSONArray();
        for (String vcard : vcards) {
            cards.put(vcard);
        }
        return Replies.object(Native.mergeCards(cards.toString()));
    }

    /**
     * Three-way merges a conflicted push: the staged local edit and
     * the fetched remote card against their common base (empty means
     * unknown; the local side stands in). The local side wins
     * same-field collisions; every other remote change flows in.
     */
    public static String mergeCardChanges(String base, String local, String remote) {
        return Json.string(
                Replies.object(Native.mergeCardChanges(base, local, remote)), "vcard");
    }

    /**
     * Builds the conflict form's inputs for a both-sides-edited row: the
     * merged document with the newer side (by REV) winning collisions as
     * the pre-filled default, its field model, the two candidate values of
     * every genuinely conflicted field, and an (always empty, non-null)
     * changed list. An empty {@code alternatives} means nothing needs the
     * user.
     */
    public static JSONObject mergeConflictForm(String base, String local, String remote) {
        return Replies.object(Native.mergeConflictForm(base, local, remote));
    }

    /**
     * Rewrites the card's UID, preserving every other byte (a plain
     * copy is a new identity).
     */
    public static String setCardUid(String vcard, String uid) {
        return Json.string(
                Replies.object(Native.setCardUid(vcard, uid)), "vcard");
    }

    /**
     * Finds groups of likely-duplicate cards among {@code {ref,
     * vcard}} pairs: exact normalized email, phone or full-name
     * matches, conservative on purpose.
     */
    public static JSONArray findDuplicates(JSONArray cards) {
        return Replies.object(Native.findDuplicates(cards.toString()))
                .optJSONArray("groups");
    }

    /**
     * The ordered type-set vocabulary the edit form's spinners address
     * for the kind ({@code phone}, {@code email}, {@code address},
     * {@code relation}, {@code gender}): each position's vCard TYPE set
     * (a one-element list of the sex code for {@code gender}), in the
     * order the Android string-arrays mirror. The Rust side owns the
     * order; a test pins the arrays to it.
     */
    public static JSONArray cardTypeOrder(String kind) {
        return Replies.object(Native.cardTypeOrder(kind)).optJSONArray("order");
    }

    /**
     * The edit form's view support computed from the field model:
     * summaries, type spinner positions and picker dates.
     */
    public static JSONObject formView(JSONObject model) {
        return Replies.object(Native.formView(model.toString()));
    }

    /**
     * One typed entry saved from an edit dialog, its TYPE set drawn
     * from the spinner position.
     */
    public static JSONObject formEntry(String kind, int index, String value, boolean pref) {
        return Replies.object(Native.formEntry(kind, index, value, pref));
    }

    /**
     * One picked date on the model wire (the vCard {@code yyyy-mm-dd}
     * form, 1-based month).
     */
    public static String formDate(int year, int month, int day) {
        return Json.string(
                Replies.object(Native.formDate(year, month, day)), "value");
    }

    /**
     * Groups the replica pool ({@code {replicas, links, detached}})
     * into merged contacts, the groups sorted by primary display name.
     */
    public static JSONObject groupContacts(JSONObject input) {
        return Replies.object(Native.groupContacts(input.toString()));
    }

    /**
     * The duplicate review's group facts ({@code {key, linkable}})
     * from its {@code {ref, book}} members.
     */
    public static JSONObject duplicateGroup(JSONArray members) {
        return Replies.object(Native.duplicateGroup(members.toString()));
    }
}
