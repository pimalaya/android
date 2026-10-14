package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.Clients;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Drives ContactStager.serve with hand-built yield envelopes, as Rust does.
 *
 * <p>Storage ops run against the real store and bridge: the driver's wire
 * contract, pinned without a network.
 */
@RunWith(RobolectricTestRunner.class)
public class ContactEngineServeTest {
    private static final String EMAIL = "jane@example.com";
    private static final String BOOK = "https://dav.example.com/books/b1/";

    private CardStore store;
    private ContactStager engine;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        PimdirDb pimdir = new PimdirDb(context);
        store = new CardStore(context, pimdir);

        List<Addressbook> books = List.of(new Addressbook("b1", "Book One", BOOK, null, null));
        store.replaceAddressbooks(EMAIL, books);
        new PimdirCollections(pimdir, context).replace(
                EMAIL, PimdirSummary.CONTACT, PimdirCollections.of(EMAIL, books));

        engine = new ContactStager(pimdir, Clients.real());
    }

    @Test
    public void serveWritesThenLoadsTheCollection() throws Exception {
        String vcard =
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:u1\r\nFN:Jane Doe\r\nEND:VCARD\r\n";
        String hash = CardStore.byteHash(vcard);

        JSONObject write = new JSONObject();
        write.put("op", "write");
        write.put(
                "writes",
                new JSONArray()
                        .put(
                                new JSONObject()
                                        .put("op", "storeObject")
                                        .put("hash", hash)
                                        .put("size", vcard.length())
                                        .put("body", vcard))
                        .put(
                                new JSONObject()
                                        .put("op", "upsert")
                                        .put(
                                                "placement",
                                                new JSONObject()
                                                        .put("collection", BOOK)
                                                        .put("handle", "c1.vcf")
                                                        // NOTE: a fetched body
                                                        // carries its identity:
                                                        // without a link id
                                                        // it is a probe.
                                                        .put("linkId", "uid-c1")
                                                        .put("object", hash)
                                                        .put("level", "full")
                                                        .put("status", "clean")
                                                        .put("flags", new JSONArray())
                                                        .put(
                                                                "base",
                                                                new JSONObject()
                                                                        .put("revision", "e1")
                                                                        .put("object", hash)))));
        assertEquals("{}", engine.serve(write.toString()));

        JSONObject load = new JSONObject();
        load.put("op", "load");
        load.put("collection", BOOK);
        JSONObject reply = new JSONObject(engine.serve(load.toString()));

        JSONArray placements = reply.getJSONArray("placements");
        assertEquals(1, placements.length());
        JSONObject placement = placements.getJSONObject(0);
        assertEquals("c1.vcf", placement.getString("handle"));
        assertEquals("clean", placement.getString("status"));
        assertEquals("full", placement.getString("level"));
        assertEquals("e1", placement.getJSONObject("base").getString("revision"));
    }

    @Test
    public void serveRejectsUnknownYields() throws Exception {
        JSONObject reply =
                new JSONObject(engine.serve("{\"op\": \"teleport\", \"collection\": \"x\"}"));
        assertTrue(reply.getString("error").contains("Unsupported engine yield"));
    }

    @Test
    public void serveWrapsDriverFailuresAsErrorReplies() throws Exception {
        // NOTE: the Rust engine aborts its run on the error field, so a
        // malformed envelope answers an error reply, never an exception.
        JSONObject reply = new JSONObject(engine.serve("{\"op\": \"load\"}"));
        assertTrue(reply.getString("error").length() > 0);
    }
}
