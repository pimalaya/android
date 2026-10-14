package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The ORG of the field model (rs/contact/fields.rs
 * {@code ContactOrganization} 192). A key the model leaves out reads as
 * empty.
 *
 * @param company the organization name, the first component
 * @param department the organizational units, the other components
 *     space-joined
 */
public record ContactOrganization(String company, String department) {
    static ContactOrganization from(JSONObject organization) {
        return new ContactOrganization(
                organization.optString("company"), organization.optString("department"));
    }

    JSONObject toJson() throws JSONException {
        JSONObject organization = new JSONObject();
        organization.put("company", company);
        organization.put("department", department);
        return organization;
    }
}
