package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The neutral field model of one card, both ways across the bridge.
 *
 * <p>Mirrors rs/contact/fields.rs {@code ContactFields} 52. A null field is
 * absent ({@code None}), an empty string present ({@code Some("")}, which
 * the phone mapping writes on purpose). Lists are never null.
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
     * A mutable field model: the phone mapping's and the edit form's copy.
     */
    public static final class Builder {
        /** The ADR entries. */
        public List<ContactAddress> addresses = new ArrayList<>();
        /** The first full ANNIVERSARY, as {@code yyyy-mm-dd}. */
        public String anniversary;
        /** The first full BDAY, as {@code yyyy-mm-dd}. */
        public String birthday;
        /** The CATEGORIES values, projected but never applied. */
        public List<String> categories = new ArrayList<>();
        /** The first FN. */
        public String displayName;
        /** The EMAIL entries. */
        public List<ContactEmail> emails = new ArrayList<>();
        /** The first GENDER. */
        public ContactGender gender;
        /** The IMPP URIs. */
        public List<String> impps = new ArrayList<>();
        /** The LANG tags. */
        public List<String> languages = new ArrayList<>();
        /** The first N. */
        public ContactName name;
        /** The NICKNAME values, flattened. */
        public List<String> nicknames = new ArrayList<>();
        /** The NOTE values. */
        public List<String> notes = new ArrayList<>();
        /** The first ORG. */
        public ContactOrganization organization;
        /** The TEL entries. */
        public List<ContactPhone> phones = new ArrayList<>();
        /** The RELATED entries. */
        public List<ContactRelation> relations = new ArrayList<>();
        /** The first ROLE. */
        public String role;
        /** The first TITLE. */
        public String title;
        /** The first UID, projected but never applied. */
        public String uid;
        /** The URL values. */
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

        /** The immutable model, its lists copied. */
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
     * Parses a field model the bridge answered: a projection or a prefill.
     *
     * <p>A key left out reads as absent, a list left out as empty.
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

    /** The model on the wire: absent field, absent key; every list written. */
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
