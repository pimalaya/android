package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.pimalaya.client.ReplyShape.assertSameJson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.mail.MailStager;
import org.pimalaya.pimdir.PimdirDb;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Every staged mutation equals, as JSON, what the stager sent before.
 *
 * <p>The literals were captured on that code, for the same inputs.
 */
@RunWith(RobolectricTestRunner.class)
public class MutationTest {
    /** The bridge, every staged mutation kept instead of sent. */
    private static final class Recording extends OfflineClient {
        final List<String> sent = new ArrayList<>();

        @Override
        public void mutate(OfflineDriver driver, String collection, Mutation mutation) {
            sent.add(mutation.toJson().toString());
        }
    }

    private final Recording client = new Recording();

    private MailStager stager;

    @Before
    public void setUp() {
        stager =
                new MailStager(
                        new PimdirDb(RuntimeEnvironment.getApplication()),
                        new Clients(
                                new AccountClient(), new ContactClient(), new CalendarClient(),
                                new MailClient(), client));
    }

    private String last() {
        return client.sent.get(client.sent.size() - 1);
    }

    @Test
    public void flagsRemoveAndMove() throws Exception {
        stager.mutateFlags("c", "h1", new Flags(List.of("\\Seen", "$junk")));
        assertSameJson(
                "{\"op\":\"setFlags\",\"handle\":\"h1\",\"flags\":[\"\\\\Seen\",\"$junk\"]}",
                last());
        stager.mutateFlags("c", "h2", Flags.none());
        assertSameJson("{\"op\":\"setFlags\",\"handle\":\"h2\",\"flags\":[]}", last());
        stager.mutateRemove("c", "h3");
        assertSameJson("{\"op\":\"remove\",\"handle\":\"h3\"}", last());
        stager.mutateMove("c", "h4", "t");
        assertSameJson("{\"op\":\"move\",\"handle\":\"h4\",\"target\":\"t\"}", last());
        assertEquals(4, client.sent.size());
    }

    /** The name the store files a text body under (BLAKE3, objects.json). */
    private static String name(String body) {
        return PimdirSql.hash(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void edits() throws Exception {
        stager.mutateEdit("c", "h6", "BODY \u00e9", new JSONObject().put("subject", "S"), "k1");
        assertSameJson(
                "{\"op\":\"edit\",\"handle\":\"h6\",\"hash\":\"" + name("BODY \u00e9") + "\","
                        + "\"size\":7,\"body\":\"BODY \u00e9\",\"summary\":{\"subject\":\"S\"},"
                        + "\"sortKey\":\"k1\"}",
                last());
        stager.mutateEdit("c", "h7", "BODY", null, "");
        assertSameJson(
                "{\"op\":\"edit\",\"handle\":\"h7\",\"hash\":\"" + name("BODY") + "\","
                        + "\"size\":4,\"body\":\"BODY\",\"sortKey\":\"\"}",
                last());
        stager.mutateEdit("c", "h8", "B", null, null);
        assertSameJson(
                "{\"op\":\"edit\",\"handle\":\"h8\",\"hash\":\"" + name("B") + "\","
                        + "\"size\":1,\"body\":\"B\"}",
                last());
    }

    /**
     * A resolution states the diverging bodies it was decided over (SYNC
     * §7), an ordinary edit no {@code resolves} key at all, never null nor
     * an empty array.
     */
    @Test
    public void aResolutionNamesWhatItResolves() throws Exception {
        stager.mutateResolve("c", "h9", "B", null, "", List.of(name("R1"), name("R2")));
        assertSameJson(
                "{\"op\":\"edit\",\"handle\":\"h9\",\"hash\":\"" + name("B") + "\","
                        + "\"size\":1,\"body\":\"B\",\"sortKey\":\"\","
                        + "\"resolves\":[\"" + name("R1") + "\",\"" + name("R2") + "\"]}",
                last());
        stager.mutateResolve("c", "h9", "B", null, "", null);
        assertSameJson(
                "{\"op\":\"edit\",\"handle\":\"h9\",\"hash\":\"" + name("B") + "\","
                        + "\"size\":1,\"body\":\"B\",\"sortKey\":\"\"}",
                last());
    }

    @Test
    public void adds() throws Exception {
        stager.mutateAdd(
                "c", "L1", "TEXT", Flags.of(Flags.Marker.SEEN), new JSONObject().put("a", 1), "k");
        assertSameJson(
                "{\"op\":\"add\",\"linkId\":\"L1\",\"flags\":[\"\\\\Seen\"],"
                        + "\"hash\":\"" + name("TEXT") + "\",\"size\":4,\"body\":\"TEXT\","
                        + "\"summary\":{\"a\":1},\"sortKey\":\"k\"}",
                last());
        stager.mutateAdd("c", "L2", "TEXT", (Flags) null, null, null);
        assertSameJson(
                "{\"op\":\"add\",\"linkId\":\"L2\",\"hash\":\"" + name("TEXT") + "\","
                        + "\"size\":4,\"body\":\"TEXT\"}",
                last());
        stager.mutateAdd("c", "L3", new byte[] {(byte) 0xff, 0, 'a'}, Flags.none(), null, "");
        assertSameJson(
                "{\"op\":\"add\",\"linkId\":\"L3\",\"flags\":[],"
                        + "\"hash\":\"" + PimdirSql.hash(new byte[] {(byte) 0xff, 0, 'a'}) + "\",\"size\":3,"
                        + "\"bodyBase64\":\"\\/wBh\",\"sortKey\":\"\"}",
                last());
        stager.mutateAdd(
                "c",
                "L4",
                "x/y".getBytes(StandardCharsets.UTF_8),
                new Flags(List.of("\\Seen", "a/b")),
                null,
                "k");
        assertSameJson(
                "{\"op\":\"add\",\"linkId\":\"L4\",\"flags\":[\"\\\\Seen\",\"a\\/b\"],"
                        + "\"hash\":\"" + name("x/y") + "\",\"size\":3,"
                        + "\"bodyBase64\":\"eC95\",\"sortKey\":\"k\"}",
                last());
    }
}
