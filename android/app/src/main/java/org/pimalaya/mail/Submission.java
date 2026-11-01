package org.pimalaya.mail;

import android.util.Log;

import org.json.JSONObject;
import org.pimalaya.client.Derived;
import org.pimalaya.client.PimdirSql;
import org.pimalaya.pimdir.PimdirSummary;

/**
 * What a listing draws of one queued submission.
 *
 * <p>The queued payload is Annex B.2's {@code submit} and nothing more ({@link
 * #payload}): what the app needs beyond it, the message's {@code Message-ID},
 * subject and date, is derived from the message, the row's body (STORAGE Annex
 * A), never added to the payload.
 *
 * @param from the envelope sender the payload names, the account's address
 * @param messageId the {@code Message-ID} it was stamped with, bare
 * @param subject the subject, empty for none
 * @param sortKey its sort key, empty when the message states no date
 */
record Submission(String from, String messageId, String subject, String sortKey) {
    /** The source performing every submission this app queues (§15.6). */
    static final String SOURCE = "server";

    /**
     * The {@code submit} payload queuing {@code message} from {@code from}:
     * its recipients and subject read off the message by io-pimdir's rules.
     */
    static String payload(String from, byte[] message) {
        return PimdirSql.submit(SOURCE, from, message);
    }

    /**
     * The submission a payload and its message make, every part empty when
     * one cannot be read back.
     */
    static Submission of(String payload, byte[] message) {
        String from = senderOf(payload);
        if (message == null) {
            return new Submission(from, "", "", "");
        }
        Derived derived;
        try {
            derived = PimdirSql.derive(PimdirSummary.MAIL, message);
        } catch (RuntimeException unreadable) {
            Log.w("pimalaya", "unreadable queued message: " + unreadable.getMessage());
            return new Submission(from, "", "", "");
        }
        JSONObject summary = derived.summary();
        JSONObject mail = summary == null ? null : summary.optJSONObject("mail");
        String subject =
                mail == null || mail.isNull("subject") ? "" : mail.optString("subject");
        return new Submission(from, derived.linkId(), subject, derived.sortKey());
    }

    /** The envelope sender a payload names, empty when it names none. */
    private static String senderOf(String payload) {
        if (payload == null) {
            return "";
        }
        try {
            JSONObject json = new JSONObject(payload);
            return json.isNull("from") ? "" : json.optString("from");
        } catch (org.json.JSONException error) {
            Log.w("pimalaya", "unreadable queue payload: " + error.getMessage());
            return "";
        }
    }
}
