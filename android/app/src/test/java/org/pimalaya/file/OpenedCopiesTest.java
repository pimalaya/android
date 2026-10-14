package org.pimalaya.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** The name a copy of an opened attachment takes, against a hostile one. */
@RunWith(RobolectricTestRunner.class)
public class OpenedCopiesTest {
    @Test
    public void anAttachmentNameDropsWhatWouldSpoofOrBreakIt() {
        assertEquals("invoicefdp.apk", OpenedCopies.safe("invoice\u202Efdp.apk"));
        assertEquals("a_b_c", OpenedCopies.safe("a/b\\c"));
        assertEquals("x", OpenedCopies.safe("\u0000\u200Ex"));
        assertEquals("file.hidden", OpenedCopies.safe(".hidden"));
    }

    @Test
    public void anAttachmentNameFitsTheFileSystemInBytes() {
        // NOTE: 120 three-byte characters, 360 bytes, which ext4 refuses.
        String name = OpenedCopies.safe("\u6587".repeat(120) + ".pdf");

        assertTrue(name.getBytes(StandardCharsets.UTF_8).length <= 200);
        assertTrue(name.endsWith(".pdf"));

        String smile = new String(Character.toChars(0x1F600));
        String cut = OpenedCopies.safe("a" + smile.repeat(100));
        assertTrue(cut.getBytes(StandardCharsets.UTF_8).length <= 200);
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
        assertEquals(cut.codePointCount(0, cut.length()) * 2 - 1, cut.length());
    }
}
