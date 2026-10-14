package org.pimalaya.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;

/**
 * The contact verbs of the bridge (rs/ffi/contact.rs) over four backends.
 *
 * <p>CardDAV, Graph, JMAP (RFC 9610) and People, told apart bridge-side by
 * the opaque base URL. Calls taking a transport block (off the main thread);
 * Graph and JMAP updates are unguarded, CardDAV and Google ETag-guarded.
 */
public class ContactClient {
    /**
     * Lists the account's addressbooks, the backend's own way.
     *
     * <p>CardDAV discovery, Graph folders (default first), JMAP AddressBooks
     * or Google contact groups.
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
     * The capability URNs a JMAP session serves the account, read afresh.
     *
     * <p>The connection flow connects a domain over JMAP by them.
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
     * Lists a collection's changes since the cursor; null runs a full round.
     *
     * <p>RFC 6578 REPORT, Graph delta (ids only), JMAP changes or People sync
     * (cards in full). An expired cursor re-runs a full round bridge-side,
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

    /** Batch-fetches named cards of a CardDAV book (addressbook-multiget). */
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
     * Reads the named Graph contacts, 20 to a {@code $batch}; Graph only.
     *
     * <p>A contact Graph no longer holds is left out, as a multiget does.
     */
    public List<Card> readGraphCards(Transport transport, Account account, List<String> ids) {
        return cards(
                Native.readGraphCards(transport, account.password, new JSONArray(ids).toString()));
    }

    /**
     * Reads the named People contacts, 200 to a batchGet; Google only.
     *
     * <p>A contact Google no longer holds is left out, as a multiget does.
     */
    public List<Card> readGoogleCards(Transport transport, Account account, List<String> ids) {
        return cards(
                Native.readGoogleCards(
                        transport, account.password, new JSONArray(ids).toString()));
    }

    /**
     * Adds and removes a card's book memberships on an account-level backend.
     *
     * <p>JMAP patches addressBookIds, Google modifies group members.
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
     * Creates the card in the addressbook, returning it with its ETag.
     *
     * <p>Backends naming their own resources return their id, not the given.
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

    /** Reads the card at a resource name from the addressbook. */
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
     * Updates the card in the addressbook, returning it with its new ETag.
     *
     * <p>{@code baseVcard} (last synced state, null if unknown) trims Graph,
     * JMAP and Google patches to the changed fields; CardDAV PUTs it whole.
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
     * Creates a batch of cards, Google only, returned in input order.
     *
     * <p>Each carries its server-assigned id.
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
     * Pushes a round of changes as batch calls (JMAP set, Graph $batch).
     *
     * <p>One {@link PushOutcome} per change: a rejected change is reported,
     * not a failed round.
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
     * Projects a vCard onto the neutral field model.
     *
     * <p>The model the app maps to ContactsContract rows
     * (docs/contacts-mapping.md).
     */
    public static ContactFields projectCard(String vcard) {
        return ContactFields.from(Replies.object(Native.projectCard(vcard)));
    }

    /**
     * Indexes a vCard for the store: display fields and content hash.
     *
     * <p>The hash flags linked replicas that diverge.
     */
    public static CardIndex indexCard(String vcard) {
        return CardIndex.from(Replies.object(Native.indexCard(vcard)));
    }

    /** Patches an edited field model onto the vCard, other properties kept. */
    public static String applyCard(String vcard, ContactFields model) {
        return Json.string(
                Replies.object(Native.applyCard(vcard, model.toJson().toString())), "vcard");
    }

    /** Merges several vCards into one union, with its form inputs. */
    public static CardsMerge mergeCards(List<String> vcards) {
        JSONArray cards = new JSONArray();
        for (String vcard : vcards) {
            cards.put(vcard);
        }
        return CardsMerge.from(Replies.object(Native.mergeCards(cards.toString())));
    }

    /**
     * Three-way merges a conflicted push: local edit and remote card.
     *
     * <p>An empty base is unknown, the local side standing in. Local wins
     * same-field collisions; every other remote change flows in.
     */
    public static ConflictMerge mergeCardChanges(String base, String local, String remote) {
        return ConflictMerge.from(Replies.object(Native.mergeCardChanges(base, local, remote)));
    }

    /**
     * Builds the conflict form's inputs for a both-sides-edited row.
     *
     * <p>The newer side by REV wins collisions as prefill. Empty
     * {@code alternatives} means nothing needs the user.
     */
    public static ConflictForm mergeConflictForm(String base, String local, String remote) {
        return ConflictForm.from(Replies.object(Native.mergeConflictForm(base, local, remote)));
    }

