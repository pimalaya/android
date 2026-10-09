package org.pimalaya;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;

import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;

/**
 * A phone mirror is granted only whole: the prompt asks every permission of
 * a mirror not yet fully granted, and a mirror counts as granted once every
 * one of its permissions is, so a half answer leaves its switch off.
 */
public class PhoneMirrorTest {
    private static final Set<PhoneMirror> CONTACTS = EnumSet.of(PhoneMirror.CONTACTS);

    @Test
    public void nothingGrantedAsksTheWholePair() {
        assertArrayEquals(
                new String[] {
                    Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS
                },
                PhoneMirror.missing(CONTACTS, permission -> false));
        assertTrue(PhoneMirror.granted(CONTACTS, permission -> false).isEmpty());
    }

    @Test
    public void halfGrantedAsksThePairAgainAndIsNotGranted() {
        String read = Manifest.permission.READ_CONTACTS;
        assertEquals(2, PhoneMirror.missing(CONTACTS, read::equals).length);
        assertTrue(PhoneMirror.granted(CONTACTS, read::equals).isEmpty());
    }

    @Test
    public void allGrantedAsksNothing() {
        assertEquals(0, PhoneMirror.missing(CONTACTS, permission -> true).length);
        assertEquals(CONTACTS, PhoneMirror.granted(CONTACTS, permission -> true));
    }

    @Test
    public void noMirrorWantedAsksNothingAndGrantsNothing() {
        Set<PhoneMirror> none = EnumSet.noneOf(PhoneMirror.class);
        assertEquals(0, PhoneMirror.missing(none, permission -> false).length);
        assertTrue(PhoneMirror.granted(none, permission -> true).isEmpty());
    }
}
