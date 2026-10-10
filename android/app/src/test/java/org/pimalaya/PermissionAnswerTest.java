package org.pimalaya;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;

import org.junit.Test;

/**
 * A denial sends the user to the system settings only when Android would
 * not ask again: no rationale for a permission still missing.
 */
public class PermissionAnswerTest {
    private static final String READ = Manifest.permission.READ_CONTACTS;
    private static final String WRITE = Manifest.permission.WRITE_CONTACTS;
    private static final String[] PAIR = {READ, WRITE};

    @Test
    public void grantedIsNotBlocked() {
        assertFalse(PermissionAnswer.blocked(PAIR, permission -> true, permission -> false));
    }

    @Test
    public void aDenialAndroidWouldAskAgainIsNotBlocked() {
        assertFalse(PermissionAnswer.blocked(PAIR, permission -> false, permission -> true));
    }

    @Test
    public void aDenialAndroidWillNotAskAgainIsBlocked() {
        assertTrue(PermissionAnswer.blocked(PAIR, permission -> false, permission -> false));
    }

    @Test
    public void oneOfThePairBlockedBlocksIt() {
        assertTrue(PermissionAnswer.blocked(PAIR, READ::equals, permission -> false));
    }

    @Test
    public void aCancelledPromptAsksNothingAndIsNotBlocked() {
        assertFalse(
                PermissionAnswer.blocked(
                        new String[0], permission -> false, permission -> false));
    }
}
