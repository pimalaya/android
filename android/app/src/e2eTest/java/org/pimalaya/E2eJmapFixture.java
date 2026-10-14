package org.pimalaya;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A JMAP target prepared over its own session (RFC 8620, RFC 8621).
 *
 * <p>A scratch mailbox is a `Mailbox`, destroyed with its mail; a message is
 * seeded by uploading its bytes and importing the blob.
 */
final class E2eJmapFixture implements E2eFixture {
    private static final String MAIL = "urn:ietf:params:jmap:mail";

    private final String token;
    private final String api;
    private final String upload;
    private final String account;

    E2eJmapFixture(String sessionUrl, String token) throws Exception {
        this.token = token;
        JSONObject session = E2eHttp.json("GET", sessionUrl, token, null);
        this.api = session.getString("apiUrl");
        this.account = session.getJSONObject("primaryAccounts").getString(MAIL);
        this.upload = session.getString("uploadUrl").replace("{accountId}", account);
    }

    @Override
    public void createMailbox(String name) throws Exception {
        JSONObject set =
                call(
                        "Mailbox/set",
                        new JSONObject()
                                .put("create", new JSONObject().put("m", new JSONObject().put("name", name))));
        if (set.optJSONObject("notCreated") != null) {
            throw new IOException("Mailbox/set refused " + name + ": " + set.get("notCreated"));
        }
    }

    @Override
    public void deleteMailbox(String name) throws Exception {
        String id = mailboxId(name);
        if (id != null) {
            call(
                    "Mailbox/set",
                    new JSONObject()
                            .put("destroy", new JSONArray().put(id))
                            .put("onDestroyRemoveEmails", true));
        }
    }

    @Override
    public void seed(String mailbox, E2eMessage message) throws Exception {
        JSONObject blob =
                E2eHttp.send("POST", upload, token, "message/rfc822", message.bytes());
        JSONObject keywords = new JSONObject();
        if (message.seen) {
            keywords.put("$seen", true);
        }
        for (String keyword : message.keywords) {
            keywords.put(keyword, true);
        }
        JSONObject imported =
                call(
                        "Email/import",
                        new JSONObject()
                                .put(
                                        "emails",
                                        new JSONObject()
                                                .put(
                                                        "e",
                                                        new JSONObject()
                                                                .put("blobId", blob.getString("blobId"))
                                                                .put(
                                                                        "mailboxIds",
                                                                        new JSONObject().put(mailboxIdOf(mailbox), true))
                                                                .put("keywords", keywords)
                                                                .put(
                                                                        "receivedAt",
                                                                        message.date
                                                                                .withZoneSameInstant(ZoneOffset.UTC)
                                                                                .format(DateTimeFormatter.ISO_INSTANT)))));
        if (imported.optJSONObject("notCreated") != null) {
            throw new IOException("Email/import refused: " + imported.get("notCreated"));
        }
    }

    @Override
    public List<String> keywords(String mailbox, String subject) throws Exception {
        List<String> ids =
                query(new JSONObject().put("inMailbox", mailboxIdOf(mailbox)).put("subject", subject));
        List<String> keywords = new ArrayList<>();
        if (ids.isEmpty()) {
            return keywords;
        }
        JSONArray emails =
                call(
                                "Email/get",
                                new JSONObject()
                                        .put("ids", new JSONArray(ids))
                                        .put("properties", new JSONArray().put("keywords")))
                        .getJSONArray("list");
        for (int index = 0; index < emails.length(); index++) {
            JSONObject held = emails.getJSONObject(index).getJSONObject("keywords");
            for (Iterator<String> names = held.keys(); names.hasNext(); ) {
                keywords.add(names.next());
            }
        }
        return keywords;
    }

    @Override
    public List<String> where(String subject) throws Exception {
        List<String> ids = query(new JSONObject().put("subject", subject));
        List<String> found = new ArrayList<>();
        if (ids.isEmpty()) {
            return found;
        }
        JSONArray mailboxes =
                call("Mailbox/get", new JSONObject().put("ids", JSONObject.NULL)).getJSONArray("list");
        JSONArray emails =
                call(
                                "Email/get",
                                new JSONObject()
                                        .put("ids", new JSONArray(ids))
                                        .put("properties", new JSONArray().put("mailboxIds")))
                        .getJSONArray("list");
        for (int index = 0; index < emails.length(); index++) {
            JSONObject held = emails.getJSONObject(index).getJSONObject("mailboxIds");
            for (int at = 0; at < mailboxes.length(); at++) {
                JSONObject mailbox = mailboxes.getJSONObject(at);
                if (held.has(mailbox.getString("id"))) {
                    found.add(mailbox.getString("name"));
                }
            }
        }
        return found;
    }

    @Override
    public void sweep(String tag) throws Exception {
        destroy(query(new JSONObject().put("subject", tag)));
    }

    @Override
    public void sweepStale(LocalDateTime before) throws Exception {
        JSONArray mailboxes =
                call("Mailbox/get", new JSONObject().put("ids", JSONObject.NULL)).getJSONArray("list");
        for (int index = 0; index < mailboxes.length(); index++) {
            JSONObject mailbox = mailboxes.getJSONObject(index);
            String name = mailbox.getString("name");
            if (name.startsWith(PREFIX) && E2eFixture.stale(name, before)) {
                call(
                        "Mailbox/set",
                        new JSONObject()
                                .put("destroy", new JSONArray().put(mailbox.getString("id")))
                                .put("onDestroyRemoveEmails", true));
            }
        }
        destroy(
                query(
                        new JSONObject()
                                .put("subject", PREFIX)
                                .put("before", before.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT))));
    }

    private void destroy(List<String> ids) throws Exception {
        if (!ids.isEmpty()) {
            call("Email/set", new JSONObject().put("destroy", new JSONArray(ids)));
        }
    }

    private List<String> query(JSONObject filter) throws Exception {
        JSONArray found =
                call("Email/query", new JSONObject().put("filter", filter).put("limit", 500))
                        .getJSONArray("ids");
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < found.length(); index++) {
            ids.add(found.getString(index));
        }
        return ids;
    }

    private String mailboxIdOf(String name) throws Exception {
        String id = mailboxId(name);
        if (id == null) {
            throw new IOException("no JMAP mailbox " + name);
        }
        return id;
    }

    private String mailboxId(String name) throws Exception {
        JSONArray mailboxes =
                call("Mailbox/get", new JSONObject().put("ids", JSONObject.NULL)).getJSONArray("list");
        for (int index = 0; index < mailboxes.length(); index++) {
            JSONObject mailbox = mailboxes.getJSONObject(index);
            if (name.equals(mailbox.getString("name"))) {
                return mailbox.getString("id");
            }
        }
        return null;
    }

    /** One method call, answering its arguments; a method error fails it. */
    private JSONObject call(String method, JSONObject arguments) throws Exception {
        arguments.put("accountId", account);
        JSONObject request =
                new JSONObject()
                        .put("using", new JSONArray().put("urn:ietf:params:jmap:core").put(MAIL))
                        .put(
                                "methodCalls",
                                new JSONArray().put(new JSONArray().put(method).put(arguments).put("0")));
        JSONArray response =
                E2eHttp.json("POST", api, token, request)
                        .getJSONArray("methodResponses")
                        .getJSONArray(0);
        if ("error".equals(response.getString(0))) {
            throw new IOException(method + " failed: " + response.get(1));
        }
        return response.getJSONObject(1);
    }
}
