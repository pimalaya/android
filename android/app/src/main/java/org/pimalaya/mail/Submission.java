package org.pimalaya.mail;

import android.util.Log;

import org.json.JSONObject;
import org.pimalaya.pimdir.PimdirQueue;

/**
 * The payload of a submission: what this app queues.
 *
 * <p>It carries what a listing draws as well as what the drain needs,
 * because a waiting message is not an item and there is no summary row
 * beside it to read a subject and a date off. The bytes would say all
 * of this, and parsing a message to draw a list is the one thing the
 * sort key exists to avoid.
 *
 * <p>The queue keeps the payload as the text it was written as: this reads
 * it and writes it, and a row sent again is queued with its text as it is.
 *
 * @param v the payload version it was written with
 * @param from the account it goes out from
 * @param messageId the {@code Message-ID} it was stamped with, bare
 * @param subject its subject
 * @param sortKey the sort key of its date
 */
record Submission(int v, String from, String messageId, String subject, String sortKey) {
    /** The payload version this app writes and reads. */
    private static final int VERSION = 1;

    /** A submission this app queues, at the version it writes. */
    static Submission of(String from, String messageId, String subject, String sortKey) {
        return new Submission(VERSION, from, messageId, subject, sortKey);
    }

    /**
     * The submission one queued action carries, read off the payload it
     * was written with; every part empty when it cannot be read back.
     */
    static Submission from(PimdirQueue.Action action) {
        JSONObject payload = payload(action.payload());
        return new Submission(
                payload.optInt("v"),
                payload.optString("from"),
                payload.optString("messageId"),
                payload.optString("subject"),
                payload.optString("sortKey"));
    }

    /** The payload text the queue keeps. */
    String text() {
        try {
            return new JSONObject()
                    .put("v", v)
                    .put("from", from)
                    .put("messageId", messageId)
                    .put("subject", subject)
                    .put("sortKey", sortKey)
                    .toString();
        } catch (org.json.JSONException error) {
            throw new IllegalStateException("Could not write the submission payload", error);
        }
    }

    /** A payload as it was written, empty when it cannot be read back. */
    private static JSONObject payload(String json) {
        if (json == null) {
            return new JSONObject();
        }
        try {
            return new JSONObject(json);
        } catch (org.json.JSONException error) {
            Log.w("pimalaya", "unreadable queue payload: " + error.getMessage());
            return new JSONObject();
        }
    }
}
