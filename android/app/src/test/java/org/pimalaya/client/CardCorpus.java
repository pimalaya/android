package org.pimalaya.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * Crafted vCards, phone row sets and form models the field pins run.
 *
 * <p>Both versions, every property read, present-empty values, first-wins
 * duplicates, groups, escapes and an unreadable document.
 */
public final class CardCorpus {
    private CardCorpus() {}

    private static String card(String version, String... lines) {
        StringBuilder out = new StringBuilder("BEGIN:VCARD\r\nVERSION:" + version + "\r\n");
        for (String line : lines) {
            out.append(line).append("\r\n");
        }
        return out.append("END:VCARD\r\n").toString();
    }

    /** The vCards, the last one unreadable. */
    public static final List<String> VCARDS =
            List.of(
                    card("4.0", "UID:min", "FN:Only Name"),
                    card(
                            "4.0",
                            "UID:rich",
                            "FN:Dr. Jane Q. Doe Jr.",
                            "N:Doe;Jane;Q.;Dr.;Jr.",
                            "NICKNAME:JD,Janie",
                            "TEL;TYPE=cell;PREF=1:+33 6 11 11 11 11",
                            "TEL;TYPE=work,voice:+33 1 22 22 22 22",
                            "EMAIL;TYPE=home:jane@home.example",
                            "EMAIL;TYPE=work;PREF=1:jane@work.example",
                            "ADR;TYPE=home:;Apt 4;12 Main St\\n2nd floor;Paris;IDF;75000;France",
                            "ADR;TYPE=work:PO 9;;1 Rue;Lyon;;69000;France",
                            "ORG:ACME;R&D;Lab",
                            "TITLE:Boss",
                            "ROLE:CTO",
                            "URL:https://doe.example",
                            "BDAY:19850412",
                            "ANNIVERSARY:2010-06-01",
                            "GENDER:F;woman",
                            "IMPP:xmpp:jane@doe.example",
                            "LANG:fr",
                            "RELATED;TYPE=spouse:urn:uuid:123",
                            "RELATED;TYPE=friend;VALUE=text:Bob",
                            "NOTE:first note",
                            "NOTE:second\\, with comma",
                            "CATEGORIES:friends,work",
                            "PHOTO:https://doe.example/p.jpg",
                            "X-CUSTOM:kept"),
                    card(
                            "3.0",
                            "UID:v3",
                            "FN:Old Style",
                            "N:Style;Old;;;",
                            "TEL;TYPE=CELL;TYPE=pref:0611",
                            "TEL;TYPE=HOME:0122",
                            "EMAIL;TYPE=INTERNET;TYPE=pref:old@style.example",
                            "ORG:Single",
                            "BDAY:--0412",
                            "X-ABUID:abc"),
                    card("4.0", "UID:lossy", "FN:Jane Doe Yo", "N:Doe;Jane;;;", "TEL;TYPE=cell:+331111"),
                    card("4.0", "UID:nfn", "N:Smith;John;;Mr.;"),
                    card("4.0", "UID:empties", "FN:", "N:;;;;", "ORG:;", "TITLE:", "ROLE:", "NOTE:"),
                    card(
                            "4.0",
                            "UID:escapes",
                            "FN:Zoë \\; Ünïcødé 😀",
                            "NOTE:line one\\nline two\\, three\\; four",
                            "ORG:Café\\, Inc.;Département",
                            "EMAIL:zoe@exämple.org"),
                    card(
                            "4.0",
                            "UID:types",
                            "FN:Types",
                            "TEL;TYPE=fax,work:1",
                            "TEL;TYPE=fax,home:2",
                            "TEL;TYPE=fax:3",
                            "TEL;TYPE=pager:4",
                            "TEL;TYPE=pager,work:5",
                            "TEL;TYPE=car:6",
                            "TEL;TYPE=isdn:7",
                            "TEL;TYPE=voice:8",
                            "TEL;TYPE=cell,work:9",
                            "TEL:10",
                            "EMAIL;TYPE=internet:a@b.c",
                            "ADR;TYPE=work:;;Work St;;;;"),
                    card(
                            "4.0",
                            "UID:dates",
                            "FN:Dates",
                            "GENDER:;nonbinary",
                            "BDAY:1990-01-02T10:00:00",
                            "ANNIVERSARY:--0601"),
                    card(
                            "4.0",
                            "UID:dups",
                            "FN:First",
                            "FN:Second",
                            "N:One;A;;;",
                            "N:Two;B;;;",
                            "ORG:First Org",
                            "ORG:Second Org",
                            "TITLE:T1",
                            "TITLE:T2",
                            "BDAY:20000101",
                            "BDAY:20010101"),
                    card(
                            "3.0",
                            "UID:groups",
                            "FN:Grouped",
                            "item1.TEL:+1 555",
                            "item1.X-ABLabel:Batphone",
                            "item2.EMAIL;TYPE=INTERNET:g@x.example",
                            "item2.X-ABLabel:_$!<Other>!$_"),
                    card("4.0", "UID:onlyuid"),
                    card(
                            "4.0",
                            "UID:folded",
                            "FN:Folded Name That Is Rather Long And Goes On And On Beyond The",
                            "  Line Limit",
                            "NOTE:A very long note that is folded across several lines because",
                            "  it exceeds seventy-five octets per line as the RFC asks"),
                    card(
                            "4.0",
                            "UID:sparse",
                            "FN:Sparse",
                            "ADR:PO 1;;;;;;",
                            "ADR:;Ext only;;;;;",
                            "ADR:;;;;;;",
                            "EMAIL:",
                            "TEL:",
                            "URL:",
                            "GENDER:M"),
                    "not a vcard");

