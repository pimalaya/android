package org.pimalaya;

import static org.junit.Assert.assertSame;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.pimdir.PimdirDb;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** The process: what every screen, service and job of the app shares. */
@RunWith(RobolectricTestRunner.class)
public class AppTest {
    /**
     * Every caller of the process shares one store, so a sync adapter run
     * opens no connection pool of its own to leak: the same helper, the
     * same database, whichever context asks.
     */
    @Test
    public void theProcessSharesOneStore() {
        PimdirDb shared = App.of(RuntimeEnvironment.getApplication()).pimdir();
        assertSame(shared, App.of(RuntimeEnvironment.getApplication()).pimdir());
        assertSame(
                shared.getReadableDatabase(),
                App.of(RuntimeEnvironment.getApplication()).pimdir().getWritableDatabase());
        assertSame(
                App.of(RuntimeEnvironment.getApplication()).cards(),
                App.of(RuntimeEnvironment.getApplication()).cards());
    }
}
