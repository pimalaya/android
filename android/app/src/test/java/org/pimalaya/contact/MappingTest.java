package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.pimalaya.client.ContactAddress;
import org.pimalaya.client.ContactEmail;
import org.pimalaya.client.ContactFields;
import org.pimalaya.client.ContactName;
import org.pimalaya.client.ContactOrganization;
import org.pimalaya.client.ContactPhone;

/**
 * Pins the neutral-field-model to ContactsContract wire mapping
 * (docs/contacts-mapping.md): mimetypes, column names and type
 * constants are asserted literally, so any drift in the mapping breaks
 * loudly here instead of silently on phones.
 */
public class MappingTest {
    @Test
    public void displayNameNeverProjectsOnlyComponentsDo() throws Exception {
        // Regression: FN is hub-only. Projected alone it was re-split
        // into the wrong parts by the provider's guesswork; written
        // alongside the parts the provider rewrote it as their join,
        // which then read back as a phone edit clearing FN. The phone
        // gets the structured parts, nothing else.
        ContactFields.Builder model = new ContactFields.Builder();
        model.displayName = "Jane Doe Yo";
        model.name = new ContactName("Doe", "Jane", "Q.", "", "Jr.");

        Map<String, Object> name = firstOfType(model.build(), "vnd.android.cursor.item/name");
        assertNull(name.get("data1"));
        assertEquals("Jane", name.get("data2"));
        assertEquals("Doe", name.get("data3"));
        assertEquals("Q.", name.get("data5"));
        assertEquals("Jr.", name.get("data6"));
    }

    @Test
    public void emptyDisplayNameStillProjectsComponents() throws Exception {
        ContactFields.Builder model = new ContactFields.Builder();
        model.displayName = "";
        model.name = new ContactName("", "Jane", "", "", "");

        Map<String, Object> name = firstOfType(model.build(), "vnd.android.cursor.item/name");
        assertEquals("Jane", name.get("data2"));
    }

    @Test
    public void phoneTypeTable() {
        assertEquals(2, Mapping.phoneType(types("cell")));
        assertEquals(17, Mapping.phoneType(types("cell", "work")));
        assertEquals(1, Mapping.phoneType(types("home")));
        assertEquals(3, Mapping.phoneType(types("work")));
        assertEquals(5, Mapping.phoneType(types("fax", "home")));
        assertEquals(4, Mapping.phoneType(types("fax", "work")));
        assertEquals(13, Mapping.phoneType(types("fax")));
        assertEquals(6, Mapping.phoneType(types("pager")));
        assertEquals(18, Mapping.phoneType(types("pager", "work")));
        assertEquals(9, Mapping.phoneType(types("car")));
        assertEquals(11, Mapping.phoneType(types("isdn")));
        assertEquals(7, Mapping.phoneType(types()));
        assertEquals(7, Mapping.phoneType(types("voice")));
    }

    @Test
    public void phoneRowCarriesNumberTypeAndPref() throws Exception {
        ContactFields.Builder model = new ContactFields.Builder();
        model.phones.add(new ContactPhone("+331111", true, List.of("cell")));

        Map<String, Object> phone =
                firstOfType(model.build(), "vnd.android.cursor.item/phone_v2");
        assertEquals("+331111", phone.get("data1"));
        assertEquals(2, phone.get("data2"));
        assertEquals(1, phone.get("is_primary"));
    }

    @Test
    public void emailTypesMapToHomeWorkOther() throws Exception {
        assertEquals(1, emailType("home"));
        assertEquals(2, emailType("work"));
        assertEquals(3, emailType("internet"));
    }

    @Test
    public void addressRowIsStructuredWithComposedFormatted() throws Exception {
        ContactFields.Builder model = new ContactFields.Builder();
        model.addresses.add(
                new ContactAddress(
                        "Paris", "France", "", "", "75000", false, "", "12 Main St",
                        List.of("home")));

        Map<String, Object> adr =
                firstOfType(model.build(), "vnd.android.cursor.item/postal-address_v2");
        assertEquals("12 Main St", adr.get("data4"));
        assertEquals("Paris", adr.get("data7"));
        assertEquals("75000", adr.get("data9"));
        assertEquals("France", adr.get("data10"));
        assertEquals(1, adr.get("data2"));
        assertEquals("12 Main St\n75000 Paris\nFrance", adr.get("data1"));
    }

