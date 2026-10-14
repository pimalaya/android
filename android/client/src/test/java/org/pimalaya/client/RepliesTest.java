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
public class RepliesTest {
    @Test
    public void errorReplyCarriesTheHttpStatus() {
        try {
            Replies.object("{\"error\": \"WebDAV server returned HTTP 412\", \"status\": 412}");
            fail("an error reply must throw");
        } catch (PimalayaException error) {
            assertEquals("WebDAV server returned HTTP 412", error.getMessage());
            assertEquals(Integer.valueOf(412), error.status);
        }
    }

    @Test
    public void nonHttpErrorReplyCarriesNoStatus() {
        try {
            Replies.object("{\"error\": \"Invalid URL\"}");
            fail("an error reply must throw");
        } catch (PimalayaException error) {
            assertEquals("Invalid URL", error.getMessage());
            assertNull(error.status);
        }
    }

    /** What a held session reopens on: a failure, told without parsing a page. */
    @Test
    public void aFailedReplyIsToldByItsErrorField() {
        assertEquals(true, Replies.failed(" {\"error\":\"Transport read failed\"}"));
        assertEquals(false, Replies.failed("{\"items\":[{\"error\":\"x\"}]}"));
        assertEquals(false, Replies.failed(null));
    }

    /**
     * An update's ETag is the string the reply encodes: Google's own quotes
     * kept, unescaped, so the next listing names the same revision.
     */
    @Test
    public void anUpdatedEtagIsReadAsTheStringTheReplyEncodes() {
        assertEquals(
                "\"3583433504310878\"", Replies.etag("\"\\\"3583433504310878\\\"\""));
        assertEquals("W/\"abc\"", Replies.etag(" \"W/\\\"abc\\\"\" "));
        assertEquals("e-1", Replies.etag("\"e-1\""));
        assertNull(Replies.etag("null"));
    }

    @Test
    public void successReplyPassesThrough() throws Exception {
        assertEquals("ok", Replies.object("{\"value\": \"ok\"}").getString("value"));
    }
}
