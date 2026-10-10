package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;

/**
 * The setups' options start off, every run, and turn on only once their
 * permission is granted: a refusal leaves the switch off, and one mirror's
 * grant never turns the other on.
 */
public class SetupSwitchesTest {
    private static final Set<PhoneMirror> NONE = EnumSet.noneOf(PhoneMirror.class);

    @Test
    public void everyOptionStartsOff() {
        SetupSwitches switches = new SetupSwitches();

        assertTrue(switches.mirrors.isEmpty());
        assertFalse(switches.notifies);
    }

    @Test
    public void aNewRunStartsOffAgain() {
        SetupSwitches switches = new SetupSwitches();
        switches.mirror(PhoneMirror.CONTACTS, true, EnumSet.of(PhoneMirror.CONTACTS));
        switches.notify(true, true);

        switches.reset();

        assertTrue(switches.mirrors.isEmpty());
        assertFalse(switches.notifies);
    }

    @Test
    public void aGrantedMirrorIsOn() {
        SetupSwitches switches = new SetupSwitches();

        assertTrue(switches.mirror(PhoneMirror.CALENDAR, true, EnumSet.of(PhoneMirror.CALENDAR)));
        assertEquals(EnumSet.of(PhoneMirror.CALENDAR), switches.mirrors);
    }

    @Test
    public void aRefusedMirrorStaysOff() {
        SetupSwitches switches = new SetupSwitches();

        assertFalse(switches.mirror(PhoneMirror.CONTACTS, true, NONE));
        assertTrue(switches.mirrors.isEmpty());
    }

    @Test
    public void anotherMirrorsGrantDoesNotCount() {
        SetupSwitches switches = new SetupSwitches();

        assertFalse(
                switches.mirror(PhoneMirror.CONTACTS, true, EnumSet.of(PhoneMirror.CALENDAR)));
        assertTrue(switches.mirrors.isEmpty());
    }

    @Test
    public void aMirrorTurnedOffIsOffWhateverIsGranted() {
        SetupSwitches switches = new SetupSwitches();
        Set<PhoneMirror> both = EnumSet.allOf(PhoneMirror.class);
        switches.mirror(PhoneMirror.CONTACTS, true, both);
        switches.mirror(PhoneMirror.CALENDAR, true, both);

        assertFalse(switches.mirror(PhoneMirror.CONTACTS, false, both));
        assertEquals(EnumSet.of(PhoneMirror.CALENDAR), switches.mirrors);
    }

    @Test
    public void notificationsFollowTheirAnswer() {
        SetupSwitches switches = new SetupSwitches();

        assertFalse(switches.notify(true, false));
        assertFalse(switches.notifies);
        assertTrue(switches.notify(true, true));
        assertTrue(switches.notifies);
        assertFalse(switches.notify(false, true));
        assertFalse(switches.notifies);
    }
}