    private static Map<String, Object> row(String mimetype, Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mimetype", mimetype);
        for (int index = 0; index < pairs.length; index += 2) {
            row.put((String) pairs[index], pairs[index + 1]);
        }
        return row;
    }

    /**
     * Phone row sets as the provider answers (strings, null columns absent).
     */
    public static List<List<Map<String, Object>>> phoneRows() {
        List<List<Map<String, Object>>> sets = new ArrayList<>();
        sets.add(List.of());
        sets.add(List.of(row("vnd.android.cursor.item/name", "data1", "Jane Doe")));
        sets.add(
                List.of(
                        row(
                                "vnd.android.cursor.item/name",
                                "data1", "Jane Doe",
                                "data2", "Jane",
                                "data3", "Doe")));
        sets.add(
                List.of(
                        row(
                                "vnd.android.cursor.item/name",
                                "data1", "Jane Doe Yo",
                                "data2", "Jane",
                                "data3", "Doe",
                                "data4", "",
                                "data5", "",
                                "data6", ""),
                        row(
                                "vnd.android.cursor.item/phone_v2",
                                "data1", "+331111",
                                "data2", "2",
                                "is_primary", "0")));
        sets.add(
                List.of(
                        row(
                                "vnd.android.cursor.item/phone_v2",
                                "data1", "0611",
                                "data2", "0",
                                "data3", "Batphone"),
                        row("vnd.android.cursor.item/phone_v2", "data1", "", "data2", "2"),
                        row(
                                "vnd.android.cursor.item/phone_v2",
                                "data1", "0612",
                                "data2", "17",
                                "is_primary", "1")));
        sets.add(
                List.of(
                        row(
                                "vnd.android.cursor.item/postal-address_v2",
                                "data1", "12 Main St, Paris",
                                "data2", "1")));
        sets.add(
                List.of(
                        row(
                                "vnd.android.cursor.item/organization",
                                "data1", "",
                                "data5", "",
                                "data4", "Boss",
                                "data6", "")));
        sets.add(
                List.of(
                        row(
                                "vnd.android.cursor.item/organization",
                                "data1", "ACME",
                                "data4", "",
                                "data6", "CTO")));
        sets.add(
                List.of(
                        row("vnd.android.cursor.item/contact_event", "data1", "1985-04-12", "data2", "3"),
                        row("vnd.android.cursor.item/contact_event", "data1", "2010-06-01", "data2", "1")));
        sets.add(
                List.of(
                        row("vnd.android.cursor.item/note", "data1", ""),
                        row("vnd.android.cursor.item/note", "data1", "phone note"),
                        row("vnd.android.cursor.item/nickname", "data1", ""),
                        row("vnd.android.cursor.item/nickname", "data1", "Nick", "data2", "1"),
                        row("vnd.android.cursor.item/website", "data1", "https://x.example")));
        sets.add(
                List.of(
                        row("vnd.android.cursor.item/photo", "data15", "blob"),
                        row("vnd.android.cursor.item/group_membership", "data1", "3")));
        sets.add(
                List.of(
                        row(
                                "vnd.android.cursor.item/email_v2",
                                "data1", "a@b.c",
                                "data2", "2",
                                "is_primary", "1"),
                        row("vnd.android.cursor.item/email_v2", "data1", "d@e.f", "data2", "9")));
        sets.add(
                List.of(
                        row(
                                "vnd.android.cursor.item/postal-address_v2",
                                "data4", "1 Rue",
                                "data5", "PO 9",
                                "data6", "",
                                "data7", "Lyon",
                                "data8", "",
                                "data9", "69000",
                                "data10", "France",
                                "data2", "2",
                                "is_primary", "1"),
                        row("vnd.android.cursor.item/postal-address_v2", "data2", "3")));
        return sets;
    }

