package org.pimalaya;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * A Google target prepared through the Gmail API, whatever it reads over.
 *
 * <p>A scratch mailbox is a user label, a mailbox to IMAP too; a sweep deletes
 * tagged messages for good, a removed label leaving its mail in All Mail.
 */
final class E2eGmailFixture implements E2eFixture {
    private static final String API = "https://gmail.googleapis.com/gmail/v1/users/me/";

    private final Callable<String> token;

    E2eGmailFixture(Callable<String> token) {
        this.token = token;
    }

    @Override
    public void createMailbox(String name) throws Exception {
        E2eHttp.json(
                "POST",
                API + "labels",
                token.call(),
                new JSONObject()
                        .put("name", name)
                        .put("labelListVisibility", "labelShow")
                        .put("messageListVisibility", "show"));
    }

    @Override
    public void deleteMailbox(String name) throws Exception {
        String id = labelId(name);
        if (id != null) {
            E2eHttp.json("DELETE", API + "labels/" + id, token.call(), null);
        }
    }

    @Override
    public void seed(String mailbox, E2eMessage message) throws Exception {
        seed(List.of(mailbox), message);
    }

    @Override
    public void seed(List<String> mailboxes, E2eMessage message) throws Exception {
        JSONArray labels = new JSONArray();
        for (String mailbox : mailboxes) {
            labels.put(labelIdOf(mailbox));
        }
        if (!message.seen) {
            labels.put("UNREAD");
        }
        // NOTE: the internal date from the `Date` header, so a message seeded
        // in the past sorts where its header says.
        E2eHttp.json(
                "POST",
                API + "messages?internalDateSource=dateHeader",
                token.call(),
                new JSONObject()
                        .put("raw", Base64.getUrlEncoder().encodeToString(message.bytes()))
                        .put("labelIds", labels));
    }

    @Override
    public void unfile(String mailbox, String subject) throws Exception {
        String label = labelIdOf(mailbox);
        for (String id : find("subject:\"" + subject + "\"")) {
            E2eHttp.json(
                    "POST",
                    API + "messages/" + id + "/modify",
                    token.call(),
                    new JSONObject().put("removeLabelIds", new JSONArray().put(label)));
        }
    }

    @Override
    public List<String> where(String subject) throws Exception {
        Map<String, String> names = new HashMap<>();
        JSONArray all =
                E2eHttp.json("GET", API + "labels", token.call(), null).optJSONArray("labels");
        for (int index = 0; all != null && index < all.length(); index++) {
            JSONObject label = all.getJSONObject(index);
            names.put(label.getString("id"), label.getString("name"));
        }
        List<String> labels = new ArrayList<>();
        for (String id : find("subject:\"" + subject + "\"")) {
            JSONArray held =
                    E2eHttp.json(
                                    "GET",
                                    API + "messages/" + id + "?format=minimal",
                                    token.call(),
                                    null)
                            .optJSONArray("labelIds");
            for (int index = 0; held != null && index < held.length(); index++) {
                labels.add(names.getOrDefault(held.getString(index), held.getString(index)));
            }
        }
        return labels;
    }

    @Override
    public void sweep(String tag) throws Exception {
        delete("subject:\"" + tag + "\"");
    }

    @Override
    public void sweepStale(LocalDateTime before) throws Exception {
        JSONArray labels = E2eHttp.json("GET", API + "labels", token.call(), null)
                .optJSONArray("labels");
        for (int index = 0; labels != null && index < labels.length(); index++) {
            JSONObject label = labels.getJSONObject(index);
            String name = label.getString("name");
            if (name.startsWith(PREFIX) && E2eFixture.stale(name, before)) {
                E2eHttp.json("DELETE", API + "labels/" + label.getString("id"), token.call(), null);
            }
        }
        delete("subject:\"" + PREFIX + "\" before:" + before.toEpochSecond(ZoneOffset.UTC));
    }

    private void delete(String query) throws Exception {
        for (String id : find(query)) {
            try {
                E2eHttp.json("DELETE", API + "messages/" + id, token.call(), null);
            } catch (IOException gone) {
                // NOTE: Gmail answers a self-sent copy's delete with a 404 or a
                // 500 while its delivery lands; the next sweep takes it.
            }
        }
    }

    /** The ids of the messages a search finds, trash and spam included. */
    private List<String> find(String query) throws Exception {
        List<String> ids = new ArrayList<>();
        String page = null;
        do {
            String url =
                    API + "messages?includeSpamTrash=true&maxResults=500&q="
                            + URLEncoder.encode(query, "UTF-8")
                            + (page == null ? "" : "&pageToken=" + page);
            JSONObject answer = E2eHttp.json("GET", url, token.call(), null);
            JSONArray messages = answer.optJSONArray("messages");
            for (int index = 0; messages != null && index < messages.length(); index++) {
                ids.add(messages.getJSONObject(index).getString("id"));
            }
            page = answer.optString("nextPageToken", null);
        } while (page != null);
        return ids;
    }

    private String labelIdOf(String name) throws Exception {
        String id = labelId(name);
        if (id == null) {
            throw new IOException("no Gmail label " + name);
        }
        return id;
    }

    private String labelId(String name) throws Exception {
        JSONArray labels = E2eHttp.json("GET", API + "labels", token.call(), null)
                .optJSONArray("labels");
        for (int index = 0; labels != null && index < labels.length(); index++) {
            JSONObject label = labels.getJSONObject(index);
            if (name.equals(label.getString("name"))) {
                return label.getString("id");
            }
        }
        return null;
    }
}
