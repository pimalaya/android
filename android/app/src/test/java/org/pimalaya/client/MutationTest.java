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
    public void flagsRemoveMoveAndCopy() throws Exception {
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
        stager.mutateCopy("c", "h5", "t");
        assertSameJson("{\"op\":\"copy\",\"handle\":\"h5\",\"target\":\"t\"}", last());
        assertEquals(5, client.sent.size());
    }

    @Test
    public void edits() throws Exception {
        stager.mutateEdit("c", "h6", "BODY \u00e9", new JSONObject().put("subject", "S"), "k1");
        assertSameJson(
                "{\"op\":\"edit\",\"handle\":\"h6\",\"hash\":\"s4eze32kzmmfwesdoapqv6fkkq\","
                        + "\"size\":7,\"body\":\"BODY \u00e9\",\"summary\":{\"subject\":\"S\"},"
                        + "\"sortKey\":\"k1\"}",
                last());
        stager.mutateEdit("c", "h7", "BODY", null, "");
        assertSameJson(
                "{\"op\":\"edit\",\"handle\":\"h7\",\"hash\":\"gdt6ihx2wmnix2vfclverksnte\","
                        + "\"size\":4,\"body\":\"BODY\",\"sortKey\":\"\"}",
                last());
        stager.mutateEdit("c", "h8", "B", null, null);
        assertSameJson(
                "{\"op\":\"edit\",\"handle\":\"h8\",\"hash\":\"357hbziccvcpja2lx3tevhrxre\","
                        + "\"size\":1,\"body\":\"B\"}",
                last());
    }

    @Test
    public void adds() throws Exception {
        stager.mutateAdd(
                "c", "L1", "TEXT", Flags.of(Flags.Marker.SEEN), new JSONObject().put("a", 1), "k");
        assertSameJson(
                "{\"op\":\"add\",\"linkId\":\"L1\",\"flags\":[\"\\\\Seen\"],"
                        + "\"hash\":\"4s5cc22t4z25htsfvbme3z46zq\",\"size\":4,\"body\":\"TEXT\","
                        + "\"summary\":{\"a\":1},\"sortKey\":\"k\"}",
                last());
        stager.mutateAdd("c", "L2", "TEXT", (Flags) null, null, null);
        assertSameJson(
                "{\"op\":\"add\",\"linkId\":\"L2\",\"hash\":\"4s5cc22t4z25htsfvbme3z46zq\","
                        + "\"size\":4,\"body\":\"TEXT\"}",
                last());
        stager.mutateAdd("c", "L3", new byte[] {(byte) 0xff, 0, 'a'}, Flags.none(), null, "");
        assertSameJson(
                "{\"op\":\"add\",\"linkId\":\"L3\",\"flags\":[],"
                        + "\"hash\":\"7f4jm5nclkdwawynma4hk2hclq\",\"size\":3,"
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
                        + "\"hash\":\"xu6jar6wqfwdc4xvfxnufp3xy4\",\"size\":3,"
                        + "\"bodyBase64\":\"eC95\",\"sortKey\":\"k\"}",
                last());
    }
}
