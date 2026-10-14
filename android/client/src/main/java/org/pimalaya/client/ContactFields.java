package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The neutral field model of one card, both ways across the bridge
 * (rs/contact/fields.rs {@code ContactFields} 52): what a vCard projects
 * to, what the phone rows and the edit form map, and what applies back
 * onto a vCard.
 *
 * <p>A null single-valued field is absent (Rust {@code None}); an empty
 * string is present and empty ({@code Some("")}), which the phone mapping
 * writes on purpose. The lists are never null, empty when the card has
 * none.
 *
 * @param addresses the ADR entries
 * @param anniversary the first full ANNIVERSARY, as {@code yyyy-mm-dd}
 * @param birthday the first full BDAY, as {@code yyyy-mm-dd}
 * @param categories the CATEGORIES values, projected but never applied
 * @param displayName the first FN
 * @param emails the EMAIL entries
 * @param gender the first GENDER
 * @param impps the IMPP URIs
 * @param languages the LANG tags
 * @param name the first N
 * @param nicknames the NICKNAME values, every property's list flattened
 * @param notes the NOTE values
 * @param organization the first ORG
 * @param phones the TEL entries
 * @param relations the RELATED entries
 * @param role the first ROLE
 * @param title the first TITLE
 * @param uid the first UID, projected but never applied
 * @param websites the URL values
 */