    @Test
    public void organizationRowOnlyWhenAnyFieldSet() throws Exception {
        ContactFields.Builder empty = new ContactFields.Builder();
        empty.displayName = "X";
        for (Map<String, Object> row : Mapping.rows(empty.build())) {
            assertFalse("vnd.android.cursor.item/organization".equals(row.get("mimetype")));
        }

        ContactFields.Builder model = new ContactFields.Builder();
        model.organization = new ContactOrganization("ACME", "R&D");
        model.title = "Boss";
        model.role = "CTO";
        Map<String, Object> org =
                firstOfType(model.build(), "vnd.android.cursor.item/organization");
        assertEquals("ACME", org.get("data1"));
        assertEquals("R&D", org.get("data5"));
        assertEquals("Boss", org.get("data4"));
        assertEquals("CTO", org.get("data6"));
        assertEquals(1, org.get("data2"));
    }

    @Test
    public void birthdayNicknameWebsiteAndNoteRows() throws Exception {
        ContactFields.Builder fields = new ContactFields.Builder();
        fields.birthday = "1985-04-12";
        fields.nicknames.add("JD");
        fields.websites.add("https://doe.org");
        fields.notes.add("a note");
        ContactFields model = fields.build();

        Map<String, Object> event = firstOfType(model, "vnd.android.cursor.item/contact_event");
        assertEquals("1985-04-12", event.get("data1"));
        assertEquals(3, event.get("data2"));

        Map<String, Object> nick = firstOfType(model, "vnd.android.cursor.item/nickname");
        assertEquals("JD", nick.get("data1"));

        Map<String, Object> site = firstOfType(model, "vnd.android.cursor.item/website");
        assertEquals("https://doe.org", site.get("data1"));
        assertEquals(7, site.get("data2"));

        Map<String, Object> note = firstOfType(model, "vnd.android.cursor.item/note");
        assertEquals("a note", note.get("data1"));
    }

    @Test
    public void noBirthdayRowWhenEmpty() throws Exception {
        ContactFields.Builder model = new ContactFields.Builder();
        model.displayName = "X";
        model.birthday = "";
        for (Map<String, Object> row : Mapping.rows(model.build())) {
            assertFalse("vnd.android.cursor.item/contact_event".equals(row.get("mimetype")));
        }
    }

    // ---- Inverse direction (rows to model) -------------------------------------

    @Test
    public void richModelRoundTripsThroughRows() throws Exception {
        // NOTE: the display name stays empty: FN is hub-only, never
        // projected to the phone, so it cannot round-trip through rows.
        ContactFields.Builder fields = new ContactFields.Builder();
        fields.displayName = "";
        fields.name = new ContactName("Doe", "Jane", "Q.", "", "Jr.");
        fields.nicknames.add("JD");
        fields.phones.add(new ContactPhone("+331111", true, List.of("cell")));
        fields.emails.add(new ContactEmail("a@b.c", false, List.of("home")));
        fields.addresses.add(
                new ContactAddress(
                        "Paris", "France", "", "", "75000", false, "", "12 Main St",
                        List.of("home")));
        fields.organization = new ContactOrganization("ACME", "R&D");
        fields.title = "Boss";
        fields.role = "CTO";
        fields.websites.add("https://doe.org");
        fields.birthday = "1985-04-12";
        fields.notes.add("a note");
        ContactFields model = fields.build();

        ContactFields round = Mapping.model(Mapping.rows(model));
        for (Mapping.Field field : Mapping.Field.values()) {
            assertEquals(field.key, field.of(model), field.of(round));
        }
    }

