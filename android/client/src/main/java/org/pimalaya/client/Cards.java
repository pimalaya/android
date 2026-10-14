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

    /** Forwards to {@link OfflineClient#offlineRetryUnguarded}. */
    public static boolean offlineRetryUnguarded(JSONObject facts) {
        return OfflineClient.offlineRetryUnguarded(facts);
    }

    /** Forwards to {@link OfflineClient#offlineAccountSnapshot}. */
    public static JSONObject offlineAccountSnapshot(JSONObject facts) {
        return OfflineClient.offlineAccountSnapshot(facts);
    }

    /** Forwards to {@link OfflineClient#offlinePushPlan}. */
    public static JSONObject offlinePushPlan(JSONObject facts) {
        return OfflineClient.offlinePushPlan(facts);
    }

    /** Forwards to {@link ContactClient#projectCard}. */
    public static JSONObject projectCard(Card card) {
        return ContactClient.projectCard(card);
    }

    /** Forwards to {@link ContactClient#projectCard}. */
    public static JSONObject projectCard(String vcard) {
        return ContactClient.projectCard(vcard);
    }

    /** Forwards to {@link ContactClient#indexCard}. */
    public static JSONObject indexCard(String vcard) {
        return ContactClient.indexCard(vcard);
    }

    /** Forwards to {@link ContactClient#applyCard}. */
    public static String applyCard(String vcard, JSONObject model) {
        return ContactClient.applyCard(vcard, model);
    }

    /** Forwards to {@link ContactClient#mergeCards}. */
    public static JSONObject mergeCards(List<String> vcards) {
        return ContactClient.mergeCards(vcards);
    }

    /** Forwards to {@link ContactClient#mergeCardChanges}. */
    public static String mergeCardChanges(String base, String local, String remote) {
        return ContactClient.mergeCardChanges(base, local, remote);
    }

    /** Forwards to {@link ContactClient#mergeConflictForm}. */
    public static JSONObject mergeConflictForm(String base, String local, String remote) {
        return ContactClient.mergeConflictForm(base, local, remote);
    }

    /** Forwards to {@link ContactClient#setCardUid}. */
    public static String setCardUid(String vcard, String uid) {
        return ContactClient.setCardUid(vcard, uid);
    }

    /** Forwards to {@link ContactClient#findDuplicates}. */
    public static JSONArray findDuplicates(JSONArray cards) {
        return ContactClient.findDuplicates(cards);
    }

    /** Forwards to {@link ContactClient#cardProps}. */
    public static JSONArray cardProps(String vcard) {
        return ContactClient.cardProps(vcard);
    }

    /** Forwards to {@link ContactClient#cardSetProp}. */
    public static String cardSetProp(String vcard, int index, String line) {
        return ContactClient.cardSetProp(vcard, index, line);
    }

    /** Forwards to {@link ContactClient#cardSetPropParts}. */
    public static String cardSetPropParts(String vcard, int index, JSONObject prop) {
        return ContactClient.cardSetPropParts(vcard, index, prop);
    }

    /** Forwards to {@link ContactClient#cardPropLabels}. */
    public static JSONArray cardPropLabels(String name) {
        return ContactClient.cardPropLabels(name);
    }

    /** Forwards to {@link ContactClient#cardTypeOrder}. */
    public static JSONArray cardTypeOrder(String kind) {
        return ContactClient.cardTypeOrder(kind);
    }

    /** Forwards to {@link ContactClient#cardSource}. */
    public static String cardSource(String vcard) {
        return ContactClient.cardSource(vcard);
    }

    /** Forwards to {@link ContactClient#formView}. */
    public static JSONObject formView(JSONObject model) {
        return ContactClient.formView(model);
    }

    /** Forwards to {@link ContactClient#formEntry}. */
    public static JSONObject formEntry(String kind, int index, String value, boolean pref) {
        return ContactClient.formEntry(kind, index, value, pref);
    }

    /** Forwards to {@link ContactClient#formDate}. */
    public static String formDate(int year, int month, int day) {
        return ContactClient.formDate(year, month, day);
    }

    /** Forwards to {@link ContactClient#groupContacts}. */
    public static JSONObject groupContacts(JSONObject input) {
        return ContactClient.groupContacts(input);
    }

    /** Forwards to {@link ContactClient#duplicateGroup}. */
    public static JSONObject duplicateGroup(JSONArray members) {
        return ContactClient.duplicateGroup(members);
    }
}
