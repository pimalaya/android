package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * What one page of a mailbox's listing is asked: the engine's enumerate
 * yield, with what only the store knows.
 *
 * <p>Mirrors rs/mail/listing.rs {@code MailRequest} 50 (pimdir SYNC 4, 5).
 *
 * @param covered whether the store's coverage holds the scope already (Graph
 *     tells a first chunk from the round making its delta link)
 * @param listing a delta from the checkpoint, or a round over the scope
 * @param received the oldest reception the account holds, in epoch
 *     milliseconds (where a Gmail round lists from); null for none
 * @param scope the {@code Date} range listed
 */
public record MailRequest(boolean covered, Listing listing, Long received, Scope scope) {
    /**
     * A delta or a round (rs/mail/listing.rs {@code Listing} 73).
     *
     * @param kind {@code delta} or {@code round}
     * @param checkpoint a delta's checkpoint; null on a round
     * @param cursor where a round resumes, null for its first page
     * @param band whether a round lists only the band a coverage lacks
     */
    public record Listing(String kind, String checkpoint, String cursor, boolean band) {
        /** Whether this is a round, else a delta. */
        public boolean round() {
            return "round".equals(kind);
        }

        JSONObject toJson() throws JSONException {
            JSONObject listing = new JSONObject().put("kind", kind);
            if (round()) {
                return listing.put("cursor", cursor == null ? JSONObject.NULL : cursor)
                        .put("band", band);
            }
            return listing.put("checkpoint", checkpoint);
        }
    }

    /**
     * A scope {@code [since, until)} on the {@code Date} header, RFC 3339
     * instants in UTC (rs/mail/listing.rs {@code Scope} 90).
     *
     * @param since the inclusive floor; null for none
     * @param until the exclusive ceiling; null for none
     */
    public record Scope(String since, String until) {
        JSONObject toJson() throws JSONException {
            JSONObject scope = new JSONObject();
            if (since != null) {
                scope.put("since", since);
            }
            if (until != null) {
                scope.put("until", until);
            }
            return scope;
        }
    }

    JSONObject toJson() {
        try {
            JSONObject request =
                    new JSONObject()
                            .put("listing", listing.toJson())
                            .put("scope", scope.toJson())
                            .put("covered", covered);
            if (received != null) {
                request.put("received", received);
            }
            return request;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable listing request: " + error.getMessage());
        }
    }
}