    @Test
    public void phoneTypesInvertsThePhoneTypeTable() {
        int[] table = {1, 2, 3, 4, 5, 13, 6, 18, 17, 9, 11};
        for (int type : table) {
            Set<String> set = new HashSet<>();
            List<String> types = Mapping.phoneTypes(type);
            for (int index = 0; index < types.size(); index++) {
                set.add(types.get(index));
            }
            assertEquals(type, Mapping.phoneType(set));
        }
        assertEquals(0, Mapping.phoneTypes(7).size());
        assertEquals(0, Mapping.phoneTypes(0).size());
    }

    @Test
    public void displayNameOnlyRowReadsAsBareDisplayName() throws Exception {
        // The shape phone apps author when only a full name is typed.
        Map<String, Object> row = new HashMap<>();
        row.put("mimetype", "vnd.android.cursor.item/name");
        row.put("data1", "Jane Doe");

        ContactFields model = Mapping.model(List.of(row));
        assertEquals("Jane Doe", model.displayName());
        assertEquals("", model.name().given());
        assertEquals("", model.name().family());
    }

    @Test
    public void customLabelledNumberReadsAsBareTel() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("mimetype", "vnd.android.cursor.item/phone_v2");
        row.put("data1", "0611");
        row.put("data2", 0);
        row.put("data3", "Batphone");

        ContactPhone phone = Mapping.model(List.of(row)).phones().get(0);
        assertEquals("0611", phone.number());
        assertEquals(0, phone.types().size());
    }

    @Test
    public void formattedOnlyAddressReadsIntoStreet() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("mimetype", "vnd.android.cursor.item/postal-address_v2");
        row.put("data1", "12 Main St, Paris");
        row.put("data2", 1);

        ContactAddress adr = Mapping.model(List.of(row)).addresses().get(0);
        assertEquals("12 Main St, Paris", adr.street());
        assertEquals("home", adr.types().get(0));
    }

    // ---- Field-space merge ------------------------------------------------------

    @Test
    public void mergeTakesOnlyThePhoneEditedFields() throws Exception {
        ContactFields base = divergentBase();
        ContactFields.Builder phone = new ContactFields.Builder(Mapping.model(Mapping.rows(base)));
        ContactPhone first = phone.phones.get(0);
        phone.phones.set(0, new ContactPhone("+332222", first.pref(), first.types()));

        ContactFields merged = Mapping.merge(base, phone.build());
        assertEquals("+332222", merged.phones().get(0).number());
        // FN never reaches the phone rows, so an untouched phone must
        // not read as a name clear.
        assertEquals("Doe", merged.name().family());
        // Fields outside the phone mapping ride along from the base.
        assertEquals("friends", merged.categories().get(0));
    }

    @Test
    public void mergeOfAnUntouchedPhoneIsNull() throws Exception {
        ContactFields base = divergentBase();
        ContactFields phone = Mapping.model(Mapping.rows(base));

        // No field taken means no phone edit: the caller keeps the base
        // card's exact bytes instead of re-encoding a no-op.
        assertNull(Mapping.merge(base, phone));
    }

    /** A base model whose FN diverges from N (lossy on the phone). */
    private static ContactFields divergentBase() {
        ContactFields.Builder base = new ContactFields.Builder();
        base.displayName = "Jane Doe Yo";
        base.name = new ContactName("Doe", "Jane", "", "", "");
        base.phones.add(new ContactPhone("+331111", false, List.of("cell")));
        base.categories.add("friends");
        return base.build();
    }

    // ---- Helpers --------------------------------------------------------------

    private static Map<String, Object> firstOfType(ContactFields model, String mimetype) {
        List<Map<String, Object>> rows = Mapping.rows(model);
        for (Map<String, Object> row : rows) {
            if (mimetype.equals(row.get("mimetype"))) {
                return row;
            }
        }
        throw new AssertionError("no row of type " + mimetype + " in " + rows);
    }

    private static int emailType(String type) throws Exception {
        ContactFields.Builder model = new ContactFields.Builder();
        model.emails.add(new ContactEmail("a@b.c", false, List.of(type)));
        return (int)
                firstOfType(model.build(), "vnd.android.cursor.item/email_v2").get("data2");
    }

    private static Set<String> types(String... values) {
        Set<String> set = new HashSet<>();
        for (String value : values) {
            set.add(value);
        }
        return set;
    }
}
