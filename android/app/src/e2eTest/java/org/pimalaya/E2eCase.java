package org.pimalaya;

import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

import org.junit.After;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.sync.RemotePass;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

/**
 * What every end-to-end test stands on, once per target: the account built as
 * the connection flow builds it, a phone device and an observer device with a
 * store each, a tag of its own, the scratch mailboxes it creates and the
 * cleanup that removes them and every tagged message however the test ends.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
public abstract class E2eCase {
    /** The targets swept of what older runs left, once a run. */
    private static final Set<String> SWEPT = new HashSet<>();

    protected final E2eTarget target;

    /** The address the account and its fixture sign in as. */
    protected String address;

    /** What this test's scratch mailboxes and messages are named by. */
    protected String tag;

    /** A mail target's fixture, null for the other domains. */
    protected E2eFixture fixture;

    /** A contacts or calendar target's fixture, null for mail. */
    protected E2eItemFixture items;

    /** The account as the connection flow builds it. */
    protected AccountEntry account;

    /** The device the test acts on. */
    protected E2eDevice phone;

    private E2eDevice observer;

    private final List<String> scratch = new ArrayList<>();

    protected E2eCase(String target) {
        this.target = E2eTarget.of(target);
    }

    /**
     * The mail targets; a contacts or calendar test class shadows this with
     * its domain's.
     */
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static Collection<Object[]> targets() {
        return E2eTarget.parameters(E2eTarget.MAIL);
    }

    @Rule
    public final TestWatcher report =
            new TestWatcher() {
                @Override
                protected void succeeded(Description description) {
                    record(description, "passed", null);
                }

                @Override
                protected void failed(Throwable error, Description description) {
                    record(description, "FAILED", String.valueOf(error.getMessage()));
                }

                @Override
                protected void skipped(AssumptionViolatedException why, Description description) {
                    record(description, "skipped", why.getMessage());
                }

                private void record(Description description, String status, String detail) {
                    E2eScenario scenarios = description.getAnnotation(E2eScenario.class);
                    String method = description.getMethodName();
                    int bracket = method.indexOf('[');
                    E2eReport.record(
                            description.getTestClass().getSimpleName()
                                    + "."
                                    + (bracket < 0 ? method : method.substring(0, bracket)),
                            scenarios == null ? new String[0] : scenarios.value(),
                            target.id,
                            status,
                            detail);
                }
            };

    @Before
    public void setUpTarget() throws Exception {
        String skipped = target.skipped();
        assumeTrue(skipped, skipped == null);
        String only = System.getProperty("e2e.only", "");
        assumeTrue(
                "not in e2e.only",
                only.isEmpty() || List.of(only.split(",")).contains(getClass().getSimpleName()));
        if (Boolean.getBoolean("e2e.log")) {
            ShadowLog.stream = System.out;
        }

        address = target.address;
        tag =
                E2eFixture.PREFIX
                        + LocalDateTime.now(ZoneOffset.UTC).format(E2eFixture.STAMP)
                        + "-"
                        + UUID.randomUUID().toString().substring(0, 6);
        LocalDateTime stale = LocalDateTime.now(ZoneOffset.UTC).minusHours(2);
        if (target.mailFixture != null) {
            fixture = target.mailFixture.call();
        } else {
            items = target.itemFixture.call();
        }
        synchronized (SWEPT) {
            if (SWEPT.add(target.id)) {
                if (fixture != null) {
                    fixture.sweepStale(stale);
                } else {
                    items.sweepStale(stale);
                }
            }
        }
        E2eDevice.network(RuntimeEnvironment.getApplication(), E2eDevice.Network.WIFI);
        phone = new E2eDevice(RuntimeEnvironment.getApplication(), "phone");
        phone.tag = tag;
        account = target.account(phone.client);
    }

    @After
    public void cleanUp() throws Exception {
        if (fixture == null && items == null) {
            return;
        }
        Exception failure = null;
        for (String name : scratch) {
            try {
                if (fixture != null) {
                    fixture.deleteMailbox(name);
                } else {
                    items.deleteCollection(name);
                }
            } catch (Exception error) {
                failure = failure == null ? error : failure;
            }
        }
        try {
            if (fixture != null) {
                fixture.sweep(tag);
            } else {
                items.sweep(tag);
            }
        } catch (Exception error) {
            failure = failure == null ? error : failure;
        }
        if (fixture != null) {
            fixture.close();
        }
        if (failure != null) {
            System.err.println("e2e cleanup of " + tag + " on " + target + ": " + failure);
        }
    }

    /**
     * Creates a scratch mailbox, calendar or address book the cleanup
     * deletes, answering its name.
     */
    protected String scratch() throws Exception {
        String name = tag + "-" + (scratch.size() + 1);
        scratch.add(name);
        if (fixture != null) {
            fixture.createMailbox(name);
        } else {
            items.createCollection(name);
        }
        return name;
    }

    /** A message from the account to itself, its subject the tag and a label. */
    protected E2eMessage message(String label) {
        return new E2eMessage(
                address, tag + " " + label, "<" + label + "." + tag + "@pimalaya-e2e.invalid>");
    }

    /** The subject {@link #message} gives a label. */
    protected String subject(String label) {
        return tag + " " + label;
    }

    /** The phone, connected: the roster listed and its first step taken. */
    protected E2eDevice connected() {
        Exception failure = phone.connect(account);
        if (failure != null) {
            throw new AssertionError("the first sync failed: " + failure.getMessage(), failure);
        }
        return phone;
    }

    /**
     * A second device on the same account, connected now: what reached the
     * server, read back through the app's own sync.
     */
    protected E2eDevice observer() throws Exception {
        if (observer == null) {
            observer = new E2eDevice(RuntimeEnvironment.getApplication(), "observer");
            observer.tag = tag;
            Exception failure = observer.connect(target.account(observer.client));
            if (failure != null) {
                throw new AssertionError(
                        "the observer's first sync failed: " + failure.getMessage(), failure);
            }
        }
        return observer;
    }

    /** One Load more on a device's account, which must not fail. */
    protected void loadMore(E2eDevice device, String only) {
        Exception failure = device.loadMore(address, only);
        if (failure != null) {
            throw new AssertionError("the step failed: " + failure.getMessage(), failure);
        }
    }

    /** One pass over some collections of a device's account, which must not fail. */
    protected void pass(E2eDevice device, String... collections) {
        RemotePass.MailPass pass = device.sync(address, List.of(collections));
        if (pass.failure != null) {
            throw new AssertionError("the pass failed: " + pass.failure.getMessage(), pass.failure);
        }
    }

    /** The collection of a device's account holding a role, which must exist. */
    protected String role(E2eDevice device, String role) {
        String collection = device.role(address, role);
        assumeTrue("not supported: the account marks no " + role + " mailbox", collection != null);
        return collection;
    }

    /** Skips a test the target cannot run, saying why. */
    protected static void requires(boolean supported, String why) {
        assumeTrue("not supported: " + why, supported);
    }

    /**
     * Holds once the check does, asked again with a growing pause until the
     * patience (`e2e.patience`, seconds) runs out: providers settle late.
     */
    protected static void eventually(String what, BooleanSupplier check) throws Exception {
        settles(what, () -> check.getAsBoolean() ? null : "");
    }

    /**
     * Holds once {@code state} answers null; else what it answers, the state
     * seen last, ends the failure.
     */
    protected static void settles(String what, Callable<String> state) throws Exception {
        long patience = Long.getLong("e2e.patience", 120) * 1000;
        long deadline = System.currentTimeMillis() + patience;
        long pause = 2000;
        String seen;
        while ((seen = state.call()) != null) {
            if (System.currentTimeMillis() + pause > deadline) {
                throw new AssertionError(
                        "not within " + patience / 1000 + " s: " + what
                                + (seen.isEmpty() ? "" : " (" + seen + ")"));
            }
            Thread.sleep(pause);
            pause = Math.min(pause * 2, 15_000);
        }
    }

    /** Fails on a pass failure, naming it. */
    protected static void assertNoFailure(RemotePass.MailPass pass) {
        assertNull(
                "the pass failed: " + (pass.failure == null ? "" : pass.failure.getMessage()),
                pass.failure);
    }
}
