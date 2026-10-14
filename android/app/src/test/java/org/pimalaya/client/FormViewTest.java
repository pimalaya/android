package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.pimalaya.client.ReplyShape.assertMirrors;
import static org.pimalaya.client.ReplyShape.elements;
import static org.pimalaya.client.ReplyShape.field;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Pins the edit form's bridge replies on the real bridge.
 *
 * <p>View support of every projection, typed dialog entries, merge and conflict
 * forms, each record mirroring its struct. The form end to end is
 * ContactFormTest's.
 */
@RunWith(RobolectricTestRunner.class)
public class FormViewTest {
    @Test
    public void everyViewReadsAsTheBridgeComputesIt() throws Exception {
        for (String vcard : CardCorpus.VCARDS.subList(0, CardCorpus.VCARDS.size() - 1)) {
            ContactFields fields = ContactClient.projectCard(vcard);
            String raw = Native.formView(fields.toJson().toString());
            assertEquals(FormView.from(new JSONObject(raw)), ContactClient.formView(fields));
        }
    }

    @Test
    public void theRichViewCarriesPositionsDatesAndGender() throws Exception {
        ContactFields fields = ContactClient.projectCard(CardCorpus.VCARDS.get(1));
        String raw = Native.formView(fields.toJson().toString());
        assertMirrors(FormView.class, raw);
        assertMirrors(FormAddress.class, elements(field(raw, "addresses")).get(0));
        assertMirrors(FormDate.class, field(raw, "birthday"));
        assertMirrors(FormGender.class, field(raw, "gender"));

        FormView view = ContactClient.formView(fields);
        assertEquals(new FormDate(12, 4, 1985), view.birthday());
        assertEquals(new FormDate(1, 6, 2010), view.anniversary());
        assertEquals(new FormGender("woman", 2), view.gender());
        assertEquals(List.of(0, 2), view.phones());
        assertEquals(List.of(0, 1), view.emails());
        assertEquals(List.of(0, 4), view.relations());
        assertEquals(0, view.addresses().get(0).index());
        assertEquals(1, view.addresses().get(1).index());
        assertEquals("Dr. Jane Q. Doe Jr.", view.name());

        FormView bare = ContactClient.formView(new ContactFields.Builder().build());
        assertNull(bare.birthday());
        assertNull(bare.anniversary());
        assertNull(bare.gender());
        assertEquals(List.of(), bare.phones());
        assertEquals(List.of(), bare.addresses());
    }

    @Test
    public void dialogEntriesReadAsTheBridgeTypesThem() throws Exception {
        assertMirrors(ContactPhone.class, Native.formEntry("phone", 2, "+33 1", true));
        assertMirrors(ContactEmail.class, Native.formEntry("email", 1, "a@b.c", false));
        assertMirrors(ContactRelation.class, Native.formEntry("relation", 4, "Bob", false));
        assertMirrors(ContactGender.class, Native.formEntry("gender", 2, "", false));

        assertEquals(
                new ContactPhone("+33 1", true, List.of("work")),
                ContactClient.formPhone(2, "+33 1", true));
        assertEquals(new ContactPhone("", false, List.of()), ContactClient.formPhone(7, "", false));
        assertEquals(
                new ContactEmail("a@b.c", false, List.of("work")),
                ContactClient.formEmail(1, "a@b.c", false));
        assertEquals(
                new ContactRelation(true, List.of("friend"), "Bob"),
                ContactClient.formRelation(4, "Bob", true));
        assertEquals(List.of("home"), ContactClient.formAddressTypes(0));
        assertEquals(List.of("work"), ContactClient.formAddressTypes(1));
        assertEquals(List.of(), ContactClient.formAddressTypes(2));
        assertEquals(List.of("home"), ContactClient.formAddressTypes(-2));
        assertEquals(new ContactGender("", "F"), ContactClient.formGender(2, ""));
        // NOTE: an unset gender answers {}, read both parts empty as the
        // form's JSON model did.
        assertEquals("{}", Native.formEntry("gender", 0, "", false));
        assertEquals(new ContactGender("", ""), ContactClient.formGender(0, ""));
        assertEquals("1990-01-02", ContactClient.formDate(1990, 1, 2));
    }

    @Test
    public void aMergeReadsAsTheBridgeUnitesTheCards() throws Exception {
        List<String> docs =
                List.of(
                        CardCorpus.VCARDS.get(3),
                        CardCorpus.VCARDS.get(1),
                        CardCorpus.VCARDS.get(2));
        String raw = Native.mergeCards(new JSONArray(docs).toString());
        assertMirrors(CardsMerge.class, raw);

        JSONObject reply = new JSONObject(raw);
        CardsMerge merged = ContactClient.mergeCards(docs);
        assertEquals(sortedLists(reply.getJSONObject("alternatives")), merged.alternatives());
        assertEquals(new ArrayList<>(merged.alternatives().keySet()),
                sortedKeys(reply.getJSONObject("alternatives")));
        assertFalse(merged.alternatives().isEmpty());
        assertEquals(Json.strings(reply.getJSONArray("changed")), merged.changed());
        assertFalse(merged.changed().isEmpty());
        assertEquals(ContactFields.from(reply.getJSONObject("model")), merged.model());
        assertEquals(reply.getString("vcard"), merged.vcard());

        assertThrows(PimalayaException.class, () -> ContactClient.mergeCards(List.of()));
    }

    @Test
    public void aConflictFormReadsAsTheBridgeSettlesIt() throws Exception {
        String base = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:c\r\nFN:Base\r\nEND:VCARD\r\n";
        String local =
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:c\r\nFN:Local\r\nREV:20210101T000000Z\r\n"
                        + "END:VCARD\r\n";
        String remote =
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:c\r\nFN:Remote\r\nREV:20220101T000000Z\r\n"
                        + "END:VCARD\r\n";
        String raw = Native.mergeConflictForm(base, local, remote);
        assertMirrors(ConflictForm.class, raw);

        JSONObject reply = new JSONObject(raw);
        ConflictForm form = ContactClient.mergeConflictForm(base, local, remote);
        assertFalse(form.resolved());
        assertEquals(List.of("Remote", "Local"), form.alternatives().get("displayName"));
        assertEquals(sortedLists(reply.getJSONObject("alternatives")), form.alternatives());
        assertEquals(List.of(), form.changed());
        assertEquals("Remote", form.model().displayName());
        assertEquals(reply.getString("vcard"), form.vcard());

        ConflictForm clean = ContactClient.mergeConflictForm(base, local, base);
        assertTrue(clean.resolved());
        assertTrue(clean.alternatives().isEmpty());
    }

    private static Map<String, List<String>> sortedLists(JSONObject object)
            throws Exception {
        Map<String, List<String>> lists = new TreeMap<>();
        for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
            String key = keys.next();
            lists.put(key, Json.strings(object.getJSONArray(key)));
        }
        return lists;
    }

    private static List<String> sortedKeys(JSONObject object) {
        List<String> keys = new ArrayList<>();
        object.keys().forEachRemaining(keys::add);
        keys.sort(null);
        return keys;
    }
}
