package org.pimalaya.client;

import org.json.JSONObject;

/**
 * A model date as the edit form's picker parts.
 *
 * <p>Mirrors rs/contact/form.rs {@code FormDate} 83. A key the reply leaves
 * out reads as zero.
 *
 * @param day the day of the month
 * @param month the month, 1-based
 * @param year the year
 */
public record FormDate(int day, int month, int year) {
    static FormDate from(JSONObject date) {
        return new FormDate(date.optInt("day"), date.optInt("month"), date.optInt("year"));
    }
}
