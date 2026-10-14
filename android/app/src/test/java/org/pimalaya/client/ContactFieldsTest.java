package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.pimalaya.client.ReplyShape.assertMirrors;
import static org.pimalaya.client.ReplyShape.assertSameJson;
import static org.pimalaya.client.ReplyShape.elements;
import static org.pimalaya.client.ReplyShape.field;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Pins the field model on the real bridge: every corpus projection read
 * into the record and written back as the same JSON value (so the record
 * holds exactly what the bridge said, absent apart from present-empty),
 * every record mirroring its Rust struct, and the conflicted-push merge.
 * Mapping corpus outcomes are pinned by MappingCorpusTest.
 */
@RunWith(RobolectricTestRunner.class)
public class ContactFieldsTest {
    @Test
    public void everyProjectionReadsAndWritesBackAsTheBridgeSaidIt() throws Exception {
        for (String vcard : CardCorpus.VCARDS.subList(0, CardCorpus.VCARDS.size() - 1)) {
            String raw = Native.projectCard(vcard);
            ContactFields fields = ContactClient.projectCard(vcard);
            assertEquals(ContactFields.from(new JSONObject(raw)), fields);
            assertSameJson(raw, fields.toJson().toString());
        }
    }

    @Test
    public void everyRecordMirrorsItsStruct() throws Exception {
        String raw = Native.projectCard(CardCorpus.VCARDS.get(1));
        assertMirrors(ContactFields.class, raw);
        assertMirrors(ContactAddress.class, elements(field(raw, "addresses")).get(0));
        assertMirrors(ContactEmail.class, elements(field(raw, "emails")).get(0));
        assertMirrors(ContactGender.class, field(raw, "gender"));
        assertMirrors(ContactName.class, field(raw, "name"));
        assertMirrors(ContactOrganization.class, field(raw, "organization"));
        assertMirrors(ContactPhone.class, elements(field(raw, "phones")).get(0));
        assertMirrors(ContactRelation.class, elements(field(raw, "relations")).get(0));
    }

    @Test
    public void presentEmptyIsNotAbsent() throws Exception {
        // NOTE: FN:, N:;;;;, ORG:;, TITLE: and ROLE: project present and
        // empty; a card without them projects them absent.
        ContactFields empties = ContactClient.projectCard(CardCorpus.VCARDS.get(5));
        assertEquals("", empties.displayName());
        assertEquals(new ContactName("", "", "", "", ""), empties.name());
        assertEquals(new ContactOrganization("", ""), empties.organization());
        assertEquals("", empties.title());
        assertEquals("", empties.role());
        assertEquals(List.of(""), empties.notes());

        ContactFields bare = ContactClient.projectCard(CardCorpus.VCARDS.get(11));
        assertNull(bare.displayName());
        assertNull(bare.name());
        assertNull(bare.organization());
        assertNull(bare.title());
        assertNull(bare.role());
        assertNull(bare.birthday());
        assertNull(bare.gender());
        assertEquals("onlyuid", bare.uid());

        // NOTE: the phone mapping's present-empty fields reach the bridge
        // as present keys.
        ContactFields.Builder phone = new ContactFields.Builder();
        phone.displayName = "";
        phone.name = new ContactName("", "", "", "", "");
        phone.organization = new ContactOrganization("", "");
        phone.title = "";
        phone.role = "";
        phone.birthday = "";
        assertSameJson(
                "{\"addresses\":[],\"birthday\":\"\",\"categories\":[],\"displayName\":\"\","
                        + "\"emails\":[],\"impps\":[],\"languages\":[],"
                        + "\"name\":{\"family\":\"\",\"given\":\"\",\"middle\":\"\","
                        + "\"prefix\":\"\",\"suffix\":\"\"},\"nicknames\":[],\"notes\":[],"
                        + "\"organization\":{\"company\":\"\",\"department\":\"\"},"
                        + "\"phones\":[],\"relations\":[],\"role\":\"\",\"title\":\"\","
                        + "\"websites\":[]}",
                phone.build().toJson().toString());
        assertSameJson(
                "{\"addresses\":[],\"categories\":[],\"emails\":[],\"impps\":[],"
                        + "\"languages\":[],\"nicknames\":[],\"notes\":[],\"phones\":[],"
                        + "\"relations\":[],\"websites\":[]}",
                new ContactFields.Builder().build().toJson().toString());
    }

    @Test
    public void aFormModelReadsWithTheBridgeDefaults() throws Exception {
        ContactFields fields =
                ContactFields.from(
                        new JSONObject(
                                "{\"name\":{\"given\":\"Jane\"},\"organization\":{},"
                                        + "\"phones\":[{\"number\":\"1\"}],\"title\":\"\"}"));
        assertEquals(new ContactName("", "Jane", "", "", ""), fields.name());
        assertEquals(new ContactOrganization("", ""), fields.organization());
        assertEquals(List.of(new ContactPhone("1", false, List.of())), fields.phones());
        assertEquals("", fields.title());
        assertNull(fields.displayName());
        assertEquals(List.of(), fields.emails());
    }

    @Test
    public void aConflictedPushMergesAsTheBridgeAnswers() throws Exception {
        String raw =
                Native.mergeCardChanges(
                        CardCorpus.VCARDS.get(3), CardCorpus.VCARDS.get(1), CardCorpus.VCARDS.get(2));
        assertMirrors(ConflictMerge.class, raw);
        ConflictMerge merged =
                ContactClient.mergeCardChanges(
                        CardCorpus.VCARDS.get(3), CardCorpus.VCARDS.get(1), CardCorpus.VCARDS.get(2));
        assertEquals(new ConflictMerge(4, new JSONObject(raw).getString("vcard")), merged);

        assertEquals(
                new ConflictMerge(0, CardCorpus.VCARDS.get(0)),
                ContactClient.mergeCardChanges("", CardCorpus.VCARDS.get(0), CardCorpus.VCARDS.get(0)));
        assertThrows(
                PimalayaException.class,
                () -> ContactClient.mergeCardChanges("", "not a vcard", CardCorpus.VCARDS.get(0)));
    }
}
