package org.pimalaya.contact;

import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.provider.ContactsContract.Data;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.pimalaya.client.ContactAddress;
import org.pimalaya.client.ContactEmail;
import org.pimalaya.client.ContactFields;
import org.pimalaya.client.ContactName;
import org.pimalaya.client.ContactOrganization;
import org.pimalaya.client.ContactPhone;

/**
 * The pure half of the phone spoke, both directions: neutral field
 * model to ContactsContract data rows and back, as plain maps keyed by
 * the wire column names (docs/contacts-mapping.md is the contract,
 * MappingTest pins it). Only compile-time Android constants are
 * referenced, so the whole mapping runs and is tested on the JVM;
 * PhoneRemote wraps the maps into ContentValues and owns everything
 * that needs a device.
 */
final class Mapping {
    private Mapping() {}

    /**
     * The model fields the phone rows represent: what {@link #model}
     * returns and what a fetched phone contact overwrites in the base
     * card's model (everything else is Pimalaya-only and rides along).
     */
    enum Field {
        DISPLAY_NAME("displayName", ContactFields::displayName, (into, from) ->
                into.displayName = from.displayName()),
        NAME("name", ContactFields::name, (into, from) -> into.name = from.name()),
        NICKNAMES("nicknames", ContactFields::nicknames, (into, from) ->
                into.nicknames = from.nicknames()),
        PHONES("phones", ContactFields::phones, (into, from) -> into.phones = from.phones()),
        EMAILS("emails", ContactFields::emails, (into, from) -> into.emails = from.emails()),
        ADDRESSES("addresses", ContactFields::addresses, (into, from) ->
                into.addresses = from.addresses()),
        ORGANIZATION("organization", ContactFields::organization, (into, from) ->
                into.organization = from.organization()),
        TITLE("title", ContactFields::title, (into, from) -> into.title = from.title()),
        ROLE("role", ContactFields::role, (into, from) -> into.role = from.role()),
        WEBSITES("websites", ContactFields::websites, (into, from) ->
                into.websites = from.websites()),
        BIRTHDAY("birthday", ContactFields::birthday, (into, from) ->
                into.birthday = from.birthday()),
        NOTES("notes", ContactFields::notes, (into, from) -> into.notes = from.notes());

        /** The model key, as the field crosses the bridge. */
        final String key;

        private final Function<ContactFields, Object> getter;

        private final BiConsumer<ContactFields.Builder, ContactFields> setter;

        Field(
                String key,
                Function<ContactFields, Object> getter,
                BiConsumer<ContactFields.Builder, ContactFields> setter) {
            this.key = key;
            this.getter = getter;
            this.setter = setter;
        }

        /** The field's value in {@code model}. */
        Object of(ContactFields model) {
            return getter.apply(model);
        }

        /** Sets the field of {@code into} to its value in {@code from}. */
        void take(ContactFields.Builder into, ContactFields from) {
            setter.accept(into, from);
        }
    }

