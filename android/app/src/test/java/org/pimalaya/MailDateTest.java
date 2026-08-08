package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The merged mail list is ordered entirely by what this returns, so the
 * spellings mail actually arrives with have to parse, and the ones that
 * do not have to sink rather than throw.
 */
public class MailDateTest {
    /** 2026-01-05T09:00:00Z, the instant every fixture below names. */
    private static final long REFERENCE = 1767603600000L;

    @Test
    public void readsTheCommonSpellings() {
        assertEquals(REFERENCE, MailDate.toStamp("Mon, 5 Jan 2026 09:00:00 +0000"));
        assertEquals(REFERENCE, MailDate.toStamp("5 Jan 2026 09:00:00 +0000"));
        assertEquals(REFERENCE, MailDate.toStamp("Mon, 5 Jan 2026 10:00:00 +0100"));
        assertEquals(REFERENCE, MailDate.toStamp("Mon, 5 Jan 2026 04:00:00 -0500"));
    }

    @Test
    public void readsTheOnesWithoutSeconds() {
        assertEquals(REFERENCE, MailDate.toStamp("Mon, 5 Jan 2026 09:00 +0000"));
        assertEquals(REFERENCE, MailDate.toStamp("5 Jan 2026 09:00 +0000"));
    }

    @Test
    public void ignoresATrailingZoneComment() {
        assertEquals(REFERENCE, MailDate.toStamp("Mon, 5 Jan 2026 10:00:00 +0100 (CET)"));
    }

    @Test
    public void sinksWhatItCannotRead() {
        assertEquals(0, MailDate.toStamp(null));
        assertEquals(0, MailDate.toStamp(""));
        assertEquals(0, MailDate.toStamp("   "));
        assertEquals(0, MailDate.toStamp("yesterday"));
    }

    @Test
    public void ordersChronologicallyNotAlphabetically() {
        // The reason the sort cannot run on the raw text: February
        // sorts before January as a string, and the offset moves the
        // instant regardless of how the text reads.
        long january = MailDate.toStamp("Mon, 5 Jan 2026 09:00:00 +0000");
        long february = MailDate.toStamp("Thu, 5 Feb 2026 09:00:00 +0000");
        assertTrue(january < february);

        long early = MailDate.toStamp("Mon, 5 Jan 2026 09:00:00 +0100");
        long late = MailDate.toStamp("Mon, 5 Jan 2026 09:00:00 -0100");
        assertTrue(early < late);
    }
}
