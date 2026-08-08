package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The transport-free half of the bridge: pure vCard computations and
 * io-offline plan verbs (facts in, decision out) that never open a
 * socket. Static on purpose, mirroring the stateless bridge: every
 * call crosses JNI with everything it needs and returns everything it
 * produced. The network operations and the engine drives stay on
 * {@link PimalayaClient}.
 */
public final class Cards {
    private Cards() {}

    /**
     * Whether a 412-rejected push may retry unguarded, the last
     * enumerate proving the handle unchanged (the CardDAV If-Match
     * quirk).
     */
    public static boolean offlineRetryUnguarded(JSONObject facts) {
        return PimalayaClient.object(Native.offlineRetryUnguarded(facts.toString()))
                .optBoolean("retry");
    }

    /**
     * Projects an account-wide delta (JMAP, Google) onto one book's
     * enumerate ({@code {members, vanished}}).
     */
    public static JSONObject offlineAccountSnapshot(JSONObject facts) {
        return PimalayaClient.object(Native.offlineAccountSnapshot(facts.toString()));
    }

    /** Plans one push change ({@code {action, postCreateBooks?}}). */
    public static JSONObject offlinePushPlan(JSONObject facts) {
        return PimalayaClient.object(Native.offlinePushPlan(facts.toString()));
    }

    /**
     * Projects the card's vCard onto the neutral field model the app
     * maps to ContactsContract rows (docs/contacts-mapping.md).
     */
    public static JSONObject projectCard(Card card) {
        return PimalayaClient.object(Native.projectCard(card.vcard));
    }

    /** Projects a raw vCard document onto the neutral field model. */
    public static JSONObject projectCard(String vcard) {
        return PimalayaClient.object(Native.projectCard(vcard));
    }

    /**
     * Indexes a vCard for the store: the display fields the contacts
     * list renders (name, first email and phone, UID) and a normalized
     * content hash for the divergence flag of linked replicas.
     */
    public static JSONObject indexCard(String vcard) {
        return PimalayaClient.object(Native.indexCard(vcard));
    }

    /**
     * Patches an edited field model back onto the vCard, preserving
     * every property the model does not manage.
     */
    public static String applyCard(String vcard, JSONObject model) {
        return PimalayaClient.string(
                PimalayaClient.object(Native.applyCard(vcard, model.toString())), "vcard");
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
        return PimalayaClient.object(Native.mergeCards(cards.toString()));
    }

    /**
     * Three-way merges a conflicted push: the staged local edit and
     * the fetched remote card against their common base (empty means
     * unknown; the local side stands in). The local side wins
     * same-field collisions; every other remote change flows in.
     */
    public static String mergeCardChanges(String base, String local, String remote) {
        return PimalayaClient.string(
                PimalayaClient.object(Native.mergeCardChanges(base, local, remote)), "vcard");
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
        return PimalayaClient.object(Native.mergeConflictForm(base, local, remote));
    }

    /**
     * Rewrites the card's UID, preserving every other byte (a plain
     * copy is a new identity).
     */
    public static String setCardUid(String vcard, String uid) {
        return PimalayaClient.string(
                PimalayaClient.object(Native.setCardUid(vcard, uid)), "vcard");
    }

    /**
     * Finds groups of likely-duplicate cards among {@code {ref,
     * vcard}} pairs: exact normalized email, phone or full-name
     * matches, conservative on purpose.
     */
    public static JSONArray findDuplicates(JSONArray cards) {
        return PimalayaClient.object(Native.findDuplicates(cards.toString()))
                .optJSONArray("groups");
    }

    /**
     * Lists the card's raw property lines for the advanced editor, in
     * source order, unfolded.
     */
    public static JSONArray cardProps(String vcard) {
        return PimalayaClient.object(Native.cardProps(vcard)).optJSONArray("props");
    }

    /**
     * Rewrites one raw property line for the advanced editor: the line
     * replaces the property at the index (a blank line removes it),
     * index -1 appends.
     */
    public static String cardSetProp(String vcard, int index, String line) {
        return PimalayaClient.string(
                PimalayaClient.object(Native.cardSetProp(vcard, index, line)), "vcard");
    }

    /**
     * Recomposes one property from its structured parts ({@code {name,
     * params: [{name, values}], value}}) and rewrites it (index -1
     * appends).
     */
    public static String cardSetPropParts(String vcard, int index, JSONObject prop) {
        return PimalayaClient.string(
                PimalayaClient.object(Native.cardSetPropParts(vcard, index, prop.toString())),
                "vcard");
    }

    /**
     * The component labels of a structured property name (N, ADR,
     * GENDER), empty for plain values: the advanced editor shapes its
     * value form from them.
     */
    public static JSONArray cardPropLabels(String name) {
        return PimalayaClient.object(Native.cardPropLabels(name)).optJSONArray("labels");
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
        return PimalayaClient.object(Native.cardTypeOrder(kind)).optJSONArray("order");
    }

    /**
     * Validates a hand-edited vCard source (it must reparse) and
     * returns it re-serialized.
     */
    public static String cardSource(String vcard) {
        return PimalayaClient.string(PimalayaClient.object(Native.cardSource(vcard)), "vcard");
    }

    /**
     * The edit form's view support computed from the field model:
     * summaries, type spinner positions and picker dates.
     */
    public static JSONObject formView(JSONObject model) {
        return PimalayaClient.object(Native.formView(model.toString()));
    }

    /**
     * One typed entry saved from an edit dialog, its TYPE set drawn
     * from the spinner position.
     */
    public static JSONObject formEntry(String kind, int index, String value, boolean pref) {
        return PimalayaClient.object(Native.formEntry(kind, index, value, pref));
    }

    /**
     * One picked date on the model wire (the vCard {@code yyyy-mm-dd}
     * form, 1-based month).
     */
    public static String formDate(int year, int month, int day) {
        return PimalayaClient.string(
                PimalayaClient.object(Native.formDate(year, month, day)), "value");
    }

    /**
     * Groups the replica pool ({@code {replicas, links, detached}})
     * into merged contacts, the groups sorted by primary display name.
     */
    public static JSONObject groupContacts(JSONObject input) {
        return PimalayaClient.object(Native.groupContacts(input.toString()));
    }

    /**
     * The duplicate review's group facts ({@code {key, linkable}})
     * from its {@code {ref, book}} members.
     */
    public static JSONObject duplicateGroup(JSONArray members) {
        return PimalayaClient.object(Native.duplicateGroup(members.toString()));
    }
}
