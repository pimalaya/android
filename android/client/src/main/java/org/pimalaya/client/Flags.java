package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;

/**
 * The marker set of one message, in the order the store holds it.
 *
 * <p>The IMAP flags this app writes ({@link Marker}) and any server keyword,
 * verbatim. The bridge speaks it and the store's {@code flags} column holds
 * it, a JSON array of strings.
 *
 * @param markers the markers, as the store's JSON array spells them
 */
public record Flags(List<String> markers) {
    /** The markers this app writes. */
    public enum Marker {
        /** The IMAP {@code \Seen} flag. */
        SEEN("\\Seen"),
        /** The IMAP {@code \Answered} flag: the message was replied to. */
        ANSWERED("\\Answered"),
        /** The IMAP {@code \Flagged} flag: the message was marked important. */
        FLAGGED("\\Flagged"),
        /**
         * The IMAP {@code \Deleted} flag: removed by a later expunge.
         *
         * <p>What deleting means on an account naming no trash to move into.
         */
        DELETED("\\Deleted");

        private final String wire;

        Marker(String wire) {
            this.wire = wire;
        }

        /** The marker as the store's JSON array spells it. */
        public String wire() {
            return wire;
        }
    }

    /** Copies the markers, immutable. */
    public Flags {
        markers = List.copyOf(markers);
    }

    /** No marker at all. */
    public static Flags none() {
        return new Flags(List.of());
    }

    /** The given markers, in order. */
    public static Flags of(Marker... markers) {
        List<String> spelled = new ArrayList<>(markers.length);
        for (Marker marker : markers) {
            spelled.add(marker.wire());
        }
        return new Flags(spelled);
    }

    /**
     * The marker set a store read answers as its {@code flags} column: the
     * {@code placement_flag} rows space-joined, in no order (pimdir STORAGE
     * §13); none if empty.
     *
     * @throws JSONException never since storage-draft-08, kept for callers
     *     written against the JSON column
     */
    public static Flags parse(String text) throws JSONException {
        List<String> markers = new ArrayList<>();
        if (text != null) {
            for (String flag : text.split(" ")) {
                if (!flag.isEmpty()) {
                    markers.add(flag);
                }
            }
        }
        return new Flags(markers);
    }

    /**
     * The marker set an offline-driver yield carries; none if it carries none.
     *
     * <p>The wire stays JSON until the engine names its own pages.
     */
    public static Flags read(JSONArray markers) {
        return new Flags(Json.strings(markers));
    }

    /** Whether a marker set names one marker. */
    public boolean has(Marker marker) {
        return markers.contains(marker.wire());
    }

    /** The marker set with one marker added or removed. */
    public Flags with(Marker marker, boolean add) {
        List<String> kept = new ArrayList<>(markers.size() + 1);
        for (String flag : markers) {
            if (!marker.wire().equals(flag)) {
                kept.add(flag);
            }
        }
        if (add) {
            kept.add(marker.wire());
        }
        return new Flags(kept);
    }

    /** The set as the store's JSON array spells it. */
    JSONArray toJson() {
        return new JSONArray(markers);
    }
}