    /** Maps the neutral field model onto ContactsContract data rows. */
    static List<Map<String, Object>> rows(ContactFields model) {
        List<Map<String, Object>> rows = new ArrayList<>();

        // NOTE: the display name (FN) is hub-only and never reaches the
        // phone: ContactsProvider owns DISPLAY_NAME, rewriting it as the
        // join of the structured parts whenever present (proven
        // on-device), so a written FN either vanishes or gets re-split
        // into the wrong parts. Only the structured parts project.
        Map<String, Object> name = row(StructuredName.CONTENT_ITEM_TYPE);
        ContactName n = model.name();
        if (n != null) {
            name.put(StructuredName.FAMILY_NAME, n.family());
            name.put(StructuredName.GIVEN_NAME, n.given());
            name.put(StructuredName.MIDDLE_NAME, n.middle());
            name.put(StructuredName.PREFIX, n.prefix());
            name.put(StructuredName.SUFFIX, n.suffix());
        }
        rows.add(name);

        for (String nickname : model.nicknames()) {
            Map<String, Object> row = row(Nickname.CONTENT_ITEM_TYPE);
            row.put(Nickname.NAME, nickname);
            row.put(Nickname.TYPE, Nickname.TYPE_DEFAULT);
            rows.add(row);
        }

        for (ContactPhone phone : model.phones()) {
            Map<String, Object> row = row(Phone.CONTENT_ITEM_TYPE);
            row.put(Phone.NUMBER, phone.number());
            row.put(Phone.TYPE, phoneType(new HashSet<>(phone.types())));
            row.put(Phone.IS_PRIMARY, phone.pref() ? 1 : 0);
            rows.add(row);
        }

        for (ContactEmail email : model.emails()) {
            Set<String> types = new HashSet<>(email.types());
            Map<String, Object> row = row(Email.CONTENT_ITEM_TYPE);
            row.put(Email.ADDRESS, email.address());
            row.put(
                    Email.TYPE,
                    types.contains("home")
                            ? Email.TYPE_HOME
                            : types.contains("work") ? Email.TYPE_WORK : Email.TYPE_OTHER);
            row.put(Email.IS_PRIMARY, email.pref() ? 1 : 0);
            rows.add(row);
        }

        for (ContactAddress adr : model.addresses()) {
            Set<String> types = new HashSet<>(adr.types());
            Map<String, Object> row = row(StructuredPostal.CONTENT_ITEM_TYPE);
            row.put(StructuredPostal.STREET, adr.street());
            row.put(StructuredPostal.POBOX, adr.pobox());
            row.put(StructuredPostal.NEIGHBORHOOD, adr.ext());
            row.put(StructuredPostal.CITY, adr.city());
            row.put(StructuredPostal.REGION, adr.region());
            row.put(StructuredPostal.POSTCODE, adr.postcode());
            row.put(StructuredPostal.COUNTRY, adr.country());
            row.put(StructuredPostal.FORMATTED_ADDRESS, formatted(adr));
            row.put(
                    StructuredPostal.TYPE,
                    types.contains("home")
                            ? StructuredPostal.TYPE_HOME
                            : types.contains("work")
                                    ? StructuredPostal.TYPE_WORK
                                    : StructuredPostal.TYPE_OTHER);
            rows.add(row);
        }

        ContactOrganization organization = model.organization();
        String title = Objects.toString(model.title(), "");
        String role = Objects.toString(model.role(), "");
        if (organization != null || !title.isEmpty() || !role.isEmpty()) {
            Map<String, Object> row = row(Organization.CONTENT_ITEM_TYPE);
            if (organization != null) {
                row.put(Organization.COMPANY, organization.company());
                row.put(Organization.DEPARTMENT, organization.department());
            }
            row.put(Organization.TITLE, title);
            row.put(Organization.JOB_DESCRIPTION, role);
            row.put(Organization.TYPE, Organization.TYPE_WORK);
            rows.add(row);
        }

        for (String website : model.websites()) {
            Map<String, Object> row = row(Website.CONTENT_ITEM_TYPE);
            row.put(Website.URL, website);
            row.put(Website.TYPE, Website.TYPE_OTHER);
            rows.add(row);
        }

        String birthday = Objects.toString(model.birthday(), "");
        if (!birthday.isEmpty()) {
            Map<String, Object> row = row(Event.CONTENT_ITEM_TYPE);
            row.put(Event.START_DATE, birthday);
            row.put(Event.TYPE, Event.TYPE_BIRTHDAY);
            rows.add(row);
        }

        for (String note : model.notes()) {
            Map<String, Object> row = row(Note.CONTENT_ITEM_TYPE);
            row.put(Note.NOTE, note);
            rows.add(row);
        }

        return rows;
    }

