package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The edit form's view support of one field model.
 *
 * <p>Mirrors rs/contact/form.rs {@code FormView} 48: summaries, spinner
 * positions and picker dates computed bridge-side, so the form only localizes
 * labels. Position lists run parallel to the model's lists.
 *
 * @param addresses each address row's spinner position and summary
 * @param anniversary the anniversary as picker parts, null unless a full date
 * @param birthday the birthday as picker parts, null unless a full date
 * @param emails each email row's spinner position
 * @param gender the gender row, null when the model has no gender
 * @param name the composed name summary
 * @param organization the organization summary
 * @param phones each phone row's spinner position
 * @param relations each relation row's spinner position
 */
public record FormView(
        List<FormAddress> addresses,
        FormDate anniversary,
        FormDate birthday,
        List<Integer> emails,
        FormGender gender,
        String name,
        String organization,
        List<Integer> phones,
        List<Integer> relations) {
    static FormView from(JSONObject view) {
        JSONArray rows = view.optJSONArray("addresses");
        List<FormAddress> addresses = new ArrayList<>(rows == null ? 0 : rows.length());
        for (int index = 0; rows != null && index < rows.length(); index++) {
            addresses.add(FormAddress.from(Json.object(rows, index)));
        }

        JSONObject anniversary = view.optJSONObject("anniversary");
        JSONObject birthday = view.optJSONObject("birthday");
        JSONObject gender = view.optJSONObject("gender");
        return new FormView(
                List.copyOf(addresses),
                anniversary == null ? null : FormDate.from(anniversary),
                birthday == null ? null : FormDate.from(birthday),
                List.copyOf(Json.integers(view.optJSONArray("emails"))),
                gender == null ? null : FormGender.from(gender),
                view.optString("name"),
                view.optString("organization"),
                List.copyOf(Json.integers(view.optJSONArray("phones"))),
                List.copyOf(Json.integers(view.optJSONArray("relations"))));
    }
}