    /** Rewrites the card's UID, every other byte kept: a copy's identity. */
    public static String setCardUid(String vcard, String uid) {
        return Json.string(
                Replies.object(Native.setCardUid(vcard, uid)), "vcard");
    }

    /**
     * Finds groups of likely-duplicate cards.
     *
     * <p>Exact normalized email, phone or full-name matches, conservative on
     * purpose.
     */
    public static List<DuplicateSet> findDuplicates(List<RefCard> cards) {
        JSONArray input = new JSONArray();
        for (RefCard card : cards) {
            input.put(card.toJson());
        }
        JSONArray groups =
                Replies.object(Native.findDuplicates(input.toString())).optJSONArray("groups");

        List<DuplicateSet> sets = new ArrayList<>(groups == null ? 0 : groups.length());
        for (int index = 0; groups != null && index < groups.length(); index++) {
            sets.add(DuplicateSet.from(Json.object(groups, index)));
        }
        return sets;
    }

    /**
     * The ordered TYPE sets the edit form's spinners address for the kind.
     *
     * <p>The sex code alone for {@link TypeKind#GENDER}. Rust owns the order;
     * a test pins the Android string-arrays to it.
     */
    static List<List<String>> cardTypeOrder(TypeKind kind) {
        JSONArray order = Replies.object(Native.cardTypeOrder(kind.wire())).optJSONArray("order");

        List<List<String>> sets = new ArrayList<>(order == null ? 0 : order.length());
        for (int index = 0; order != null && index < order.length(); index++) {
            sets.add(List.copyOf(Json.strings(order.optJSONArray(index))));
        }
        return sets;
    }

    /** The edit form's view support computed from the field model. */
    public static FormView formView(ContactFields model) {
        return FormView.from(Replies.object(Native.formView(model.toJson().toString())));
    }

    /** One phone entry saved from an edit dialog, TYPE set from the spinner. */
    public static ContactPhone formPhone(int index, String number, boolean pref) {
        return ContactPhone.from(Replies.object(Native.formEntry("phone", index, number, pref)));
    }

    /** One email entry saved from an edit dialog, TYPE set from the spinner. */
    public static ContactEmail formEmail(int index, String address, boolean pref) {
        return ContactEmail.from(Replies.object(Native.formEntry("email", index, address, pref)));
    }

    /** One relation saved from an edit dialog, TYPE set from the spinner. */
    public static ContactRelation formRelation(int index, String value, boolean pref) {
        return ContactRelation.from(
                Replies.object(Native.formEntry("relation", index, value, pref)));
    }

    /** An address's TYPE set from an edit dialog's radio position. */
    public static List<String> formAddressTypes(int index) {
        return List.copyOf(
                Json.strings(
                        Replies.object(Native.formEntry("address", index, "", false))
                                .optJSONArray("types")));
    }

    /**
     * One GENDER saved from an edit dialog, sex code from the choice.
     *
     * <p>Both parts empty when sex and identity are unset.
     */
    public static ContactGender formGender(int index, String identity) {
        return ContactGender.from(
                Replies.object(Native.formEntry("gender", index, identity, false)));
    }

    /** One picked date on the model wire, {@code yyyy-mm-dd}, 1-based month. */
    public static String formDate(int year, int month, int day) {
        return Json.string(
                Replies.object(Native.formDate(year, month, day)), "value");
    }

    /** Groups the replica pool into merged contacts, by display name. */
    public static List<ContactGroup> groupContacts(ReplicaPool pool) {
        JSONArray groups =
                Replies.object(Native.groupContacts(pool.toJson().toString()))
                        .optJSONArray("groups");

        List<ContactGroup> grouped = new ArrayList<>(groups == null ? 0 : groups.length());
        for (int index = 0; groups != null && index < groups.length(); index++) {
            grouped.add(ContactGroup.from(Json.object(groups, index)));
        }
        return grouped;
    }

    /** The duplicate review's group facts from its members. */
    public static DuplicateGroup duplicateGroup(List<DuplicateMember> members) {
        JSONArray input = new JSONArray();
        for (DuplicateMember member : members) {
            input.put(member.toJson());
        }
        return DuplicateGroup.from(Replies.object(Native.duplicateGroup(input.toString())));
    }
}
