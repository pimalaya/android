package org.pimalaya.contact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pimalaya.client.Account;
import org.pimalaya.client.BookCard;
import org.pimalaya.client.BooksUpdate;
import org.pimalaya.client.Card;
import org.pimalaya.client.CardDelta;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.NewCard;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.client.Transport;

/** A People account: persons in groups, refusing as Google does. */
final class FakePeople extends ContactClient {
    final List<String> calls = new ArrayList<>();
    final Map<String, String> bodies = new LinkedHashMap<>();
    final Map<String, Set<String>> groups = new LinkedHashMap<>();
    final Map<String, Integer> revisions = new HashMap<>();
    int created;

    Card card(String id) {
        return new Card(
                id,
                id,
                "e-" + id + "-" + revisions.get(id),
                bodies.get(id),
                new ArrayList<>(groups.get(id)));
    }

    /** The person a resource name addresses; Google refuses any other. */
    private void person(String id) {
        if (!id.startsWith("c")) {
            throw new PimalayaException(
                    "People API returned HTTP 400: Resource name \"people/" + id
                            + "\" is not a valid contact person resource",
                    400);
        }
        if (!bodies.containsKey(id)) {
            throw new PimalayaException(
                    "People API returned HTTP 404: Requested entity was not found", 404);
        }
    }

    private Card create(String vcard) {
        String id = "c" + ++created;
        bodies.put(id, vcard);
        groups.put(id, new LinkedHashSet<>(List.of("myContacts")));
        revisions.put(id, 0);
        calls.add("createCard " + id);
        return card(id);
    }

    /** An edit made on the server: the person at a new revision. */
    void edit(String id, String vcard) {
        bodies.put(id, vcard);
        revisions.put(id, revisions.get(id) + 1);
    }

    /** Listings answered before they fail; negative never fails. */
    int failAfter = -1;

    @Override
    public CardDelta syncCards(
            Transport transport, Account account, String addressbookUrl, String syncToken) {
        if (failAfter == 0) {
            throw new PimalayaException("People API returned HTTP 503: unavailable", 503);
        }
        if (failAfter > 0) {
            failAfter--;
        }
        List<Card> cards = new ArrayList<>();
        for (String id : bodies.keySet()) {
            cards.add(card(id));
        }
        Runnable landing = afterListing;
        afterListing = null;
        if (landing != null) {
            landing.run();
        }
        return new CardDelta(cards, List.of(), "t", true);
    }

    /** A change made on the server once the next listing is answered. */
    Runnable afterListing;

    @Override
    public List<Card> readGoogleCards(
            Transport transport, Account account, List<String> ids) {
        List<Card> cards = new ArrayList<>();
        for (String id : ids) {
            if (bodies.containsKey(id)) {
                cards.add(card(id));
            }
        }
        return cards;
    }

    @Override
    public Card createCard(
            Transport transport, Account account, String addressbookUrl, NewCard card) {
        return create(card.vcard());
    }

    @Override
    public List<Card> createCards(Transport transport, Account account, List<String> vcards) {
        List<Card> cards = new ArrayList<>();
        for (String vcard : vcards) {
            cards.add(create(vcard));
        }
        return cards;
    }

    /** Refuses the next guarded update once, as a conflict. */
    boolean refuseGuard;

    /** Refuses every update as a conflict, as a copy People reads apart. */
    boolean refuseAll;

    /** Membership patches failing before they apply, as People's 503. */
    int failBooks;

    /** As the bridge: guarded by the etag, else merged against the base. */
    @Override
    public Card updateCard(
            Transport transport, Account account, BookCard target, String baseVcard) {
        Card card = target.card();
        String id = card.uri() == null || card.uri().isEmpty() ? card.id() : card.uri();
        person(id);
        if (refuseAll || refuseGuard && card.etag() != null) {
            refuseGuard = false;
            calls.add("updateCard " + id + " refused");
            throw new PimalayaException("People API returned HTTP 412", 412);
        }
        String held = bodies.get(id);
        if (!card(id).etag().equals(card.etag())) {
            if (held.equals(card.vcard())) {
                return card(id);
            }
            if (!held.equals(baseVcard)) {
                calls.add("updateCard " + id + " refused");
                throw new PimalayaException(
                        "People contact " + id + " changed since the base of its edit", 412);
            }
        }
        calls.add("updateCard " + id);
        edit(id, card.vcard());
        return card(id);
    }

    /** As the bridge: adds first, then removals, never the last group. */
    @Override
    public void updateCardBooks(Transport transport, Account account, BooksUpdate update) {
        String id = update.cardId();
        person(id);
        if (failBooks > 0) {
            failBooks--;
            throw new PimalayaException("People API returned HTTP 503: unavailable", 503);
        }
        calls.add("updateCardBooks " + id + " +" + update.add() + " -" + update.remove());
        Set<String> held = groups.get(id);
        held.addAll(update.add());
        for (String group : update.remove()) {
            if (held.size() == 1 && held.contains(group)) {
                throw new PimalayaException(
                        "Google refused to remove the contact from its last group");
            }
            held.remove(group);
        }
        // NOTE: memberships are person fields; the next listing names
        // the person at a new revision, and People answers none.
        revisions.put(id, revisions.get(id) + 1);
    }

    @Override
    public void deleteCard(Transport transport, Account account, BookCard target) {
        Card card = target.card();
        String id = card.uri() == null || card.uri().isEmpty() ? card.id() : card.uri();
        deleteCards(transport, account, List.of(id));
    }

    @Override
    public void deleteCards(Transport transport, Account account, List<String> ids) {
        for (String id : ids) {
            person(id);
            calls.add("deleteCard " + id);
            bodies.remove(id);
            groups.remove(id);
        }
    }
}