    /**
     * Edit-form models as the form leaves them.
     *
     * <p>Partial objects, removed parts, present-empty values, keys it never
     * applies.
     */
    public static final List<String> FORM_MODELS =
            List.of(
                    "{}",
                    "{\"displayName\":\"New Person\"}",
                    "{\"name\":{\"given\":\"Jane\"}}",
                    "{\"name\":{\"given\":\"Jane\",\"family\":\"Doe\"},"
                            + "\"phones\":[{\"number\":\"+33 1\",\"types\":[\"cell\"],\"pref\":true}]}",
                    "{\"organization\":{\"company\":\"ACME\"}}",
                    "{\"organization\":{\"department\":\"R&D\"},\"title\":\"\",\"role\":\"  \"}",
                    "{\"gender\":{\"sex\":\"\",\"identity\":\"nb\"}}",
                    "{\"gender\":{\"sex\":\"M\",\"identity\":\"\"}}",
                    "{\"birthday\":\"1990-01-02\",\"anniversary\":\"2000-03-04\"}",
                    "{\"birthday\":\"\",\"anniversary\":\"bad\"}",
                    "{\"addresses\":[{\"street\":\"1 A\",\"city\":\"B\",\"region\":\"\","
                            + "\"postcode\":\"\",\"country\":\"\",\"pobox\":\"\",\"ext\":\"\","
                            + "\"pref\":false,\"types\":[\"home\"]}]}",
                    "{\"uid\":\"x\",\"categories\":[\"a\"],\"impps\":[\"xmpp:a@b\"],"
                            + "\"languages\":[\"en\"]}",
                    "{\"relations\":[{\"value\":\"mailto:x@y\",\"types\":[\"friend\"],"
                            + "\"pref\":false},{\"value\":\"Bob\",\"types\":[],\"pref\":true},"
                            + "{\"value\":\" \",\"types\":[],\"pref\":false}]}",
                    "{\"displayName\":\"\",\"name\":{},\"organization\":{},\"nicknames\":[],"
                            + "\"notes\":[\"\",\"kept\"],\"websites\":[\" \"]}",
                    "{\"emails\":[{\"address\":\" a@b.c \",\"types\":[\"work\",\"home\"],"
                            + "\"pref\":true},{\"address\":\"\",\"types\":[],\"pref\":false}]}");

    /** The edit-form models as field models, read with the bridge defaults. */
    public static List<ContactFields> formFields() throws Exception {
        List<ContactFields> fields = new ArrayList<>();
        for (String model : FORM_MODELS) {
            fields.add(ContactFields.from(new JSONObject(model)));
        }
        return fields;
    }
}
