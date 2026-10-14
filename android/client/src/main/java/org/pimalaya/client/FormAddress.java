package org.pimalaya.client;

import org.json.JSONObject;

/**
 * One address row of the edit form's view support.
 *
 * <p>Mirrors rs/contact/form.rs {@code FormAddress} 74. A key the reply leaves
 * out reads as zero or empty.
 *
 * @param index the type spinner position
 * @param summary the row summary
 */
public record FormAddress(int index, String summary) {
    static FormAddress from(JSONObject row) {
        return new FormAddress(row.optInt("index"), row.optString("summary"));
    }
}
