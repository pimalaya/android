package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.pimalaya.client.ReplyShape.assertMirrors;
import static org.pimalaya.client.ReplyShape.assertSameJson;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Pins the offline records on the real bridge: the facts each plan verb
 * is sent equal, as JSON values, to what the engine built before they
 * were records (literals captured on that code), every reply parsed to
 * the values its text carries, and every record mirroring its reply.
 */
@RunWith(RobolectricTestRunner.class)
public class OfflineClientTest {
    /** A driver over an empty collection on an empty remote. */
    private static final OfflineDriver EMPTY =
            yielded -> {
                try {
                    switch (new JSONObject(yielded).getString("op")) {
                        case "enumerate":
                            return "{\"items\":[],\"vanished\":[],\"complete\":true}";
                        default:
                            return "{}";
                    }
                } catch (Exception error) {
                    return "{\"error\":\"" + error.getMessage() + "\"}";
                }
            };

    @Test
    public void aSyncReportMirrorsItsReply() {
        String raw = Native.offlineSync(EMPTY, "c", false, true, "", true);
        assertMirrors(SyncReport.class, raw);
        assertEquals(new SyncReport(0, 0, 0, 0, 0, 0), new OfflineClient().sync(EMPTY, "c", false));
    }

    @Test
    public void aSyncReportReadsEveryCount() throws Exception {
        SyncReport report =
                SyncReport.from(
                        new JSONObject(
                                "{\"conflicts\":1,\"pulled\":2,\"pushed\":3,\"refreshed\":4,"
                                        + "\"rejected\":5,\"waiting\":6}"));
        assertEquals(new SyncReport(1, 2, 3, 4, 5, 6), report);
    }

    @Test
    public void anUpgradeReportMirrorsItsReply() throws Exception {
        String raw = Native.offlineUpgrade(EMPTY, "c", "[]", true);
        assertMirrors(UpgradeReport.class, raw);
        assertEquals(
                new UpgradeReport(1, 2, 3),
                UpgradeReport.from(
                        new JSONObject("{\"deduped\":1,\"fetched\":2,\"upgraded\":3}")));
    }

    @Test
    public void snapshotFactsAreTheOnesTheEngineBuilt() throws Exception {
        AccountSnapshotFacts facts =
                new AccountSnapshotFacts(
                        "b1",
                        false,
                        List.of(
                                new SnapshotCard("a.vcf", List.of("b1", "b2"), true),
                                new SnapshotCard("b.vcf", List.of(), false)),
                        List.of("gone.vcf"));
        assertSameJson(
                "{\"bookId\":\"b1\",\"complete\":false,\"changed\":[{\"handle\":\"a.vcf\","
                        + "\"books\":[\"b1\",\"b2\"],\"known\":true},{\"handle\":\"b.vcf\","
                        + "\"books\":[],\"known\":false}],\"vanished\":[\"gone.vcf\"]}",
                facts.toJson().toString());
        assertEquals(
                new AccountSnapshot(List.of(0), List.of("gone.vcf")),
                OfflineClient.accountSnapshot(facts));

        AccountSnapshotFacts unknown = new AccountSnapshotFacts(null, true, List.of(), List.of());
        assertSameJson(
                "{\"complete\":true,\"changed\":[],\"vanished\":[]}", unknown.toJson().toString());
        assertEquals(
                new AccountSnapshot(List.of(), List.of()), OfflineClient.accountSnapshot(unknown));
    }

    @Test
    public void aKnownCardLeavingTheBookRidesAsVanished() {
        AccountSnapshotFacts facts =
                new AccountSnapshotFacts(
                        "b1",
                        false,
                        List.of(
                                new SnapshotCard("a.vcf", List.of("b1"), true),
                                new SnapshotCard("c.vcf", List.of("b2"), true)),
                        List.of("gone.vcf"));
        String raw = Native.offlineAccountSnapshot(facts.toJson().toString());
        assertMirrors(AccountSnapshot.class, raw);
        assertEquals(
                new AccountSnapshot(List.of(0), List.of("gone.vcf", "c.vcf")),
                OfflineClient.accountSnapshot(facts));
    }

    @Test
    public void pushPlanFactsAreTheOnesTheEngineBuilt() throws Exception {
        String google = "https:\\/\\/www.googleapis.com\\/x";
        String dav = "https:\\/\\/dav.example.org\\/x";
        assertSameJson(
                "{\"op\":\"add\",\"collection\":\"" + google + "\",\"bookId\":\"g1\","
                        + "\"origin\":true,\"deleted\":false}",
                plan(PushPlanFacts.Op.ADD, "https://www.googleapis.com/x", "g1", true, false));
        assertSameJson(
                "{\"op\":\"add\",\"collection\":\"" + dav + "\",\"origin\":false,"
                        + "\"deleted\":false}",
                plan(PushPlanFacts.Op.ADD, "https://dav.example.org/x", null, false, false));
        assertSameJson(
                "{\"op\":\"remove\",\"collection\":\"" + dav + "\",\"origin\":false,"
                        + "\"deleted\":true}",
                plan(PushPlanFacts.Op.REMOVE, "https://dav.example.org/x", null, false, true));
    }