    /**
     * Maps ContactsContract data rows back onto the neutral field
     * model, the inverse of {@link #rows}. Every field of
     * {@link Field} is present in the result, empty when the rows
     * carry nothing, so a phone-side clear reads as a clear.
     */
    static ContactFields model(List<Map<String, Object>> rows) {
        ContactFields.Builder model = new ContactFields.Builder();
        model.displayName = "";
        model.name = emptyName();
        model.organization = new ContactOrganization("", "");
        model.title = "";
        model.role = "";
        model.birthday = "";

        for (Map<String, Object> row : rows) {
            switch (string(row, Data.MIMETYPE)) {
                case StructuredName.CONTENT_ITEM_TYPE:
                    String prefix = string(row, StructuredName.PREFIX);
                    String given = string(row, StructuredName.GIVEN_NAME);
                    String middle = string(row, StructuredName.MIDDLE_NAME);
                    String family = string(row, StructuredName.FAMILY_NAME);
                    String suffix = string(row, StructuredName.SUFFIX);
                    model.name = new ContactName(family, given, middle, prefix, suffix);
                    // NOTE: the provider auto-composes DISPLAY_NAME from
                    // the parts, so reading it back off a structured row
                    // would mint it into FN. Only a parts-free row's
                    // DISPLAY_NAME is real phone-authored content and
                    // ingests.
                    boolean hasParts =
                            !prefix.isEmpty()
                                    || !given.isEmpty()
                                    || !middle.isEmpty()
                                    || !family.isEmpty()
                                    || !suffix.isEmpty();
                    model.displayName = hasParts ? "" : string(row, StructuredName.DISPLAY_NAME);
                    break;

                case Nickname.CONTENT_ITEM_TYPE:
                    String nickname = string(row, Nickname.NAME);
                    if (!nickname.isEmpty()) {
                        model.nicknames.add(nickname);
                    }
                    break;

                case Phone.CONTENT_ITEM_TYPE:
                    String phoneNumber = string(row, Phone.NUMBER);
                    if (!phoneNumber.isEmpty()) {
                        model.phones.add(
                                new ContactPhone(
                                        phoneNumber,
                                        number(row, Phone.IS_PRIMARY) != 0,
                                        phoneTypes(number(row, Phone.TYPE))));
                    }
                    break;

                case Email.CONTENT_ITEM_TYPE:
                    String address = string(row, Email.ADDRESS);
                    if (!address.isEmpty()) {
                        model.emails.add(
                                new ContactEmail(
                                        address,
                                        number(row, Email.IS_PRIMARY) != 0,
                                        homeWorkTypes(
                                                number(row, Email.TYPE),
                                                Email.TYPE_HOME,
                                                Email.TYPE_WORK)));
                    }
                    break;

                case StructuredPostal.CONTENT_ITEM_TYPE:
                    ContactAddress adr = readAddress(row);
                    if (adr != null) {
                        model.addresses.add(adr);
                    }
                    break;

                case Organization.CONTENT_ITEM_TYPE:
                    String company = string(row, Organization.COMPANY);
                    String department = string(row, Organization.DEPARTMENT);
                    if (!company.isEmpty() || !department.isEmpty()) {
                        model.organization = new ContactOrganization(company, department);
                    }
                    model.title = string(row, Organization.TITLE);
                    model.role = string(row, Organization.JOB_DESCRIPTION);
                    break;

                case Website.CONTENT_ITEM_TYPE:
                    String url = string(row, Website.URL);
                    if (!url.isEmpty()) {
                        model.websites.add(url);
                    }
                    break;

                case Event.CONTENT_ITEM_TYPE:
                    if (number(row, Event.TYPE) == Event.TYPE_BIRTHDAY) {
                        model.birthday = string(row, Event.START_DATE);
                    }
                    break;

                case Note.CONTENT_ITEM_TYPE:
                    String note = string(row, Note.NOTE);
                    if (!note.isEmpty()) {
                        model.notes.add(note);
                    }
                    break;

                default:
                    // NOTE: kinds outside the mapping (photo, groups,
                    // third-party rows) stay phone-side.
                    break;
            }
        }

        return model.build();
    }

    /**
     * Overlays the phone's field model onto the base card's model: a
     * field is taken from the phone only when it differs from what the
     * base itself projects onto the phone, so mapping lossiness never
     * reads as a phone edit, and every field outside {@link Field}
     * rides along from the base untouched. Returns null when no field
     * is taken: the phone carries no edit, and the caller must keep
     * the base card's exact bytes rather than re-encode them (the
     * engine diffs content by hash, so a re-encoded no-op would read
     * as a phone edit and cascade to the server).
     */
    static ContactFields merge(ContactFields base, ContactFields phone) {
        ContactFields baseView = model(rows(base));

        ContactFields.Builder merged = new ContactFields.Builder(base);

        boolean edited = false;
        for (Field field : Field.values()) {
            // NOTE: both sides come out of model(), so equal content
            // compares equal (same construction on either side).
            if (!Objects.equals(field.of(phone), field.of(baseView))) {
                field.take(merged, phone);
                edited = true;
            }
        }

        return edited ? merged.build() : null;
    }

