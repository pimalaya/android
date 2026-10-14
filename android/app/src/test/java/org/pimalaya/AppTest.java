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
     * Every caller shares one store: a sync adapter run opens no pool to leak.
     *
     * <p>The same helper, the same database, whichever context asks.
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