    private static String plan(
            PushPlanFacts.Op op, String url, String book, boolean origin, boolean deleted) {
        return new PushPlanFacts(op, url, book, origin, deleted).toJson().toString();
    }

    @Test
    public void everyPushActionComesFromTheBridge() {
        PushPlanFacts googleAdd =
                new PushPlanFacts(PushPlanFacts.Op.ADD, "google://a@b/g1", "g1", false, false);
        String raw = Native.offlinePushPlan(googleAdd.toJson().toString());
        assertMirrors(PushPlan.class, raw);
        assertEquals(
                new PushPlan(PushAction.CREATE, List.of("g1")), OfflineClient.pushPlan(googleAdd));

        assertEquals(
                new PushPlan(PushAction.CREATE, null),
                OfflineClient.pushPlan(
                        new PushPlanFacts(
                                PushPlanFacts.Op.ADD, "google://a@b/contacts", "myContacts",
                                false, false)));
        assertEquals(
                new PushPlan(PushAction.MEMBERSHIP, null),
                OfflineClient.pushPlan(
                        new PushPlanFacts(
                                PushPlanFacts.Op.ADD, "jmap://host/b1", "b1", true, false)));
        assertEquals(
                new PushPlan(PushAction.CREATE, null),
                OfflineClient.pushPlan(
                        new PushPlanFacts(
                                PushPlanFacts.Op.ADD, "https://dav/b1", null, true, false)));
        assertEquals(
                new PushPlan(PushAction.MEMBERSHIP, null),
                OfflineClient.pushPlan(
                        new PushPlanFacts(
                                PushPlanFacts.Op.REMOVE, "jmap://host/b1", "b1", false, false)));
        assertEquals(
                new PushPlan(PushAction.DELETE, null),
                OfflineClient.pushPlan(
                        new PushPlanFacts(
                                PushPlanFacts.Op.REMOVE, "jmap://host/b1", "b1", false, true)));
    }

    @Test(expected = PimalayaException.class)
    public void anUnknownPushActionIsUnreadable() throws Exception {
        PushPlan.from(new JSONObject("{\"action\":\"patch\"}"));
    }

    @Test
    public void retryFactsAreTheOnesTheEngineBuilt() throws Exception {
        Map<String, String> etags = new LinkedHashMap<>();
        etags.put("a.vcf", "e1");
        etags.put("b.vcf", null);
        String listed = "\"listed\":{\"a.vcf\":\"e1\",\"b.vcf\":null}";

        RetryFacts unchanged = new RetryFacts(etags, true, "a.vcf", "e1");
        assertSameJson(
                "{" + listed + ",\"complete\":true,\"handle\":\"a.vcf\",\"ifMatch\":\"e1\"}",
                unchanged.toJson().toString());
        assertTrue(OfflineClient.retryUnguarded(unchanged));

        RetryFacts noEtag = new RetryFacts(etags, false, "b.vcf", "e1");
        assertSameJson(
                "{" + listed + ",\"complete\":false,\"handle\":\"b.vcf\",\"ifMatch\":\"e1\"}",
                noEtag.toJson().toString());
        assertFalse(OfflineClient.retryUnguarded(noEtag));

        RetryFacts unlisted = new RetryFacts(etags, false, "c.vcf", "e1");
        assertSameJson(
                "{" + listed + ",\"complete\":false,\"handle\":\"c.vcf\",\"ifMatch\":\"e1\"}",
                unlisted.toJson().toString());
        assertTrue(OfflineClient.retryUnguarded(unlisted));

        RetryFacts unguarded = new RetryFacts(etags, false, "a.vcf", null);
        assertSameJson(
                "{" + listed + ",\"complete\":false,\"handle\":\"a.vcf\"}",
                unguarded.toJson().toString());
        assertFalse(OfflineClient.retryUnguarded(unguarded));

        RetryFacts nothingListed = new RetryFacts(null, false, "a.vcf", "e1");
        assertSameJson(
                "{\"complete\":false,\"handle\":\"a.vcf\",\"ifMatch\":\"e1\"}",
                nothingListed.toJson().toString());
        assertFalse(OfflineClient.retryUnguarded(nothingListed));
    }

    @Test
    public void aPushPlanWithoutBooksReadsNull() throws Exception {
        assertNull(PushPlan.from(new JSONObject("{\"action\":\"create\"}")).postCreateBooks());
    }
}
