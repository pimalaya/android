package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Pins the bridge reply parsing: an error reply raises a
 * PimalayaException carrying the message and, when the failure was an
 * HTTP round, the status the callers branch on (412 retries unguarded,
 * 404 converges a removal, 401 refreshes the token).
 */
public class PimalayaClientTest {
    @Test
    public void errorReplyCarriesTheHttpStatus() {
        try {
            PimalayaClient.object("{\"error\": \"WebDAV server returned HTTP 412\", \"status\": 412}");
            fail("an error reply must throw");
        } catch (PimalayaException error) {
            assertEquals("WebDAV server returned HTTP 412", error.getMessage());
            assertEquals(Integer.valueOf(412), error.status);
        }
    }

    @Test
    public void nonHttpErrorReplyCarriesNoStatus() {
        try {
            PimalayaClient.object("{\"error\": \"Invalid URL\"}");
            fail("an error reply must throw");
        } catch (PimalayaException error) {
            assertEquals("Invalid URL", error.getMessage());
            assertNull(error.status);
        }
    }

    /** What a held session reopens on: a failure, told without parsing a page. */
    @Test
    public void aFailedReplyIsToldByItsErrorField() {
        assertEquals(true, PimalayaClient.failed(" {\"error\":\"Transport read failed\"}"));
        assertEquals(false, PimalayaClient.failed("{\"items\":[{\"error\":\"x\"}]}"));
        assertEquals(false, PimalayaClient.failed(null));
    }

    /**
     * An update's ETag is the string the reply encodes: Google's own quotes
     * kept, unescaped, so the next listing names the same revision.
     */
    @Test
    public void anUpdatedEtagIsReadAsTheStringTheReplyEncodes() {
        assertEquals(
                "\"3583433504310878\"", PimalayaClient.etag("\"\\\"3583433504310878\\\"\""));
        assertEquals("W/\"abc\"", PimalayaClient.etag(" \"W/\\\"abc\\\"\" "));
        assertEquals("e-1", PimalayaClient.etag("\"e-1\""));
        assertNull(PimalayaClient.etag("null"));
    }

    @Test
    public void successReplyPassesThrough() throws Exception {
        assertEquals("ok", PimalayaClient.object("{\"value\": \"ok\"}").getString("value"));
    }
}