public record ContactFields(
        List<ContactAddress> addresses,
        String anniversary,
        String birthday,
        List<String> categories,
        String displayName,
        List<ContactEmail> emails,
        ContactGender gender,
        List<String> impps,
        List<String> languages,
        ContactName name,
        List<String> nicknames,
        List<String> notes,
        ContactOrganization organization,
        List<ContactPhone> phones,
        List<ContactRelation> relations,
        String role,
        String title,
        String uid,
        List<String> websites) {
    /**
     * A field model under construction, its lists mutable: what the phone
     * mapping fills, field by field.
     */
    public static final class Builder {
        public List<ContactAddress> addresses = new ArrayList<>();
        public String anniversary;
        public String birthday;
        public List<String> categories = new ArrayList<>();
        public String displayName;
        public List<ContactEmail> emails = new ArrayList<>();
        public ContactGender gender;
        public List<String> impps = new ArrayList<>();
        public List<String> languages = new ArrayList<>();
        public ContactName name;
        public List<String> nicknames = new ArrayList<>();
        public List<String> notes = new ArrayList<>();
        public ContactOrganization organization;
        public List<ContactPhone> phones = new ArrayList<>();
        public List<ContactRelation> relations = new ArrayList<>();
        public String role;
        public String title;
        public String uid;
        public List<String> websites = new ArrayList<>();

        /** An empty model: every field absent, every list empty. */
        public Builder() {}

        /** A working copy of {@code fields}. */
        public Builder(ContactFields fields) {
            addresses = new ArrayList<>(fields.addresses);
            anniversary = fields.anniversary;
            birthday = fields.birthday;
            categories = new ArrayList<>(fields.categories);
            displayName = fields.displayName;
            emails = new ArrayList<>(fields.emails);
            gender = fields.gender;
            impps = new ArrayList<>(fields.impps);
            languages = new ArrayList<>(fields.languages);
            name = fields.name;
            nicknames = new ArrayList<>(fields.nicknames);
            notes = new ArrayList<>(fields.notes);
            organization = fields.organization;
            phones = new ArrayList<>(fields.phones);
            relations = new ArrayList<>(fields.relations);
            role = fields.role;
            title = fields.title;
            uid = fields.uid;
            websites = new ArrayList<>(fields.websites);
        }

        public ContactFields build() {
            return new ContactFields(
                    List.copyOf(addresses),
                    anniversary,
                    birthday,
                    List.copyOf(categories),
                    displayName,
                    List.copyOf(emails),
                    gender,
                    List.copyOf(impps),
                    List.copyOf(languages),
                    name,
                    List.copyOf(nicknames),
                    List.copyOf(notes),
                    organization,
                    List.copyOf(phones),
                    List.copyOf(relations),
                    role,
                    title,
                    uid,
                    List.copyOf(websites));
        }
    }

    /**
     * Parses a field model: the bridge's projection, or (until the form
     * edits records) the form's working model. A key left out reads as
     * absent, a list left out as empty.
     */
    static ContactFields from(JSONObject model) {
        JSONObject gender = model.optJSONObject("gender");
        JSONObject name = model.optJSONObject("name");
        JSONObject organization = model.optJSONObject("organization");
        return new ContactFields(
                entries(model.optJSONArray("addresses"), ContactAddress::from),
                Json.optString(model, "anniversary"),
                Json.optString(model, "birthday"),
                List.copyOf(Json.strings(model.optJSONArray("categories"))),
                Json.optString(model, "displayName"),
                entries(model.optJSONArray("emails"), ContactEmail::from),
                gender == null ? null : ContactGender.from(gender),
                List.copyOf(Json.strings(model.optJSONArray("impps"))),
                List.copyOf(Json.strings(model.optJSONArray("languages"))),
                name == null ? null : ContactName.from(name),
                List.copyOf(Json.strings(model.optJSONArray("nicknames"))),
                List.copyOf(Json.strings(model.optJSONArray("notes"))),
                organization == null ? null : ContactOrganization.from(organization),
                entries(model.optJSONArray("phones"), ContactPhone::from),
                entries(model.optJSONArray("relations"), ContactRelation::from),
                Json.optString(model, "role"),
                Json.optString(model, "title"),
                Json.optString(model, "uid"),
                List.copyOf(Json.strings(model.optJSONArray("websites"))));
    }

    /** The model on the wire: an absent field is an absent key, every list written. */
    JSONObject toJson() {
        try {
            JSONArray addresses = new JSONArray();
            for (ContactAddress address : this.addresses) {
                addresses.put(address.toJson());
            }
            JSONArray emails = new JSONArray();
            for (ContactEmail email : this.emails) {
                emails.put(email.toJson());
            }
            JSONArray phones = new JSONArray();
            for (ContactPhone phone : this.phones) {
                phones.put(phone.toJson());
            }
            JSONArray relations = new JSONArray();
            for (ContactRelation relation : this.relations) {
                relations.put(relation.toJson());
            }

            JSONObject model = new JSONObject();
            model.put("addresses", addresses);
            if (anniversary != null) {
                model.put("anniversary", anniversary);
            }
            if (birthday != null) {
                model.put("birthday", birthday);
            }
            model.put("categories", new JSONArray(categories));
            if (displayName != null) {
                model.put("displayName", displayName);
            }
            model.put("emails", emails);
            if (gender != null) {
                model.put("gender", gender.toJson());
            }
            model.put("impps", new JSONArray(impps));
            model.put("languages", new JSONArray(languages));
            if (name != null) {
                model.put("name", name.toJson());
            }
            model.put("nicknames", new JSONArray(nicknames));
            model.put("notes", new JSONArray(notes));
            if (organization != null) {
                model.put("organization", organization.toJson());
            }
            model.put("phones", phones);
            model.put("relations", relations);
            if (role != null) {
                model.put("role", role);
            }
            if (title != null) {
                model.put("title", title);
            }
            if (uid != null) {
                model.put("uid", uid);
            }
            model.put("websites", new JSONArray(websites));
            return model;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable field model: " + error.getMessage());
        }
    }

    private static <T> List<T> entries(
            JSONArray values, Function<JSONObject, T> entry) {
        List<T> entries = new ArrayList<>(values == null ? 0 : values.length());
        for (int index = 0; values != null && index < values.length(); index++) {
            entries.add(entry.apply(Json.object(values, index)));
        }
        return List.copyOf(entries);
    }
}
