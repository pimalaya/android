package org.pimalaya.client;

import org.json.JSONObject;

/**
 * The mail records' package-private wire, for other packages' engine tests.
 *
 * <p>A step request as the bridge reads it, a step reply as the client parses
 * it.
 */
public final class MailWire {
    private MailWire() {}

    /**
     * {@code request} is the JSON value {@code expected} (absent is not null).
     */
    public static void assertSameJson(String expected, StepRequest request) throws Exception {
        ReplyShape.assertSameJson(expected, request.toJson().toString());
    }

    /**
     * {@code actual} is the JSON value {@code expected} (absent is not null).
     */
    public static void assertSameJson(String expected, JSONObject actual) throws Exception {
        ReplyShape.assertSameJson(expected, actual.toString());
    }

    /** A Gmail or JMAP step reply as {@link MailClient#step} parses it. */
    public static NamedStep namedStep(String raw) throws Exception {
        return NamedStep.from(new JSONObject(raw));
    }
}