    /** vCard TYPE set to the Android phone type, per docs/contacts-mapping.md. */
    static int phoneType(Set<String> types) {
        if (types.contains("fax")) {
            return types.contains("work")
                    ? Phone.TYPE_FAX_WORK
                    : types.contains("home") ? Phone.TYPE_FAX_HOME : Phone.TYPE_OTHER_FAX;
        }
        if (types.contains("cell")) {
            return types.contains("work") ? Phone.TYPE_WORK_MOBILE : Phone.TYPE_MOBILE;
        }
        if (types.contains("pager")) {
            return types.contains("work") ? Phone.TYPE_WORK_PAGER : Phone.TYPE_PAGER;
        }
        if (types.contains("car")) {
            return Phone.TYPE_CAR;
        }
        if (types.contains("isdn")) {
            return Phone.TYPE_ISDN;
        }
        if (types.contains("work")) {
            return Phone.TYPE_WORK;
        }
        if (types.contains("home")) {
            return Phone.TYPE_HOME;
        }
        return Phone.TYPE_OTHER;
    }

    /**
     * Android phone type back to the vCard TYPE set, the inverse of
     * {@link #phoneType}; exotic types map to the empty set (a bare
     * TEL), per docs/contacts-mapping.md.
     */
    static List<String> phoneTypes(int type) {
        switch (type) {
            case Phone.TYPE_HOME:
                return typeArray("home");
            case Phone.TYPE_MOBILE:
                return typeArray("cell");
            case Phone.TYPE_WORK:
                return typeArray("work");
            case Phone.TYPE_FAX_WORK:
                return typeArray("work", "fax");
            case Phone.TYPE_FAX_HOME:
                return typeArray("home", "fax");
            case Phone.TYPE_OTHER_FAX:
                return typeArray("fax");
            case Phone.TYPE_PAGER:
                return typeArray("pager");
            case Phone.TYPE_WORK_PAGER:
                return typeArray("work", "pager");
            case Phone.TYPE_WORK_MOBILE:
                return typeArray("work", "cell");
            case Phone.TYPE_CAR:
                return typeArray("car");
            case Phone.TYPE_ISDN:
                return typeArray("isdn");
            default:
                return List.of();
        }
    }

    /** HOME/WORK-style Android type back to the vCard TYPE set. */
    static List<String> homeWorkTypes(int type, int home, int work) {
        if (type == home) {
            return typeArray("home");
        }
        if (type == work) {
            return typeArray("work");
        }
        return List.of();
    }

    /** One postal row back to a model address, null when empty. */
    private static ContactAddress readAddress(Map<String, Object> row) {
        String street = string(row, StructuredPostal.STREET);
        String pobox = string(row, StructuredPostal.POBOX);
        String ext = string(row, StructuredPostal.NEIGHBORHOOD);
        String city = string(row, StructuredPostal.CITY);
        String region = string(row, StructuredPostal.REGION);
        String postcode = string(row, StructuredPostal.POSTCODE);
        String country = string(row, StructuredPostal.COUNTRY);

        boolean empty = true;
        for (String part : List.of(street, pobox, ext, city, region, postcode, country)) {
            empty &= part.isEmpty();
        }
        if (empty) {
            // NOTE: a free-form-only address (phone apps allow it) rides
            // in the street component, per docs/contacts-mapping.md.
            String formatted = string(row, StructuredPostal.FORMATTED_ADDRESS);
            if (formatted.isEmpty()) {
                return null;
            }
            street = formatted;
        }

        return new ContactAddress(
                city,
                country,
                ext,
                pobox,
                postcode,
                number(row, StructuredPostal.IS_PRIMARY) != 0,
                region,
                street,
                homeWorkTypes(
                        number(row, StructuredPostal.TYPE),
                        StructuredPostal.TYPE_HOME,
                        StructuredPostal.TYPE_WORK));
    }

    /** The all-empty structured name every inverse model starts from. */
    private static ContactName emptyName() {
        return new ContactName("", "", "", "", "");
    }

    private static List<String> typeArray(String... types) {
        return List.of(types);
    }

    /** A row's column as text, empty when absent. */
    private static String string(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** A row's column as a number, 0 when absent or malformed. */
    private static int number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private static Map<String, Object> row(String mimetype) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(Data.MIMETYPE, mimetype);
        return row;
    }

    /** Composes the display form contact apps show for an address. */
    private static String formatted(ContactAddress adr) {
        StringBuilder out = new StringBuilder();
        appendLine(out, adr.street());
        appendLine(out, join(adr.postcode(), adr.city()));
        appendLine(out, adr.region());
        appendLine(out, adr.country());
        return out.toString();
    }

    private static void appendLine(StringBuilder out, String line) {
        if (!line.isEmpty()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
    }

    private static String join(String left, String right) {
        if (left.isEmpty()) {
            return right;
        }
        return right.isEmpty() ? left : left + " " + right;
    }
}
