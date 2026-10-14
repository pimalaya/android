package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.pimalaya.client.ReplyShape.assertMirrors;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;

/** Pins {@link Derived} on the real bridge: same values as the reply's own fields. */
@RunWith(RobolectricTestRunner.class)
public class DerivedTest {
    private static final byte[] VCARD =
            "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:alice\r\nFN:Alice Martin\r\nEND:VCARD\r\n"
                    .getBytes(StandardCharsets.UTF_8);

    private static final byte[] MAIL =
            ("Message-ID: <m1@example.org>\r\nDate: Wed, 07 Oct 2026 08:00:00 +0000\r\n"
                            + "From: Ann <a@example.org>\r\nSubject: Hi\r\n\r\nbody\r\n")
                    .getBytes(StandardCharsets.UTF_8);

    @Test
    public void aCardDerivesItsUidAndSummary() throws Exception {
        assertDerives(PimdirSummary.CONTACT, VCARD);
    }

    @Test
    public void aMessageDerivesItsKeyAndSummary() throws Exception {
        assertDerives(PimdirSummary.MAIL, MAIL);
    }

    private static void assertDerives(String kind, byte[] body) throws Exception {
        String raw = Native.pimdirDerive(kind, body);
        assertMirrors(Derived.class, raw);

        JSONObject reply = new JSONObject(raw);
        Derived derived = PimdirSql.derive(kind, body);
        assertEquals(reply.getString("linkId"), derived.linkId());
        assertEquals(reply.optString("sortKey", ""), derived.sortKey());
        assertNotNull(derived.summary());
        assertEquals(
                reply.optJSONObject("summary").toString(), derived.summary().toString());
    }
}
